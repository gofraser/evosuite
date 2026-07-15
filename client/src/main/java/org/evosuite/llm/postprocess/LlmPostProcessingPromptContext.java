/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.llm.postprocess;

import org.evosuite.Properties;
import org.evosuite.assertion.Assertion;
import org.evosuite.assertion.ArrayLengthAssertion;
import org.evosuite.assertion.CheapPurityAnalyzer;
import org.evosuite.assertion.ContainsAssertion;
import org.evosuite.assertion.EqualsAssertion;
import org.evosuite.assertion.Inspector;
import org.evosuite.assertion.InspectorManager;
import org.evosuite.assertion.NullAssertion;
import org.evosuite.setup.TestUsageChecker;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.FieldStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test-local prompt context with stable statement and variable IDs.
 */
public final class LlmPostProcessingPromptContext {

    private static final Set<String> DENIED_INSPECTOR_METHODS = new LinkedHashSet<>(Arrays.asList(
            "wait",
            "notify",
            "notifyAll",
            "getClass",
            "hashCode",
            "finalize",
            "toString"));

    private final LlmPostProcessingReferences references;
    private final List<StatementContext> statements;
    private final List<Observation> observations;
    private final List<CallableMember> callableMembers;
    private final List<ExceptionContext> exceptions;
    private final List<CandidateFact> candidateFacts;

    private LlmPostProcessingPromptContext(LlmPostProcessingReferences references,
                                           List<StatementContext> statements,
                                           List<Observation> observations,
                                           List<CallableMember> callableMembers,
                                           List<ExceptionContext> exceptions,
                                           List<CandidateFact> candidateFacts) {
        this.references = references;
        this.statements = Collections.unmodifiableList(new ArrayList<>(statements));
        this.observations = Collections.unmodifiableList(new ArrayList<>(observations));
        this.callableMembers = Collections.unmodifiableList(new ArrayList<>(callableMembers));
        this.exceptions = Collections.unmodifiableList(new ArrayList<>(exceptions));
        this.candidateFacts = Collections.unmodifiableList(new ArrayList<>(candidateFacts));
    }

    public static LlmPostProcessingPromptContext from(TestCase test) {
        return from(test, null);
    }

    public static LlmPostProcessingPromptContext from(TestCase test, ExecutionResult executionResult) {
        return from(test, executionResult, Collections.emptyList());
    }

    public static LlmPostProcessingPromptContext from(TestCase test, ExecutionResult executionResult,
                                                      List<Assertion> candidateAssertions) {
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(test);
        List<StatementContext> statements = new ArrayList<>();
        List<Observation> observations = new ArrayList<>();
        List<CallableMember> callableMembers = new ArrayList<>();
        Set<String> advertisedTypes = new LinkedHashSet<>();
        List<ExceptionContext> exceptions = exceptionContexts(executionResult);
        List<CandidateFact> candidateFacts = candidateFacts(references, candidateAssertions);
        // A candidate fact already tells the model this variable's observed value,
        // so emitting the same value again as an observation is redundant (plan 6.6:
        // observations are only supplemental to the candidate pool).
        Set<String> candidateCoveredVariableIds = new LinkedHashSet<>();
        for (CandidateFact fact : candidateFacts) {
            if (fact.getSourceId() != null && fact.getObservedValue() != null) {
                candidateCoveredVariableIds.add(fact.getSourceId());
            }
        }
        Scope finalScope = executionResult == null ? null : executionResult.getFinalScope();
        if (test != null) {
            for (int position = 0; position < test.size(); position++) {
                Statement statement = test.getStatement(position);
                String statementId = LlmPostProcessingReferences.statementId(position);
                String variableId = references.hasVariableId(LlmPostProcessingReferences.variableId(position))
                        ? LlmPostProcessingReferences.variableId(position)
                        : null;
                VariableReference returnValue = statement.getReturnValue();
                statements.add(new StatementContext(
                        statementId,
                        variableId,
                        declaredType(returnValue),
                        runtimeType(returnValue, finalScope),
                        normalizeCode(statement.getCode())));
                Observation observation = primitiveInputObservation(statementId, variableId, statement);
                if (observation != null) {
                    observations.add(observation);
                }
                Observation finalScopeObservation = finalScopeObservation(statementId, variableId, statement, returnValue,
                        finalScope);
                if (finalScopeObservation != null
                        && (variableId == null || !candidateCoveredVariableIds.contains(variableId))) {
                    observations.add(finalScopeObservation);
                }
                Object runtimeValue = finalScopeValue(returnValue, finalScope);
                callableMembers.addAll(callableMembers(variableId, returnValue, runtimeValue, advertisedTypes));
            }
        }
        return new LlmPostProcessingPromptContext(references, statements, observations, callableMembers, exceptions,
                candidateFacts);
    }

    public LlmPostProcessingReferences getReferences() {
        return references;
    }

    public List<StatementContext> getStatements() {
        return statements;
    }

    public List<Observation> getObservations() {
        return observations;
    }

    public List<CallableMember> getCallableMembers() {
        return callableMembers;
    }

    public List<ExceptionContext> getExceptions() {
        return exceptions;
    }

    public List<CandidateFact> getCandidateFacts() {
        return candidateFacts;
    }

    public String toAnnotatedText() {
        StringBuilder builder = new StringBuilder();
        for (StatementContext statement : statements) {
            builder.append(statement.getStatementId());
            if (statement.getVariableId() != null) {
                builder.append(" ").append(statement.getVariableId());
            }
            if (statement.getDeclaredType() != null) {
                builder.append(" : ").append(statement.getDeclaredType());
            }
            if (statement.getRuntimeType() != null
                    && !statement.getRuntimeType().equals(statement.getDeclaredType())) {
                builder.append(" runtime=").append(statement.getRuntimeType());
            }
            builder.append(" | ").append(statement.getCode());
            if (!statement.getCode().endsWith("\n")) {
                builder.append('\n');
            }
        }
        return builder.toString();
    }

    public String toObservationText() {
        if (observations.isEmpty()) {
            return "none\n";
        }
        StringBuilder builder = new StringBuilder();
        int maxChars = Properties.LLM_POSTPROCESSING_MAX_OBSERVATION_CHARS;
        for (Observation observation : observations) {
            String line = observationLine(observation);
            if (maxChars > 0 && builder.length() + line.length() > maxChars) {
                String truncationLine = "truncated=true\n";
                if (builder.length() + truncationLine.length() <= maxChars) {
                    builder.append(truncationLine);
                }
                break;
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private String observationLine(Observation observation) {
        StringBuilder builder = new StringBuilder();
        builder.append(observation.getStatementId());
        if (observation.getVariableId() != null) {
            builder.append(" ").append(observation.getVariableId());
        }
        builder.append(" provenance=").append(observation.getProvenance());
        builder.append(" complete=").append(observation.isComplete());
        builder.append(" relationalOnly=").append(observation.isRelationalOnly());
        builder.append(" value=").append(observation.getValue());
        if (!observation.isComplete()) {
            builder.append(" note=truncated");
        }
        builder.append('\n');
        return builder.toString();
    }

    public String toCallableMemberText() {
        if (callableMembers.isEmpty()) {
            return "none\n";
        }
        // Advertise each owner type's callable catalogue once, plus compact
        // receiver->type bindings and observed results, so two receivers sharing a
        // type do not repeat the whole member list. The section is bounded by the
        // same observation char budget.
        Map<String, LinkedHashSet<String>> membersByType = new LinkedHashMap<>();
        LinkedHashSet<String> receiverBindings = new LinkedHashSet<>();
        LinkedHashSet<String> observedResults = new LinkedHashSet<>();
        for (CallableMember member : callableMembers) {
            LinkedHashSet<String> typeMembers = membersByType.get(member.getOwnerType());
            if (typeMembers == null) {
                typeMembers = new LinkedHashSet<>();
                membersByType.put(member.getOwnerType(), typeMembers);
            }
            typeMembers.add(member.getSignature() + "->" + member.getReturnType());
            if (member.getReceiverId() != null) {
                receiverBindings.add(member.getReceiverId() + "->" + member.getOwnerType());
                if (member.getObservedResult() != null) {
                    observedResults.add(member.getReceiverId() + "." + member.getSignature()
                            + "=" + member.getObservedResult());
                }
            }
        }
        int maxChars = Properties.LLM_POSTPROCESSING_MAX_OBSERVATION_CHARS;
        StringBuilder builder = new StringBuilder();
        if (!receiverBindings.isEmpty()) {
            appendCapped(builder, "receivers: " + String.join(", ", receiverBindings) + "\n", maxChars);
        }
        int truncatedTypes = 0;
        for (Map.Entry<String, LinkedHashSet<String>> entry : membersByType.entrySet()) {
            String line = "owner=" + entry.getKey() + " members=" + String.join(", ", entry.getValue()) + "\n";
            if (maxChars > 0 && builder.length() + line.length() > maxChars) {
                truncatedTypes++;
                continue;
            }
            builder.append(line);
        }
        if (truncatedTypes > 0) {
            appendCapped(builder, "truncatedCallableTypes=" + truncatedTypes + "\n", maxChars);
        }
        if (!observedResults.isEmpty()) {
            appendCapped(builder, "observed: " + String.join(", ", observedResults) + "\n", maxChars);
        }
        return builder.length() == 0 ? "none\n" : builder.toString();
    }

    private static void appendCapped(StringBuilder builder, String line, int maxChars) {
        if (maxChars <= 0 || builder.length() + line.length() <= maxChars) {
            builder.append(line);
        }
    }

    public String toExceptionText() {
        if (exceptions.isEmpty()) {
            return "none\n";
        }
        StringBuilder builder = new StringBuilder();
        for (ExceptionContext exception : exceptions) {
            builder.append(exception.getStatementId());
            builder.append(" type=").append(exception.getType());
            builder.append(" explicit=").append(exception.isExplicit());
            if (exception.getMessage() != null) {
                builder.append(" message=").append(exception.getMessage());
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    public String toCandidateFactText() {
        if (candidateFacts.isEmpty()) {
            return "none\n";
        }
        StringBuilder builder = new StringBuilder();
        int maxChars = Properties.LLM_POSTPROCESSING_MAX_OBSERVATION_CHARS;
        int emitted = 0;
        for (CandidateFact fact : candidateFacts) {
            String line = candidateFactLine(fact);
            if (maxChars > 0 && builder.length() + line.length() > maxChars) {
                appendCandidateTruncation(builder, candidateFacts.size() - emitted, maxChars);
                break;
            }
            builder.append(line);
            emitted++;
        }
        return builder.toString();
    }

    private static String candidateFactLine(CandidateFact fact) {
        StringBuilder builder = new StringBuilder();
        builder.append(fact.getStatementId());
        if (fact.getSourceId() != null) {
            builder.append(" source=").append(fact.getSourceId());
        }
        if (!fact.getReferencedIds().isEmpty()) {
            builder.append(" refs=").append(String.join(",", fact.getReferencedIds()));
        }
        builder.append(" kind=").append(fact.getKind());
        if (fact.getObservedValue() != null) {
            builder.append(" observed=").append(fact.getObservedValue());
        }
        builder.append('\n');
        return builder.toString();
    }

    private static void appendCandidateTruncation(StringBuilder builder, int truncatedCount, int maxChars) {
        String truncationLine = "truncatedCandidates=" + Math.max(0, truncatedCount) + "\n";
        if (maxChars <= 0 || builder.length() + truncationLine.length() <= maxChars) {
            builder.append(truncationLine);
        }
    }

    public LlmPostProcessingResponseParser.ParseContext toParseContext() {
        Set<LlmPostProcessingResponseParser.CallableMethod> callableMethods = new LinkedHashSet<>();
        for (CallableMember member : callableMembers) {
            callableMethods.add(new LlmPostProcessingResponseParser.CallableMethod(
                    member.getReceiverId(), member.getOwnerType(), methodName(member.getSignature()),
                    methodDescriptor(member.getSignature()), member.getReturnType()));
        }
        Map<String, String> variableTypes = new LinkedHashMap<>();
        for (StatementContext statement : statements) {
            if (statement.getVariableId() != null && statement.getDeclaredType() != null) {
                variableTypes.put(statement.getVariableId(), statement.getDeclaredType());
            }
        }
        Set<String> observedCandidateKeys = new LinkedHashSet<>();
        for (CandidateFact fact : candidateFacts) {
            if (fact.getAssertionKey() != null) {
                observedCandidateKeys.add(fact.getAssertionKey());
            }
        }
        Set<String> setupInputVariableIds = new LinkedHashSet<>();
        for (Observation observation : observations) {
            if ("INPUT".equals(observation.getProvenance()) && observation.getVariableId() != null) {
                setupInputVariableIds.add(observation.getVariableId());
            }
        }
        return LlmPostProcessingResponseParser.context(
                references.getStatementIds(), references.getVariableIds(), callableMethods, observedCandidateKeys,
                setupInputVariableIds, variableTypes);
    }

    private static String declaredType(VariableReference returnValue) {
        if (returnValue == null || returnValue.isVoid()) {
            return null;
        }
        Type type = returnValue.getType();
        return type == null ? null : type.getTypeName();
    }

    private static String runtimeType(VariableReference returnValue, Scope finalScope) {
        Object value = finalScopeValue(returnValue, finalScope);
        return value == null ? null : value.getClass().getTypeName();
    }

    private static Object finalScopeValue(VariableReference returnValue, Scope finalScope) {
        if (returnValue == null || returnValue.isVoid() || finalScope == null) {
            return null;
        }
        return finalScope.getObject(returnValue);
    }

    private static String normalizeCode(String code) {
        if (code == null) {
            return "";
        }
        return code.trim().replace("\r\n", "\n").replace('\r', '\n');
    }

    private static List<ExceptionContext> exceptionContexts(ExecutionResult executionResult) {
        if (executionResult == null || executionResult.noThrownExceptions()) {
            return Collections.emptyList();
        }
        List<ExceptionContext> contexts = new ArrayList<>();
        for (Map.Entry<Integer, Throwable> entry : executionResult.getCopyOfExceptionMapping().entrySet()) {
            Integer position = entry.getKey();
            Throwable throwable = entry.getValue();
            if (position == null || throwable == null) {
                continue;
            }
            contexts.add(new ExceptionContext(
                    LlmPostProcessingReferences.statementId(position),
                    throwable.getClass().getName(),
                    Boolean.TRUE.equals(executionResult.getExplicitExceptions().get(position)),
                    sanitizedMessage(throwable.getMessage())));
        }
        contexts.sort((left, right) -> left.getStatementId().compareTo(right.getStatementId()));
        return contexts;
    }

    private static String sanitizedMessage(String message) {
        if (message == null || message.isEmpty()) {
            return null;
        }
        String sanitized = message.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        int maxChars = Math.max(1, Properties.LLM_POSTPROCESSING_MAX_LITERAL_CHARS);
        if (sanitized.length() > maxChars) {
            sanitized = sanitized.substring(0, maxChars);
        }
        return "\"" + escapeJava(sanitized) + "\"";
    }

    private static List<CandidateFact> candidateFacts(LlmPostProcessingReferences references,
                                                      List<Assertion> assertions) {
        if (assertions == null || assertions.isEmpty()) {
            return Collections.emptyList();
        }
        List<CandidateFact> facts = new ArrayList<>();
        for (Assertion assertion : assertions) {
            if (assertion == null) {
                continue;
            }
            String sourceId = variableId(references, assertion.getSource());
            List<String> referencedIds = referencedIds(references, assertion);
            String statementId = assertionStatementId(assertion);
            if (statementId == null && sourceId == null && referencedIds.isEmpty()) {
                continue;
            }
            facts.add(new CandidateFact(
                    statementId == null ? "s?" : statementId,
                    sourceId,
                    assertion.getClass().getSimpleName(),
                    valueSummaryText(assertion.getValue()),
                    referencedIds,
                    assertionKey(references, assertion)));
        }
        facts.sort((left, right) -> {
            int priority = Integer.compare(candidatePriority(left.getKind()), candidatePriority(right.getKind()));
            if (priority != 0) {
                return priority;
            }
            int statement = left.getStatementId().compareTo(right.getStatementId());
            if (statement != 0) {
                return statement;
            }
            String leftSource = left.getSourceId() == null ? "" : left.getSourceId();
            String rightSource = right.getSourceId() == null ? "" : right.getSourceId();
            int source = leftSource.compareTo(rightSource);
            if (source != 0) {
                return source;
            }
            return left.getKind().compareTo(right.getKind());
        });
        return facts;
    }

    private static String assertionKey(LlmPostProcessingReferences references, Assertion assertion) {
        String sourceId = variableId(references, assertion.getSource());
        if (sourceId == null) {
            return null;
        }
        if (assertion instanceof NullAssertion && assertion.getValue() instanceof Boolean) {
            boolean isNull = (Boolean) assertion.getValue();
            return assertionKey(isNull ? "NULL" : "NOT_NULL", null, sourceId, null);
        }
        if (assertion instanceof EqualsAssertion) {
            EqualsAssertion equals = (EqualsAssertion) assertion;
            String destId = variableId(references, equals.getDest());
            if (destId == null || !(assertion.getValue() instanceof Boolean)) {
                return null;
            }
            boolean equal = (Boolean) assertion.getValue();
            return assertionKey(equal ? "EQUALS" : "NOT_EQUALS", sourceId, destId, null);
        }
        if (assertion instanceof ContainsAssertion) {
            String code = codeHint(references, assertion);
            return code == null ? null : "TRUE||" + normalizeKeyExpression(unquote(code)) + "|";
        }
        if (assertion instanceof ArrayLengthAssertion) {
            Object value = assertion.getValue();
            if (value == null) {
                return null;
            }
            return assertionKey("EQUALS", String.valueOf(value), sourceId + ".length", null);
        }
        Object value = assertion.getValue();
        String expected = valueSummaryText(value);
        if (expected == null) {
            return null;
        }
        return assertionKey("EQUALS", expected, sourceId, null);
    }

    private static String assertionKey(String kind, String expected, String actual, String delta) {
        return kind
                + "|" + normalizeKeyExpression(expected)
                + "|" + normalizeKeyExpression(actual)
                + "|" + normalizeKeyExpression(delta);
    }

    private static String normalizeKeyExpression(String expression) {
        return expression == null ? "" : expression.replaceAll("\\s+", "");
    }

    private static String unquote(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != '"'
                || value.charAt(value.length() - 1) != '"') {
            return value;
        }
        return value.substring(1, value.length() - 1);
    }

    private static int candidatePriority(String kind) {
        if ("InspectorAssertion".equals(kind) || "PrimitiveFieldAssertion".equals(kind)) {
            return 10;
        }
        if ("ContainsAssertion".equals(kind) || "ArrayEqualsAssertion".equals(kind)
                || "ArrayLengthAssertion".equals(kind)) {
            return 20;
        }
        if ("CompareAssertion".equals(kind) || "EqualsAssertion".equals(kind)) {
            return 30;
        }
        if ("PrimitiveAssertion".equals(kind)) {
            return 80;
        }
        if ("NullAssertion".equals(kind) || "SameAssertion".equals(kind)) {
            return 90;
        }
        return 50;
    }

    private static String assertionStatementId(Assertion assertion) {
        if (assertion.getStatement() != null) {
            return LlmPostProcessingReferences.statementId(assertion.getStatement().getPosition());
        }
        VariableReference source = assertion.getSource();
        if (source == null) {
            return null;
        }
        try {
            return LlmPostProcessingReferences.statementId(source.getStPosition());
        } catch (AssertionError e) {
            return null;
        }
    }

    private static List<String> referencedIds(LlmPostProcessingReferences references, Assertion assertion) {
        List<String> ids = new ArrayList<>();
        for (VariableReference variable : assertion.getReferencedVariables()) {
            String id = variableId(references, variable);
            if (id != null && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static String variableId(LlmPostProcessingReferences references, VariableReference variable) {
        if (variable == null) {
            return null;
        }
        int position;
        try {
            position = variable.getStPosition();
        } catch (AssertionError e) {
            return null;
        }
        String variableId = LlmPostProcessingReferences.variableId(position);
        return references.hasVariableId(variableId) ? variableId : null;
    }

    private static String valueSummaryText(Object value) {
        ValueSummary summary = valueSummary(value);
        return summary == null ? null : summary.value;
    }

    private static String codeHint(LlmPostProcessingReferences references, Assertion assertion) {
        String code;
        try {
            code = assertion.getCode();
        } catch (RuntimeException | AssertionError e) {
            return null;
        }
        if (code == null || code.trim().isEmpty()) {
            return null;
        }
        String normalized = normalizeCode(code);
        for (VariableReference variable : assertion.getReferencedVariables()) {
            String id = variableId(references, variable);
            if (id != null && variable != null && variable.getName() != null) {
                normalized = normalized.replace(variable.getName(), id);
            }
        }
        return "\"" + escapeJava(normalized) + "\"";
    }

    private static Observation primitiveInputObservation(String statementId, String variableId,
                                                         Statement statement) {
        if (variableId == null || !(statement instanceof PrimitiveStatement<?>)) {
            return null;
        }
        Object value = ((PrimitiveStatement<?>) statement).getValue();
        String literal = literalValue(value);
        if (literal == null) {
            return null;
        }
        int maxChars = Math.max(1, Properties.LLM_POSTPROCESSING_MAX_LITERAL_CHARS);
        boolean complete = literal.length() <= maxChars;
        if (!complete) {
            literal = literal.substring(0, maxChars);
        }
        return new Observation(statementId, variableId, "INPUT", literal, complete, true);
    }

    private static Observation finalScopeObservation(String statementId, String variableId, Statement statement,
                                                     VariableReference returnValue, Scope finalScope) {
        if (variableId == null || finalScope == null || statement instanceof PrimitiveStatement<?>) {
            return null;
        }
        Object value = finalScopeValue(returnValue, finalScope);
        ValueSummary summary = valueSummary(value);
        if (summary == null) {
            return null;
        }
        return new Observation(statementId, variableId, provenance(statement), summary.value, summary.complete,
                summary.relationalOnly);
    }

    private static String provenance(Statement statement) {
        if (statement instanceof FieldStatement) {
            return "FIELD_OBSERVATION";
        }
        return "SUT_RETURN";
    }

    private static ValueSummary valueSummary(Object value) {
        String literal = literalValue(value);
        if (literal != null) {
            int maxChars = Math.max(1, Properties.LLM_POSTPROCESSING_MAX_LITERAL_CHARS);
            boolean complete = literal.length() <= maxChars;
            return new ValueSummary(complete ? literal : literal.substring(0, maxChars), complete, !complete);
        }
        if (value != null && value.getClass().isArray()) {
            return arraySummary(value);
        }
        if (value instanceof Collection<?>) {
            return collectionSummary((Collection<?>) value);
        }
        if (value instanceof Map<?, ?>) {
            return mapSummary((Map<?, ?>) value);
        }
        return null;
    }

    private static ValueSummary arraySummary(Object array) {
        int length = Array.getLength(array);
        int maxElements = Math.max(0, Properties.LLM_POSTPROCESSING_MAX_COLLECTION_ELEMENTS);
        List<String> elements = new ArrayList<>();
        boolean complete = length <= maxElements;
        for (int i = 0; i < length && i < maxElements; i++) {
            String literal = literalValue(Array.get(array, i));
            if (literal == null) {
                complete = false;
                break;
            }
            elements.add(literal);
        }
        return new ValueSummary("array length=" + length + " elements=[" + String.join(", ", elements) + "]",
                complete, !complete);
    }

    private static ValueSummary collectionSummary(Collection<?> collection) {
        int maxElements = Math.max(0, Properties.LLM_POSTPROCESSING_MAX_COLLECTION_ELEMENTS);
        List<String> elements = new ArrayList<>();
        int size;
        java.util.Iterator<?> iterator;
        try {
            size = collection.size();
            iterator = collection.iterator();
        } catch (RuntimeException e) {
            return new ValueSummary("collection elements unavailable", false, true);
        }
        if (iterator == null) {
            return new ValueSummary("collection size=" + size + " elements unavailable", false, true);
        }

        boolean complete = size <= maxElements;
        int index = 0;
        while (iterator.hasNext()) {
            Object element = iterator.next();
            if (index++ >= maxElements) {
                break;
            }
            String literal = literalValue(element);
            if (literal == null) {
                complete = false;
                break;
            }
            elements.add(literal);
        }
        return new ValueSummary("collection size=" + size + " elements=["
                + String.join(", ", elements) + "]", complete, !complete);
    }

    private static ValueSummary mapSummary(Map<?, ?> map) {
        int maxElements = Math.max(0, Properties.LLM_POSTPROCESSING_MAX_COLLECTION_ELEMENTS);
        List<String> entries = new ArrayList<>();
        int size;
        Set<? extends Map.Entry<?, ?>> entrySet;
        try {
            size = map.size();
            entrySet = map.entrySet();
        } catch (RuntimeException e) {
            return new ValueSummary("map entries unavailable", false, true);
        }
        if (entrySet == null) {
            return new ValueSummary("map size=" + size + " entries unavailable", false, true);
        }

        boolean complete = size <= maxElements;
        int index = 0;
        for (Map.Entry<?, ?> entry : entrySet) {
            if (index++ >= maxElements) {
                break;
            }
            if (entry == null) {
                complete = false;
                break;
            }
            String key = literalValue(entry.getKey());
            String value = literalValue(entry.getValue());
            if (key == null || value == null) {
                complete = false;
                break;
            }
            entries.add(key + "->" + value);
        }
        return new ValueSummary("map size=" + size + " entries=[" + String.join(", ", entries) + "]",
                complete, !complete);
    }

    private static List<CallableMember> callableMembers(String variableId, VariableReference returnValue,
                                                        Object runtimeValue, Set<String> advertisedTypes) {
        if (variableId == null || returnValue == null || returnValue.isVoid() || returnValue.isPrimitive()) {
            return Collections.emptyList();
        }
        String typeName = declaredType(returnValue);
        if (typeName == null) {
            return Collections.emptyList();
        }
        List<CallableMember> members = new ArrayList<>();
        addCuratedCallableMembers(members, variableId, typeName, runtimeValue);
        if ("java.lang.Boolean".equals(typeName) || "Boolean".equals(typeName)) {
            add(members, variableId, "java.lang.Boolean", "booleanValue()Z", "boolean");
        } else if ("java.lang.Byte".equals(typeName) || "Byte".equals(typeName)) {
            add(members, variableId, "java.lang.Byte", "byteValue()B", "byte");
            add(members, variableId, "java.lang.Byte", "intValue()I", "int");
        } else if ("java.lang.Short".equals(typeName) || "Short".equals(typeName)) {
            add(members, variableId, "java.lang.Short", "shortValue()S", "short");
            add(members, variableId, "java.lang.Short", "intValue()I", "int");
        } else if ("java.lang.Integer".equals(typeName) || "Integer".equals(typeName)) {
            add(members, variableId, "java.lang.Integer", "intValue()I", "int");
            add(members, variableId, "java.lang.Integer", "longValue()J", "long");
        } else if ("java.lang.Long".equals(typeName) || "Long".equals(typeName)) {
            add(members, variableId, "java.lang.Long", "longValue()J", "long");
            add(members, variableId, "java.lang.Long", "intValue()I", "int");
        } else if ("java.lang.Float".equals(typeName) || "Float".equals(typeName)) {
            add(members, variableId, "java.lang.Float", "floatValue()F", "float");
            add(members, variableId, "java.lang.Float", "doubleValue()D", "double");
        } else if ("java.lang.Double".equals(typeName) || "Double".equals(typeName)) {
            add(members, variableId, "java.lang.Double", "doubleValue()D", "double");
            add(members, variableId, "java.lang.Double", "floatValue()F", "float");
        } else if ("java.lang.Character".equals(typeName) || "Character".equals(typeName)) {
            add(members, variableId, "java.lang.Character", "charValue()C", "char");
        } else if ("java.math.BigInteger".equals(typeName) || "BigInteger".equals(typeName)) {
            add(members, variableId, "java.math.BigInteger", "abs()Ljava/math/BigInteger;",
                    "java.math.BigInteger");
            add(members, variableId, "java.math.BigInteger", "signum()I", "int",
                    runtimeValue instanceof java.math.BigInteger
                            ? String.valueOf(((java.math.BigInteger) runtimeValue).signum())
                            : null);
            add(members, variableId, "java.math.BigInteger", "compareTo(Ljava/math/BigInteger;)I", "int");
        } else if ("java.math.BigDecimal".equals(typeName) || "BigDecimal".equals(typeName)) {
            add(members, variableId, "java.math.BigDecimal", "abs()Ljava/math/BigDecimal;",
                    "java.math.BigDecimal");
            add(members, variableId, "java.math.BigDecimal", "signum()I", "int",
                    runtimeValue instanceof java.math.BigDecimal
                            ? String.valueOf(((java.math.BigDecimal) runtimeValue).signum())
                            : null);
            add(members, variableId, "java.math.BigDecimal", "compareTo(Ljava/math/BigDecimal;)I", "int");
        }
        if (Properties.LLM_POSTPROCESSING_CALLABLE_POLICY == Properties.LlmPostProcessingCallablePolicy.INSPECTORS_ONLY
                || Properties.LLM_POSTPROCESSING_CALLABLE_POLICY == Properties.LlmPostProcessingCallablePolicy.PURE_BOUNDED) {
            addInspectorMembers(members, variableId, runtimeValue);
        }
        if (Properties.LLM_POSTPROCESSING_CALLABLE_POLICY == Properties.LlmPostProcessingCallablePolicy.PURE_BOUNDED
                && runtimeValue != null) {
            addPureBoundedMembers(members, variableId, runtimeValue.getClass(), advertisedTypes, 0);
        }
        return deduplicateCallableMembers(members);
    }

    private static void addCuratedCallableMembers(List<CallableMember> members, String receiverId,
                                                  String typeName, Object runtimeValue) {
        Class<?> type = classForTypeName(typeName, runtimeValue);
        String owner = type == null ? typeName : type.getTypeName();
        if (isStringType(typeName, type)) {
            String string = runtimeValue instanceof String ? (String) runtimeValue : null;
            add(members, receiverId, owner, "length()I", "int",
                    string == null ? null : String.valueOf(string.length()));
            add(members, receiverId, owner, "isEmpty()Z", "boolean",
                    string == null ? null : String.valueOf(string.isEmpty()));
            add(members, receiverId, owner, "startsWith(Ljava/lang/String;)Z", "boolean");
            add(members, receiverId, owner, "endsWith(Ljava/lang/String;)Z", "boolean");
            add(members, receiverId, owner, "contains(Ljava/lang/CharSequence;)Z", "boolean");
        }
        if (type != null && Collection.class.isAssignableFrom(type)) {
            Collection<?> collection = runtimeValue instanceof Collection<?> ? (Collection<?>) runtimeValue : null;
            add(members, receiverId, owner, "size()I", "int",
                    collection == null ? null : safeCollectionSize(collection));
            add(members, receiverId, owner, "isEmpty()Z", "boolean",
                    collection == null ? null : safeCollectionIsEmpty(collection));
            add(members, receiverId, owner, "contains(Ljava/lang/Object;)Z", "boolean");
            if (List.class.isAssignableFrom(type)) {
                // Element access with an observed constant index (see plan 6.3). The
                // return type is the observed homogeneous element type so numeric or
                // string element equality passes the operand type check; otherwise
                // it degrades to Object (still usable for null/identity assertions).
                String elementType = commonElementTypeName(collection);
                add(members, receiverId, owner, "get(I)Ljava/lang/Object;",
                        elementType == null ? "java.lang.Object" : elementType);
            }
        } else if (type != null && Map.class.isAssignableFrom(type)) {
            Map<?, ?> map = runtimeValue instanceof Map<?, ?> ? (Map<?, ?>) runtimeValue : null;
            add(members, receiverId, owner, "size()I", "int", map == null ? null : safeMapSize(map));
            add(members, receiverId, owner, "isEmpty()Z", "boolean", map == null ? null : safeMapIsEmpty(map));
            add(members, receiverId, owner, "containsKey(Ljava/lang/Object;)Z", "boolean");
        }
    }

    private static String safeCollectionSize(Collection<?> collection) {
        try {
            return String.valueOf(collection.size());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String safeCollectionIsEmpty(Collection<?> collection) {
        try {
            return String.valueOf(collection.isEmpty());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String safeMapSize(Map<?, ?> map) {
        try {
            return String.valueOf(map.size());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String safeMapIsEmpty(Map<?, ?> map) {
        try {
            return String.valueOf(map.isEmpty());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Class<?> classForTypeName(String typeName, Object runtimeValue) {
        if (runtimeValue != null) {
            return runtimeValue.getClass();
        }
        if (typeName == null || typeName.trim().isEmpty()) {
            return null;
        }
        try {
            return Class.forName(typeName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isStringType(String typeName, Class<?> type) {
        return String.class.equals(type) || "java.lang.String".equals(typeName) || "String".equals(typeName);
    }

    /**
     * Return the shared runtime element type of a collection when its sampled
     * elements are homogeneous, otherwise {@code null}. Used to give {@code get}
     * a concrete return type instead of the erased {@code Object}.
     */
    private static String commonElementTypeName(Collection<?> collection) {
        if (collection == null) {
            return null;
        }
        java.util.Iterator<?> iterator;
        try {
            if (collection.isEmpty()) {
                return null;
            }
            iterator = collection.iterator();
        } catch (RuntimeException e) {
            return null;
        }
        if (iterator == null) {
            return null;
        }
        int maxElements = Math.max(1, Properties.LLM_POSTPROCESSING_MAX_COLLECTION_ELEMENTS);
        Class<?> common = null;
        int seen = 0;
        while (iterator.hasNext()) {
            Object element = iterator.next();
            if (element == null) {
                continue;
            }
            Class<?> elementClass = element.getClass();
            if (common == null) {
                common = elementClass;
            } else if (!common.equals(elementClass)) {
                return null;
            }
            if (++seen >= maxElements) {
                break;
            }
        }
        return common == null ? null : common.getTypeName();
    }

    private static void addPureBoundedMembers(List<CallableMember> members, String receiverId, Class<?> receiverType,
                                              Set<String> advertisedTypes, int depth) {
        if (receiverType == null || !TestUsageChecker.canUse(receiverType)) {
            return;
        }
        int limit = Math.max(0, Properties.LLM_POSTPROCESSING_MAX_CALLABLE_MEMBERS_PER_TYPE);
        int accepted = 0;
        List<Method> methods = new ArrayList<>(Arrays.asList(receiverType.getMethods()));
        // Rank the most assertable members first so the per-type cap keeps them:
        // zero-arg inspectors returning primitive/String/enum values with
        // getter-style names, rather than an alphabetical prefix.
        methods.sort(Comparator.comparingInt(LlmPostProcessingPromptContext::pureBoundedRank)
                .thenComparing(Method::getName)
                .thenComparing(Method::toGenericString));
        for (Method method : methods) {
            if (accepted >= limit) {
                break;
            }
            if (!isPureBoundedCallable(method)) {
                continue;
            }
            add(members, receiverId, receiverType.getTypeName(),
                    method.getName() + org.objectweb.asm.Type.getMethodDescriptor(method),
                    method.getReturnType().getTypeName());
            accepted++;
            addCallableTypeMembersForReturnType(members, method.getReturnType(), advertisedTypes, depth + 1);
        }
    }

    private static void addCallableTypeMembersForReturnType(List<CallableMember> members, Class<?> returnType,
                                                            Set<String> advertisedTypes, int depth) {
        if (!Properties.LLM_POSTPROCESSING_ALLOW_CHAINED_CALLS || returnType == null || returnType.equals(Void.TYPE)) {
            return;
        }
        if (depth > Math.max(0, Properties.LLM_POSTPROCESSING_MAX_CHAIN_DEPTH)) {
            return;
        }
        int maxTypes = Math.max(0, Properties.LLM_POSTPROCESSING_MAX_CALLABLE_TYPES_PER_TEST);
        if (maxTypes > 0 && advertisedTypes.size() >= maxTypes) {
            return;
        }
        String typeName = returnType.getTypeName();
        if (!advertisedTypes.add(typeName)) {
            return;
        }
        addCuratedCallableMembers(members, null, typeName, null);
        if (Properties.LLM_POSTPROCESSING_CALLABLE_POLICY == Properties.LlmPostProcessingCallablePolicy.PURE_BOUNDED) {
            addPureBoundedMembers(members, null, returnType, advertisedTypes, depth);
        }
    }

    /**
     * Lower rank = more assertable, used to order pure-bounded members before the
     * per-type cap trims the list. Prefers zero-argument inspectors returning
     * directly-assertable values with conventional accessor names.
     */
    private static int pureBoundedRank(Method method) {
        int rank = 0;
        if (method.getParameterTypes().length > 0) {
            rank += 4;
        }
        Class<?> returnType = method.getReturnType();
        if (!returnType.isPrimitive() && !returnType.equals(String.class) && !returnType.isEnum()
                && !isPrimitiveWrapper(returnType)) {
            rank += 3;
        }
        String name = method.getName();
        boolean accessorName = name.startsWith("get") || name.startsWith("is") || name.startsWith("has")
                || "size".equals(name) || "length".equals(name);
        if (!accessorName) {
            rank += 2;
        }
        return rank;
    }

    private static boolean isPureBoundedCallable(Method method) {
        if (method == null || !Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())
                || method.isBridge() || method.isSynthetic() || method.getReturnType().equals(Void.TYPE)) {
            return false;
        }
        if (method.getDeclaringClass().equals(Object.class) || method.getDeclaringClass().equals(Enum.class)
                || DENIED_INSPECTOR_METHODS.contains(method.getName())) {
            return false;
        }
        if (method.getParameterTypes().length > Math.max(0, Properties.LLM_POSTPROCESSING_MAX_CALLABLE_ARGS)) {
            return false;
        }
        if (!TestUsageChecker.canUse(method)) {
            return false;
        }
        return CheapPurityAnalyzer.getInstance().isPure(method);
    }

    private static void addInspectorMembers(List<CallableMember> members, String variableId, Object runtimeValue) {
        if (runtimeValue == null) {
            return;
        }
        Class<?> runtimeClass = runtimeValue.getClass();
        if (runtimeClass.isArray()) {
            return;
        }
        for (Inspector inspector : InspectorManager.getInstance().getInspectors(runtimeClass)) {
            Method method = inspector.getMethod();
            if (!isAllowedInspectorMethod(method)) {
                continue;
            }
            String observedResult = observedInspectorResult(inspector, runtimeValue);
            add(members, variableId, method.getDeclaringClass().getName(),
                    method.getName() + org.objectweb.asm.Type.getMethodDescriptor(method),
                    method.getReturnType().getTypeName(), observedResult);
        }
    }

    private static boolean isAllowedInspectorMethod(Method method) {
        if (method == null
                || !Modifier.isPublic(method.getModifiers())
                || Modifier.isStatic(method.getModifiers())
                || method.getParameterTypes().length != 0
                || method.getReturnType().equals(Void.TYPE)
                || DENIED_INSPECTOR_METHODS.contains(method.getName())) {
            return false;
        }
        Class<?> returnType = method.getReturnType();
        if (!returnType.isPrimitive()
                && !returnType.equals(String.class)
                && !returnType.isEnum()
                && !isPrimitiveWrapper(returnType)) {
            return false;
        }
        String ownerName = method.getDeclaringClass().getName();
        if (ownerName.startsWith("java.") || ownerName.startsWith("javax.")) {
            return true;
        }
        return CheapPurityAnalyzer.getInstance().isPure(method);
    }

    private static boolean isPrimitiveWrapper(Class<?> type) {
        return type.equals(Boolean.class)
                || type.equals(Byte.class)
                || type.equals(Short.class)
                || type.equals(Character.class)
                || type.equals(Integer.class)
                || type.equals(Long.class)
                || type.equals(Float.class)
                || type.equals(Double.class);
    }

    private static String observedInspectorResult(Inspector inspector, Object runtimeValue) {
        try {
            return literalValue(inspector.getValue(runtimeValue));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<CallableMember> deduplicateCallableMembers(List<CallableMember> members) {
        List<CallableMember> deduplicated = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (CallableMember member : members) {
            String key = member.getReceiverId() + "\n" + member.getOwnerType() + "\n" + member.getSignature();
            if (seen.add(key)) {
                deduplicated.add(member);
            }
        }
        return deduplicated;
    }

    private static void add(List<CallableMember> members, String receiverId, String ownerType,
                            String signature, String returnType) {
        members.add(new CallableMember(receiverId, ownerType, signature, returnType, null));
    }

    private static void add(List<CallableMember> members, String receiverId, String ownerType,
                            String signature, String returnType, String observedResult) {
        members.add(new CallableMember(receiverId, ownerType, signature, returnType, observedResult));
    }

    private static String methodName(String signature) {
        int parameterStart = signature.indexOf('(');
        return parameterStart < 0 ? signature : signature.substring(0, parameterStart);
    }

    private static String methodDescriptor(String signature) {
        int parameterStart = signature.indexOf('(');
        return parameterStart < 0 ? null : signature.substring(parameterStart);
    }

    private static String literalValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "\"" + escapeJava((String) value) + "\"";
        }
        if (value instanceof Character) {
            return "'" + escapeJava(String.valueOf(value)) + "'";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Enum<?>) {
            return enumConstantExpression((Enum<?>) value);
        }
        return null;
    }

    /**
     * Render an enum constant as a fully-qualified, import-free Java expression
     * (for example {@code com.example.Status.ACTIVE}) so it can appear both as an
     * observed value and as an assertion operand. Uses the declaring class so
     * enums with constant-specific bodies still resolve to the base enum type.
     */
    private static String enumConstantExpression(Enum<?> value) {
        Class<?> declaringClass = value.getDeclaringClass();
        String typeName = declaringClass.getCanonicalName();
        if (typeName == null) {
            typeName = declaringClass.getName().replace('$', '.');
        }
        return typeName + "." + value.name();
    }

    private static String escapeJava(String value) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    builder.append("\\\\");
                    break;
                case '"':
                    builder.append("\\\"");
                    break;
                case '\'':
                    builder.append("\\'");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (c < 32 || c == 127) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                    break;
            }
        }
        return builder.toString();
    }

    public static final class StatementContext {
        private final String statementId;
        private final String variableId;
        private final String declaredType;
        private final String runtimeType;
        private final String code;

        private StatementContext(String statementId, String variableId, String declaredType, String runtimeType,
                                 String code) {
            this.statementId = statementId;
            this.variableId = variableId;
            this.declaredType = declaredType;
            this.runtimeType = runtimeType;
            this.code = code;
        }

        public String getStatementId() {
            return statementId;
        }

        public String getVariableId() {
            return variableId;
        }

        public String getDeclaredType() {
            return declaredType;
        }

        public String getRuntimeType() {
            return runtimeType;
        }

        public String getCode() {
            return code;
        }
    }

    private static final class ValueSummary {
        private final String value;
        private final boolean complete;
        private final boolean relationalOnly;

        private ValueSummary(String value, boolean complete, boolean relationalOnly) {
            this.value = value;
            this.complete = complete;
            this.relationalOnly = relationalOnly;
        }
    }

    public static final class Observation {
        private final String statementId;
        private final String variableId;
        private final String provenance;
        private final String value;
        private final boolean complete;
        private final boolean relationalOnly;

        private Observation(String statementId, String variableId, String provenance,
                            String value, boolean complete, boolean relationalOnly) {
            this.statementId = statementId;
            this.variableId = variableId;
            this.provenance = provenance;
            this.value = value;
            this.complete = complete;
            this.relationalOnly = relationalOnly;
        }

        public String getStatementId() {
            return statementId;
        }

        public String getVariableId() {
            return variableId;
        }

        public String getProvenance() {
            return provenance;
        }

        public String getValue() {
            return value;
        }

        public boolean isComplete() {
            return complete;
        }

        public boolean isRelationalOnly() {
            return relationalOnly;
        }
    }

    public static final class CallableMember {
        private final String receiverId;
        private final String ownerType;
        private final String signature;
        private final String returnType;
        private final String observedResult;

        private CallableMember(String receiverId, String ownerType, String signature,
                               String returnType, String observedResult) {
            this.receiverId = receiverId;
            this.ownerType = ownerType;
            this.signature = signature;
            this.returnType = returnType;
            this.observedResult = observedResult;
        }

        public String getReceiverId() {
            return receiverId;
        }

        public String getOwnerType() {
            return ownerType;
        }

        public String getSignature() {
            return signature;
        }

        public String getReturnType() {
            return returnType;
        }

        public String getObservedResult() {
            return observedResult;
        }
    }

    public static final class ExceptionContext {
        private final String statementId;
        private final String type;
        private final boolean explicit;
        private final String message;

        private ExceptionContext(String statementId, String type, boolean explicit, String message) {
            this.statementId = statementId;
            this.type = type;
            this.explicit = explicit;
            this.message = message;
        }

        public String getStatementId() {
            return statementId;
        }

        public String getType() {
            return type;
        }

        public boolean isExplicit() {
            return explicit;
        }

        public String getMessage() {
            return message;
        }
    }

    public static final class CandidateFact {
        private final String statementId;
        private final String sourceId;
        private final String kind;
        private final String observedValue;
        private final List<String> referencedIds;
        private final String assertionKey;

        private CandidateFact(String statementId, String sourceId, String kind, String observedValue,
                              List<String> referencedIds, String assertionKey) {
            this.statementId = statementId;
            this.sourceId = sourceId;
            this.kind = kind;
            this.observedValue = observedValue;
            this.referencedIds = Collections.unmodifiableList(new ArrayList<>(referencedIds));
            this.assertionKey = assertionKey;
        }

        public String getStatementId() {
            return statementId;
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getKind() {
            return kind;
        }

        public String getObservedValue() {
            return observedValue;
        }

        public List<String> getReferencedIds() {
            return referencedIds;
        }

        public String getAssertionKey() {
            return assertionKey;
        }
    }
}
