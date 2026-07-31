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

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
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
import org.evosuite.testcase.statements.ArrayStatement;
import org.evosuite.testcase.statements.FieldStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
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
    private final List<RelationalOpportunity> relationalOpportunities;
    private final PromptVariantCapabilities capabilities;
    private final PostProcessingOptions options;

    private LlmPostProcessingPromptContext(LlmPostProcessingReferences references,
                                           List<StatementContext> statements,
                                           List<Observation> observations,
                                           List<CallableMember> callableMembers,
                                           List<ExceptionContext> exceptions,
                                           List<CandidateFact> candidateFacts,
                                           List<RelationalOpportunity> relationalOpportunities,
                                           PromptVariantCapabilities capabilities,
                                           PostProcessingOptions options) {
        this.references = references;
        this.statements = Collections.unmodifiableList(new ArrayList<>(statements));
        this.observations = Collections.unmodifiableList(new ArrayList<>(observations));
        this.callableMembers = Collections.unmodifiableList(new ArrayList<>(callableMembers));
        this.exceptions = Collections.unmodifiableList(new ArrayList<>(exceptions));
        this.candidateFacts = Collections.unmodifiableList(new ArrayList<>(candidateFacts));
        this.relationalOpportunities = Collections.unmodifiableList(
                new ArrayList<>(relationalOpportunities));
        this.capabilities = capabilities;
        this.options = options;
    }

    public static LlmPostProcessingPromptContext from(TestCase test) {
        return from(test, null);
    }

    public static LlmPostProcessingPromptContext from(TestCase test, ExecutionResult executionResult) {
        return from(test, executionResult, Collections.emptyList());
    }

    public static LlmPostProcessingPromptContext from(TestCase test, ExecutionResult executionResult,
                                                      List<Assertion> candidateAssertions) {
        return from(test, executionResult, null, candidateAssertions);
    }

    public static LlmPostProcessingPromptContext from(TestCase test, ExecutionResult executionResult,
                                                      ExecutionResult stabilityExecutionResult,
                                                      List<Assertion> candidateAssertions) {
        return from(test, executionResult, stabilityExecutionResult, candidateAssertions, null);
    }

    /** Production context capture using the phase's immutable options snapshot. */
    public static LlmPostProcessingPromptContext from(TestCase test, ExecutionResult executionResult,
                                                      ExecutionResult stabilityExecutionResult,
                                                      List<Assertion> candidateAssertions,
                                                      PostProcessingOptions options) {
        PostProcessingOptions effectiveOptions = options == null
                ? PostProcessingOptions.fromProperties() : options;
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(test);
        List<StatementContext> statements = new ArrayList<>();
        List<Observation> observations = new ArrayList<>();
        List<CallableMember> callableMembers = new ArrayList<>();
        Set<String> advertisedTypes = new LinkedHashSet<>();
        List<ExceptionContext> exceptions = exceptionContexts(executionResult, effectiveOptions);
        PromptVariantCapabilities capabilities = PromptVariantCapabilities.forVariant(options == null
                ? Properties.LLM_POSTPROCESSING_PROMPT_VARIANT
                : Properties.LlmPostProcessingPromptVariant.P2_CANDIDATE_SELECTION);
        List<CandidateFact> candidateFacts = candidateFacts(
                references, candidateAssertions, stabilityExecutionResult, capabilities, effectiveOptions);
        candidateFacts = appendExceptionCandidateFacts(
                candidateFacts, exceptions, stabilityExecutionResult, capabilities, effectiveOptions);
        // A candidate fact already tells the model this variable's observed value,
        // so emitting the same value again as an observation is redundant (plan 6.6:
        // observations are only supplemental to the candidate pool).
        Set<String> candidateCoveredVariableIds = new LinkedHashSet<>();
        for (CandidateFact fact : candidateFacts) {
            boolean completeCanonicalFact = isCandidateSelectable(
                    fact, capabilities.hasAssertableTypesOnly(),
                    capabilities.hasStabilityLabels());
            boolean preserveLegacySuppression = !capabilities.hasCanonicalCandidates()
                    && !capabilities.hasCompactObservedCalls()
                    && !capabilities.hasAssertableTypesOnly();
            if (fact.getSourceId() != null && fact.getObservedValue() != null
                    && (preserveLegacySuppression || completeCanonicalFact)) {
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
                String declaredType = declaredType(statement, returnValue);
                statements.add(new StatementContext(
                        statementId,
                        variableId,
                        declaredType,
                        runtimeType(returnValue, finalScope),
                        normalizeCode(statement.getCode()),
                        statementPhase(statement),
                        statementReceiverId(references, statement),
                        statementArgumentIds(references, statement)));
                Observation observation = primitiveInputObservation(
                        statementId, variableId, statement, effectiveOptions);
                if (observation != null) {
                    observations.add(observation);
                }
                Observation finalScopeObservation = finalScopeObservation(
                        statementId, variableId, statement, returnValue, finalScope, effectiveOptions);
                if (finalScopeObservation != null
                        && (variableId == null || !candidateCoveredVariableIds.contains(variableId))) {
                    observations.add(finalScopeObservation);
                }
                Object runtimeValue = finalScopeValue(returnValue, finalScope);
                callableMembers.addAll(callableMembers(
                        variableId, returnValue, declaredType, runtimeValue, advertisedTypes, effectiveOptions));
            }
        }
        if (capabilities.hasActionRoles()) {
            candidateFacts = actionRankedCandidateFacts(candidateFacts, statements);
        }
        List<RelationalOpportunity> relationalOpportunities =
                relationalOpportunities(statements, candidateFacts, callableMembers);
        return new LlmPostProcessingPromptContext(references, statements, observations, callableMembers, exceptions,
                candidateFacts, relationalOpportunities, capabilities, effectiveOptions);
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

    public List<RelationalOpportunity> getRelationalOpportunities() {
        return relationalOpportunities;
    }

    public String toAnnotatedText() {
        return toAnnotatedText(false);
    }

    String toAnnotatedText(boolean includeActionRoles) {
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
            if (includeActionRoles) {
                builder.append(" phase=").append(statement.getPhase());
                if (statement.getReceiverId() != null) {
                    builder.append(" receiver=").append(statement.getReceiverId());
                }
                if (!statement.getArgumentIds().isEmpty()) {
                    builder.append(" args=").append(String.join(",", statement.getArgumentIds()));
                }
                if (statement.getVariableId() != null && "ACTION".equals(statement.getPhase())) {
                    builder.append(" role=RESULT_OF_").append(statement.getStatementId());
                }
            }
            builder.append(" | ").append(statement.getCode());
            if (!statement.getCode().endsWith("\n")) {
                builder.append('\n');
            }
        }
        return builder.toString();
    }

    public String toObservationText() {
        return toObservationText(null);
    }

    String toObservationText(Set<String> relevantVariableIds) {
        if (observations.isEmpty()) {
            return "none\n";
        }
        StringBuilder builder = new StringBuilder();
        int maxChars = options.contextLimits().observationChars();
        for (Observation observation : observations) {
            if (relevantVariableIds != null
                    && (observation.getVariableId() == null
                    || !relevantVariableIds.contains(observation.getVariableId()))) {
                continue;
            }
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
        return builder.length() == 0 ? "none\n" : builder.toString();
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
        return toCallableMemberText(null);
    }

    public String toObservedSafeExpressionText() {
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        int maxChars = options.contextLimits().observedExpressionChars();
        StringBuilder builder = new StringBuilder();
        for (CallableMember member : callableMembers) {
            if (member.getReceiverId() == null || member.getObservedResult() == null) {
                continue;
            }
            String expression = member.getReceiverId() + "." + readableSignature(member.getSignature());
            String line = expression + " -> " + jsonString(member.getObservedResult()) + "\n";
            if (lines.add(line) && (maxChars <= 0 || builder.length() + line.length() <= maxChars)) {
                builder.append(line);
            }
        }
        for (CandidateFact fact : candidateFacts) {
            LlmPostProcessingResponseParser.SelectableCandidate candidate = fact.getSelectableCandidate();
            if (candidate == null || candidate.getActual() == null || fact.getObservedValue() == null) {
                continue;
            }
            String line = candidate.getActual() + " -> " + jsonString(fact.getObservedValue()) + "\n";
            if (lines.add(line) && (maxChars <= 0 || builder.length() + line.length() <= maxChars)) {
                builder.append(line);
            }
        }
        return builder.length() == 0 ? "none\n" : builder.toString();
    }

    public String toAdditionalLegalCallText() {
        String text = toCallableMemberText();
        int total = callableMembers.size();
        int rendered = 0;
        for (String line : text.split("\\n")) {
            if (line.startsWith("owner=")) {
                int members = line.indexOf(" members=");
                if (members >= 0) {
                    String values = line.substring(members + " members=".length());
                    rendered += values.isEmpty() ? 0 : values.split(", ").length;
                }
            }
        }
        int dropped = Math.max(0, total - rendered);
        if (dropped == 0) {
            return text;
        }
        int maxChars = options.contextLimits().callableChars();
        StringBuilder builder = new StringBuilder(text);
        appendCapped(builder, "droppedMethods=" + dropped + "\n", maxChars);
        return builder.toString();
    }

    private static String readableSignature(String signature) {
        String name = methodName(signature);
        return name + "()";
    }

    String toCallableMemberText(Set<String> relevantVariableIds) {
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
            if (relevantVariableIds != null
                    && (member.getReceiverId() == null
                    || !relevantVariableIds.contains(member.getReceiverId()))) {
                continue;
            }
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
        int maxChars = options.contextLimits().callableChars();
        StringBuilder builder = new StringBuilder();
        if (!receiverBindings.isEmpty()) {
            appendCapped(builder, "receivers: " + String.join(", ", receiverBindings) + "\n", maxChars);
        }
        if (!observedResults.isEmpty()) {
            appendCapped(builder, "observed: " + String.join(", ", observedResults) + "\n", maxChars);
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

    public String toSafeAssertionSiteText() {
        if (exceptions.isEmpty()) {
            return "- END_OF_TEST available=" + String.join(",", references.getVariableIds()) + "\n";
        }
        ExceptionContext exception = exceptions.get(0);
        int throwingPosition = stableIdIndex(exception.getStatementId());
        List<String> completedStatements = new ArrayList<>();
        List<String> availableVariables = new ArrayList<>();
        for (int position = 0; position < throwingPosition; position++) {
            String statementId = LlmPostProcessingReferences.statementId(position);
            if (references.hasStatementId(statementId)) {
                completedStatements.add(statementId);
            }
            String variableId = LlmPostProcessingReferences.variableId(position);
            if (references.hasVariableId(variableId)) {
                availableVariables.add(variableId);
            }
        }
        String available = String.join(",", availableVariables);
        StringBuilder builder = new StringBuilder();
        if (!completedStatements.isEmpty()) {
            builder.append("- BEFORE_TRY after=")
                    .append(String.join(",", completedStatements))
                    .append(" available=").append(available).append('\n');
        }
        builder.append("- IN_CATCH exception=e0 available=e0");
        if (!available.isEmpty()) {
            builder.append(',').append(available);
        }
        builder.append('\n');
        builder.append("- AFTER_CATCH available=").append(available).append('\n');
        return builder.toString();
    }

    public String toCandidateFactText() {
        return toCandidateFactText(true);
    }

    String toCandidateFactText(boolean includeCandidateIds) {
        return toCandidateFactText(includeCandidateIds, false, false, false, false);
    }

    String toCandidateFactText(boolean includeCandidateIds, boolean canonicalSemantics,
                               boolean includeActionRoles, boolean includeStability,
                               boolean assertableTypesOnly) {
        if (candidateFacts.isEmpty()) {
            return "none\n";
        }
        StringBuilder builder = new StringBuilder();
        int maxChars = options.contextLimits().candidateChars();
        int emitted = 0;
        int unstableOmitted = 0;
        if (includeStability) {
            for (CandidateFact fact : candidateFacts) {
                if ("UNSTABLE".equals(fact.getStability())) {
                    unstableOmitted++;
                }
            }
        }
        int displayableFacts = candidateFacts.size() - unstableOmitted;
        for (CandidateFact fact : candidateFacts) {
            if (includeStability && "UNSTABLE".equals(fact.getStability())) {
                continue;
            }
            String line = candidateFactLine(fact, includeCandidateIds, canonicalSemantics,
                    includeActionRoles, includeStability, assertableTypesOnly);
            if (maxChars > 0 && builder.length() + line.length() > maxChars) {
                appendCandidateTruncation(builder, displayableFacts - emitted, maxChars);
                break;
            }
            builder.append(line);
            emitted++;
        }
        if (unstableOmitted > 0) {
            appendCapped(builder, "unstableCandidatesOmitted=" + unstableOmitted + "\n", maxChars);
        }
        return builder.toString();
    }

    private static String candidateFactLine(CandidateFact fact, boolean includeCandidateIds,
                                            boolean canonicalSemantics, boolean includeActionRoles,
                                            boolean includeStability, boolean assertableTypesOnly) {
        StringBuilder builder = new StringBuilder();
        boolean selectable = isCandidateSelectable(
                fact, assertableTypesOnly, includeStability);
        if (includeCandidateIds && selectable) {
            builder.append("candidateId=").append(fact.getCandidateId()).append(' ');
        }
        builder.append(fact.getStatementId());
        if (fact.getSourceId() != null) {
            builder.append(" source=").append(fact.getSourceId());
        }
        if (!fact.getReferencedIds().isEmpty()) {
            builder.append(" refs=").append(String.join(",", fact.getReferencedIds()));
        }
        builder.append(" kind=").append(fact.getKind());
        if (canonicalSemantics && fact.getSelectableCandidate() != null) {
            LlmPostProcessingResponseParser.SelectableCandidate candidate = fact.getSelectableCandidate();
            builder.append(" canonicalKind=").append(candidate.getKind());
            appendJsonExpression(builder, "expected", candidate.getExpected());
            appendJsonExpression(builder, "actual", candidate.getActual());
            appendJsonExpression(builder, "delta", candidate.getDelta());
        }
        if (fact.getObservedValue() != null) {
            builder.append(" observed=").append(canonicalSemantics
                    ? jsonString(fact.getObservedValue()) : fact.getObservedValue());
        }
        if (includeActionRoles) {
            builder.append(" phase=").append(fact.getPhase());
            builder.append(" rank=").append(fact.getRank());
        }
        if (includeStability) {
            builder.append(" stability=").append(fact.getStability());
            if (fact.getStabilityReason() != null) {
                builder.append(" stabilityReason=").append(fact.getStabilityReason());
            }
        }
        if (canonicalSemantics || assertableTypesOnly) {
            builder.append(" selectable=").append(selectable);
        }
        if (includeCandidateIds && canonicalSemantics && selectable) {
            builder.append(" select={\"assertionId\":\"aN\",\"candidateId\":\"")
                    .append(fact.getCandidateId()).append("\"}");
        }
        if (assertableTypesOnly) {
            builder.append(" assertable=").append(fact.isAssertable());
            if (!fact.isAssertable()) {
                builder.append(" reason=INACCESSIBLE_TYPE");
            }
        }
        builder.append('\n');
        return builder.toString();
    }

    private static boolean isCandidateSelectable(CandidateFact fact,
                                                 boolean assertableTypesOnly,
                                                 boolean stabilityLabels) {
        return fact != null
                && fact.getCandidateId() != null
                && fact.getSelectableCandidate() != null
                && (!assertableTypesOnly || fact.isAssertable())
                && (!stabilityLabels || !"UNSTABLE".equals(fact.getStability()));
    }

    private static void appendJsonExpression(StringBuilder builder, String name, String expression) {
        if (expression != null) {
            builder.append(' ').append(name).append('=').append(jsonString(expression));
        }
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + escapeJava(value) + "\"";
    }

    public String toRelationalOpportunityText() {
        if (relationalOpportunities.isEmpty()) {
            return "none\n";
        }
        StringBuilder builder = new StringBuilder();
        int maxCount = options.contextLimits().relationalOpportunities();
        int maxChars = options.contextLimits().relationalChars();
        int emitted = 0;
        for (RelationalOpportunity opportunity : relationalOpportunities) {
            if (maxCount > 0 && emitted >= maxCount) {
                break;
            }
            String line = opportunity.getId()
                    + " left=" + jsonString(opportunity.getLeft())
                    + " right=" + jsonString(opportunity.getRight())
                    + " type=" + opportunity.getType()
                    + " relation=" + opportunity.getRelation() + "\n";
            if (maxChars > 0 && builder.length() + line.length() > maxChars) {
                break;
            }
            builder.append(line);
            emitted++;
        }
        int dropped = relationalOpportunities.size() - emitted;
        if (dropped > 0) {
            appendCapped(builder, "droppedRelationalOpportunities=" + dropped + "\n", maxChars);
        }
        return builder.length() == 0 ? "none\n" : builder.toString();
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
        Set<String> expressionVariableIds = new LinkedHashSet<>(references.getVariableIds());
        String throwingStatementId = exceptions.isEmpty() ? null : exceptions.get(0).getStatementId();
        if (throwingStatementId != null) {
            expressionVariableIds.add("e0");
            variableTypes.put("e0", "java.lang.Throwable");
            callableMethods.add(new LlmPostProcessingResponseParser.CallableMethod(
                    "e0", "java.lang.Throwable", "getMessage", "()Ljava/lang/String;",
                    "java.lang.String"));
            callableMethods.add(new LlmPostProcessingResponseParser.CallableMethod(
                    "e0", "java.lang.Throwable", "getCause", "()Ljava/lang/Throwable;",
                    "java.lang.Throwable"));
        }
        Set<String> observedCandidateKeys = new LinkedHashSet<>();
        Map<String, LlmPostProcessingResponseParser.SelectableCandidate> selectableCandidates =
                new LinkedHashMap<>();
        PromptVariantCapabilities capabilities = this.capabilities;
        for (CandidateFact fact : candidateFacts) {
            if (fact.getAssertionKey() != null) {
                observedCandidateKeys.add(fact.getAssertionKey());
            }
            if (isCandidateSelectable(fact, capabilities.hasAssertableTypesOnly(),
                    capabilities.hasStabilityLabels())) {
                selectableCandidates.put(fact.getCandidateId(),
                        candidateWithDefaultPlacement(fact, throwingStatementId));
            }
        }
        Set<String> setupInputVariableIds = new LinkedHashSet<>();
        for (Observation observation : observations) {
            if ("INPUT".equals(observation.getProvenance()) && observation.getVariableId() != null) {
                setupInputVariableIds.add(observation.getVariableId());
            }
        }
        return LlmPostProcessingResponseParser.context(
                references.getStatementIds(), expressionVariableIds, callableMethods, observedCandidateKeys,
                setupInputVariableIds, variableTypes, selectableCandidates, throwingStatementId)
                .withOptions(options);
    }

    private static LlmPostProcessingResponseParser.SelectableCandidate candidateWithDefaultPlacement(
            CandidateFact fact, String throwingStatementId) {
        LlmPostProcessingResponseParser.SelectableCandidate candidate = fact.getSelectableCandidate();
        if (candidate == null || throwingStatementId == null) {
            return candidate;
        }
        if (fact.getReferencedIds().contains("e0") || "IN_CATCH".equals(fact.getPhase())) {
            return candidate.withDefaultPlacement(
                    LlmPostProcessingResponse.AssertionSite.IN_CATCH, null, "e0");
        }
        int throwingPosition = stableIdIndex(throwingStatementId);
        int latestReferencedPosition = -1;
        for (String referencedId : fact.getReferencedIds()) {
            if (referencedId != null && referencedId.startsWith("v")) {
                latestReferencedPosition = Math.max(latestReferencedPosition,
                        stableIdIndex(referencedId));
            }
        }
        if (latestReferencedPosition < 0 && fact.getStatementId() != null) {
            latestReferencedPosition = stableIdIndex(fact.getStatementId());
        }
        if (latestReferencedPosition >= 0 && latestReferencedPosition < throwingPosition) {
            return candidate.withDefaultPlacement(
                    LlmPostProcessingResponse.AssertionSite.BEFORE_TRY,
                    LlmPostProcessingReferences.statementId(latestReferencedPosition), null);
        }
        return candidate;
    }

    private static String declaredType(Statement statement, VariableReference returnValue) {
        if (returnValue == null || returnValue.isVoid()) {
            return null;
        }
        Type type = null;
        if (statement instanceof MethodStatement && ((MethodStatement) statement).getMethod() != null) {
            type = ((MethodStatement) statement).getMethod().getReturnType();
        } else if (statement instanceof ConstructorStatement
                && ((ConstructorStatement) statement).getConstructor() != null) {
            type = ((ConstructorStatement) statement).getConstructor().getReturnType();
        } else if (statement instanceof FieldStatement && ((FieldStatement) statement).getField() != null) {
            type = ((FieldStatement) statement).getField().getFieldType();
        }
        if (type == null) {
            type = returnValue.getType();
        }
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

    private static List<ExceptionContext> exceptionContexts(ExecutionResult executionResult,
                                                            PostProcessingOptions options) {
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
                    sanitizedMessage(throwable.getMessage(), options),
                    isCompleteExceptionMessage(throwable.getMessage(), options),
                    throwable.getCause() == null));
        }
        contexts.sort((left, right) -> left.getStatementId().compareTo(right.getStatementId()));
        return contexts;
    }

    private static String sanitizedMessage(String message, PostProcessingOptions options) {
        if (message == null || message.isEmpty()) {
            return null;
        }
        String sanitized = message.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        int maxChars = Math.max(1, options.assertionPolicy().maxLiteralChars());
        if (sanitized.length() > maxChars) {
            sanitized = sanitized.substring(0, maxChars);
        }
        return "\"" + escapeJava(sanitized) + "\"";
    }

    private static boolean isCompleteExceptionMessage(String message, PostProcessingOptions options) {
        if (message == null || message.isEmpty()) {
            return true;
        }
        String sanitized = message.replace('\r', ' ').replace('\n', ' ')
                .replace('\t', ' ').trim();
        return sanitized.length() <= Math.max(1, options.assertionPolicy().maxLiteralChars());
    }

    private static List<CandidateFact> candidateFacts(LlmPostProcessingReferences references,
                                                      List<Assertion> assertions,
                                                      ExecutionResult stabilityExecutionResult,
                                                      PromptVariantCapabilities capabilities,
                                                      PostProcessingOptions options) {
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
            LlmPostProcessingResponseParser.SelectableCandidate selectable =
                    selectableCandidate(references, assertion);
            boolean assertable = candidateIsAssertable(references, assertion);
            StabilityEvidence stability = stabilityEvidence(assertion, stabilityExecutionResult);
            facts.add(new CandidateFact(
                    null,
                    statementId == null ? "s?" : statementId,
                    sourceId,
                    assertion.getClass().getSimpleName(),
                    valueSummaryText(assertion.getValue(), options),
                    referencedIds,
                    assertionKey(references, assertion, options),
                    selectable,
                    "UNKNOWN",
                    50,
                    stability.label,
                    stability.reason,
                    assertable));
        }
        facts = assignCandidateRolesAndRanks(facts);
        facts.sort((left, right) -> {
            int leftRank = capabilities.hasActionRoles() ? left.getRank() : candidatePriority(left.getKind());
            int rightRank = capabilities.hasActionRoles() ? right.getRank() : candidatePriority(right.getKind());
            int priority = Integer.compare(leftRank, rightRank);
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
        int limit = options.contextLimits().candidateFacts();
        if (limit > 0 && facts.size() > limit) {
            List<CandidateFact> ranked = new ArrayList<>();
            Set<String> diversityKeys = new LinkedHashSet<>();
            for (CandidateFact fact : facts) {
                String key = String.valueOf(fact.getSourceId()) + "|" + fact.getKind();
                if (diversityKeys.add(key)) {
                    ranked.add(fact);
                    if (ranked.size() >= limit) {
                        break;
                    }
                }
            }
            if (ranked.size() < limit) {
                for (CandidateFact fact : facts) {
                    if (!ranked.contains(fact)) {
                        ranked.add(fact);
                        if (ranked.size() >= limit) {
                            break;
                        }
                    }
                }
            }
            facts = ranked;
        }
        List<CandidateFact> identified = new ArrayList<>();
        int candidateIndex = 0;
        for (CandidateFact fact : facts) {
            String candidateId = fact.getSelectableCandidate() == null ? null : "c" + candidateIndex++;
            identified.add(fact.withCandidateId(candidateId));
        }
        return identified;
    }

    private static List<CandidateFact> appendExceptionCandidateFacts(
            List<CandidateFact> original,
            List<ExceptionContext> exceptions,
            ExecutionResult stabilityExecutionResult,
            PromptVariantCapabilities capabilities,
            PostProcessingOptions options) {
        if (!capabilities.hasExceptionAdjacentPlacements() || exceptions.isEmpty()) {
            return original;
        }
        ExceptionContext observed = exceptions.get(0);
        Throwable repeated = repeatedExceptionAt(
                stabilityExecutionResult, stableIdIndex(observed.getStatementId()));
        String repeatedMessage = repeated == null
                ? null : sanitizedMessage(repeated.getMessage(), options);
        boolean stable = observed.isMessageComplete()
                && repeated != null
                && isCompleteExceptionMessage(repeated.getMessage(), options)
                && repeated.getClass().getName().equals(observed.getType())
                && java.util.Objects.equals(repeatedMessage, observed.getMessage());
        if (!stable) {
            return original;
        }
        List<CandidateFact> result = new ArrayList<>(original);
        int nextId = 0;
        for (CandidateFact fact : original) {
            if (fact.getCandidateId() != null) {
                nextId++;
            }
        }
        LlmPostProcessingResponse.AssertionKind kind = observed.getMessage() == null
                ? LlmPostProcessingResponse.AssertionKind.NULL
                : LlmPostProcessingResponse.AssertionKind.EQUALS;
        LlmPostProcessingResponseParser.SelectableCandidate selectable =
                new LlmPostProcessingResponseParser.SelectableCandidate(
                        kind, observed.getMessage(), "e0.getMessage()", null);
        result.add(new CandidateFact("c" + nextId, observed.getStatementId(), "e0",
                "ExceptionMessageAssertion", observed.getMessage(),
                Collections.singletonList("e0"),
                assertionKey(kind.name(), observed.getMessage(), "e0.getMessage()", null),
                selectable, "IN_CATCH", 10, "STABLE", null, true));
        if (observed.isCauseNull() && repeated.getCause() == null) {
            LlmPostProcessingResponseParser.SelectableCandidate causeCandidate =
                    new LlmPostProcessingResponseParser.SelectableCandidate(
                            LlmPostProcessingResponse.AssertionKind.NULL,
                            null, "e0.getCause()", null);
            result.add(new CandidateFact("c" + (nextId + 1), observed.getStatementId(), "e0",
                    "ExceptionCauseNullAssertion", "null", Collections.singletonList("e0"),
                    assertionKey("NULL", null, "e0.getCause()", null),
                    causeCandidate, "IN_CATCH", 20, "STABLE", null, true));
        }
        return result;
    }

    private static Throwable repeatedExceptionAt(ExecutionResult result, int position) {
        if (result == null || position < 0) {
            return null;
        }
        return result.getCopyOfExceptionMapping().get(position);
    }

    private static List<CandidateFact> assignCandidateRolesAndRanks(List<CandidateFact> facts) {
        int latestStatement = -1;
        for (CandidateFact fact : facts) {
            latestStatement = Math.max(latestStatement, stableIdIndex(fact.getStatementId()));
        }
        List<CandidateFact> result = new ArrayList<>();
        for (CandidateFact fact : facts) {
            int position = stableIdIndex(fact.getStatementId());
            String phase = position == latestStatement ? "RESULT" : "POST_STATE";
            int rank;
            if (position == latestStatement && !"NullAssertion".equals(fact.getKind())) {
                rank = 10;
            } else if ("EqualsAssertion".equals(fact.getKind())
                    || "CompareAssertion".equals(fact.getKind())
                    || "SameAssertion".equals(fact.getKind())) {
                rank = 20;
            } else if ("PrimitiveAssertion".equals(fact.getKind())
                    && !"0".equals(fact.getObservedValue())
                    && !"false".equals(fact.getObservedValue())
                    && !"null".equals(fact.getObservedValue())) {
                rank = 30;
            } else {
                rank = 60 + candidatePriority(fact.getKind());
            }
            result.add(fact.withRoleAndRank(phase, rank));
        }
        return result;
    }

    private static List<CandidateFact> actionRankedCandidateFacts(
            List<CandidateFact> facts, List<StatementContext> statements) {
        StatementContext lastAction = null;
        for (StatementContext statement : statements) {
            if ("ACTION".equals(statement.getPhase())) {
                lastAction = statement;
            }
        }
        if (lastAction == null) {
            return facts;
        }
        List<CandidateFact> ranked = new ArrayList<>();
        Set<String> postStateIds = new LinkedHashSet<>(lastAction.getArgumentIds());
        if (lastAction.getReceiverId() != null) {
            postStateIds.add(lastAction.getReceiverId());
        }
        for (CandidateFact fact : facts) {
            String phase;
            int rank;
            if (lastAction.getVariableId() != null
                    && lastAction.getVariableId().equals(fact.getSourceId())) {
                phase = "RESULT";
                rank = 10;
            } else if (postStateIds.contains(fact.getSourceId())) {
                phase = "POST_STATE";
                rank = 20;
            } else if (fact.getReferencedIds().size() > 1) {
                phase = "RELATION";
                rank = 30;
            } else {
                phase = "SETUP";
                rank = 80 + candidatePriority(fact.getKind());
            }
            ranked.add(fact.withCandidateId(null).withRoleAndRank(phase, rank));
        }
        ranked.sort(Comparator.comparingInt(CandidateFact::getRank)
                .thenComparing(CandidateFact::getStatementId)
                .thenComparing(fact -> fact.getSourceId() == null ? "" : fact.getSourceId())
                .thenComparing(CandidateFact::getKind));
        List<CandidateFact> identified = new ArrayList<>();
        int candidateId = 0;
        for (CandidateFact fact : ranked) {
            identified.add(fact.withCandidateId(
                    fact.getSelectableCandidate() == null ? null : "c" + candidateId++));
        }
        return identified;
    }

    private static int stableIdIndex(String id) {
        if (id == null || id.length() < 2) {
            return -1;
        }
        try {
            return Integer.parseInt(id.substring(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static StabilityEvidence stabilityEvidence(Assertion assertion,
                                                       ExecutionResult stabilityExecutionResult) {
        if (stabilityExecutionResult == null || stabilityExecutionResult.getFinalScope() == null) {
            return new StabilityEvidence("UNKNOWN", "missing_scope");
        }
        if (stabilityExecutionResult.hasTimeout() || stabilityExecutionResult.hasTestException()) {
            return new StabilityEvidence("UNKNOWN", "exception_or_timeout");
        }
        try {
            return assertion.evaluate(stabilityExecutionResult.getFinalScope())
                    ? new StabilityEvidence("STABLE", null)
                    : new StabilityEvidence("UNSTABLE", "changed_value");
        } catch (RuntimeException | AssertionError error) {
            return new StabilityEvidence("UNKNOWN", "evaluation_failure");
        }
    }

    private static boolean candidateIsAssertable(LlmPostProcessingReferences references, Assertion assertion) {
        for (VariableReference variable : assertion.getReferencedVariables()) {
            if (variableId(references, variable) == null || !isAccessibleType(variable.getVariableClass())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAccessibleType(Class<?> type) {
        if (type == null || type.isPrimitive()) {
            return true;
        }
        if (type.isArray()) {
            return isAccessibleType(type.getComponentType());
        }
        for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
            if (!Modifier.isPublic(current.getModifiers())) {
                // Assertion snippets are compiled in a generated helper class,
                // not in the SUT package. Same-package access in the final
                // EvoSuite test does not make this type snippet-safe.
                return false;
            }
        }
        return OracleTypeAccessibility.isAccessible(type);
    }

    private static List<RelationalOpportunity> relationalOpportunities(
            List<StatementContext> statements, List<CandidateFact> facts,
            List<CallableMember> callableMembers) {
        List<RelationalOperand> operands = new ArrayList<>();
        Set<String> seenExpressions = new LinkedHashSet<>();
        for (CandidateFact fact : facts) {
            LlmPostProcessingResponseParser.SelectableCandidate candidate = fact.getSelectableCandidate();
            if (candidate != null && candidate.getActual() != null
                    && candidate.getActual().matches("v[0-9]+")
                    && seenExpressions.add(candidate.getActual())) {
                operands.add(new RelationalOperand(candidate.getActual(),
                        declaredTypeForId(statements, fact.getSourceId()), fact.getPhase()));
            }
        }
        for (CallableMember member : callableMembers) {
            if (member.getReceiverId() == null || member.getObservedResult() == null
                    || !member.getSignature().contains("()")) {
                continue;
            }
            String expression = member.getReceiverId() + "." + readableSignature(member.getSignature());
            if (seenExpressions.add(expression)) {
                operands.add(new RelationalOperand(expression, member.getReturnType(), "OBSERVED"));
            }
        }
        for (StatementContext statement : statements) {
            if (statement.getVariableId() != null && statement.getDeclaredType() != null
                    && seenExpressions.add(statement.getVariableId())) {
                operands.add(new RelationalOperand(statement.getVariableId(),
                        statement.getDeclaredType(), statement.getPhase()));
            }
        }
        List<RelationalOpportunity> result = new ArrayList<>();
        Set<String> seenPairs = new LinkedHashSet<>();
        for (int leftIndex = 0; leftIndex < operands.size(); leftIndex++) {
            RelationalOperand left = operands.get(leftIndex);
            for (int rightIndex = leftIndex + 1; rightIndex < operands.size(); rightIndex++) {
                RelationalOperand right = operands.get(rightIndex);
                if (!compatibleRelationalTypes(left.type, right.type)) {
                    continue;
                }
                String key = left.expression + "\n" + right.expression;
                if (!seenPairs.add(key)) {
                    continue;
                }
                String relation = isNumericType(left.type)
                        ? "COMPARE_RESULTS" : "IDENTITY_OR_EQUALITY";
                result.add(new RelationalOpportunity("r" + result.size(), left.expression,
                        right.expression, left.type, relation));
            }
        }
        return result;
    }

    private static String declaredTypeForId(List<StatementContext> statements, String variableId) {
        if (variableId == null) {
            return null;
        }
        for (StatementContext statement : statements) {
            if (variableId.equals(statement.getVariableId())) {
                return statement.getDeclaredType();
            }
        }
        return null;
    }

    private static boolean compatibleRelationalTypes(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equals(right) || (isNumericType(left) && isNumericType(right));
    }

    private static boolean isNumericType(String type) {
        return type != null && type.replace("java.lang.", "")
                .matches("byte|short|int|long|float|double|Byte|Short|Integer|Long|Float|Double");
    }

    private static String statementPhase(Statement statement) {
        if (statement instanceof ConstructorStatement || statement instanceof PrimitiveStatement<?>
                || statement instanceof ArrayStatement) {
            return "SETUP";
        }
        if (statement instanceof MethodStatement) {
            MethodStatement method = (MethodStatement) statement;
            Class<?> declaringClass = method.getMethod() == null ? null
                    : method.getMethod().getDeclaringClass();
            if (declaringClass != null && Properties.TARGET_CLASS != null
                    && Properties.TARGET_CLASS.equals(declaringClass.getName())) {
                return "ACTION";
            }
        }
        return "UNKNOWN";
    }

    private static String statementReceiverId(LlmPostProcessingReferences references, Statement statement) {
        if (!(statement instanceof MethodStatement)) {
            return null;
        }
        return variableId(references, ((MethodStatement) statement).getCallee());
    }

    private static List<String> statementArgumentIds(LlmPostProcessingReferences references, Statement statement) {
        if (!(statement instanceof org.evosuite.testcase.statements.EntityWithParametersStatement)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (VariableReference parameter :
                ((org.evosuite.testcase.statements.EntityWithParametersStatement) statement)
                        .getParameterReferences()) {
            String id = variableId(references, parameter);
            if (id != null) {
                result.add(id);
            }
        }
        return result;
    }

    private static LlmPostProcessingResponseParser.SelectableCandidate selectableCandidate(
            LlmPostProcessingReferences references, Assertion assertion) {
        String code;
        try {
            code = assertion.getCode();
        } catch (RuntimeException | AssertionError error) {
            return null;
        }
        if (code == null) {
            return null;
        }
        Map<String, String> stableVariableIds = new LinkedHashMap<>();
        for (VariableReference variable : assertion.getReferencedVariables()) {
            String id = variableId(references, variable);
            if (id != null && variable.getName() != null) {
                stableVariableIds.put(variable.getName(), id);
            }
        }
        code = code.trim();
        if (code.endsWith(";")) {
            code = code.substring(0, code.length() - 1);
        }
        try {
            Expression expression = StaticJavaParser.parseExpression(code);
            for (NameExpr name : expression.findAll(NameExpr.class)) {
                String id = stableVariableIds.get(name.getNameAsString());
                if (id != null) {
                    name.setName(id);
                }
            }
            if (!(expression instanceof MethodCallExpr)) {
                return null;
            }
            MethodCallExpr call = (MethodCallExpr) expression;
            String method = call.getNameAsString();
            List<Expression> arguments = call.getArguments();
            LlmPostProcessingResponse.AssertionKind kind;
            String expected = null;
            String actual;
            String delta = null;
            if (("assertEquals".equals(method) || "assertArrayEquals".equals(method))
                    && arguments.size() >= 2) {
                kind = LlmPostProcessingResponse.AssertionKind.EQUALS;
                expected = arguments.get(0).toString();
                actual = arguments.get(1).toString();
                delta = arguments.size() >= 3 ? arguments.get(2).toString() : null;
            } else if ("assertNotEquals".equals(method) && arguments.size() >= 2) {
                kind = LlmPostProcessingResponse.AssertionKind.NOT_EQUALS;
                expected = arguments.get(0).toString();
                actual = arguments.get(1).toString();
            } else if ("assertTrue".equals(method) && arguments.size() == 1) {
                kind = LlmPostProcessingResponse.AssertionKind.TRUE;
                actual = arguments.get(0).toString();
            } else if ("assertFalse".equals(method) && arguments.size() == 1) {
                kind = LlmPostProcessingResponse.AssertionKind.FALSE;
                actual = arguments.get(0).toString();
            } else if ("assertNull".equals(method) && arguments.size() == 1) {
                kind = LlmPostProcessingResponse.AssertionKind.NULL;
                actual = arguments.get(0).toString();
            } else if ("assertNotNull".equals(method) && arguments.size() == 1) {
                kind = LlmPostProcessingResponse.AssertionKind.NOT_NULL;
                actual = arguments.get(0).toString();
            } else if ("assertSame".equals(method) && arguments.size() == 2) {
                kind = LlmPostProcessingResponse.AssertionKind.SAME;
                expected = arguments.get(0).toString();
                actual = arguments.get(1).toString();
            } else if ("assertNotSame".equals(method) && arguments.size() == 2) {
                kind = LlmPostProcessingResponse.AssertionKind.NOT_SAME;
                expected = arguments.get(0).toString();
                actual = arguments.get(1).toString();
            } else {
                return null;
            }
            return new LlmPostProcessingResponseParser.SelectableCandidate(
                    kind, expected, actual, delta);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String assertionKey(LlmPostProcessingReferences references, Assertion assertion,
                                       PostProcessingOptions options) {
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
        String expected = valueSummaryText(value, options);
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

    private static String valueSummaryText(Object value, PostProcessingOptions options) {
        ValueSummary summary = valueSummary(value, options);
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
                                                         Statement statement,
                                                         PostProcessingOptions options) {
        if (variableId == null || !(statement instanceof PrimitiveStatement<?>)) {
            return null;
        }
        Object value = ((PrimitiveStatement<?>) statement).getValue();
        String literal = literalValue(value);
        if (literal == null) {
            return null;
        }
        int maxChars = Math.max(1, options.assertionPolicy().maxLiteralChars());
        boolean complete = literal.length() <= maxChars;
        if (!complete) {
            literal = literal.substring(0, maxChars);
        }
        return new Observation(statementId, variableId, "INPUT", literal, complete, true);
    }

    private static Observation finalScopeObservation(String statementId, String variableId, Statement statement,
                                                     VariableReference returnValue, Scope finalScope,
                                                     PostProcessingOptions options) {
        if (variableId == null || finalScope == null || statement instanceof PrimitiveStatement<?>) {
            return null;
        }
        Object value = finalScopeValue(returnValue, finalScope);
        ValueSummary summary = valueSummary(value, options);
        if (summary == null) {
            return null;
        }
        String provenance = provenance(statement);
        return new Observation(statementId, variableId, provenance, summary.value, summary.complete,
                "INPUT".equals(provenance) || summary.relationalOnly);
    }

    private static String provenance(Statement statement) {
        if ("SETUP".equals(statementPhase(statement))) {
            return "INPUT";
        }
        if (statement instanceof FieldStatement) {
            return "FIELD_OBSERVATION";
        }
        return "SUT_RETURN";
    }

    private static ValueSummary valueSummary(Object value, PostProcessingOptions options) {
        String literal = literalValue(value);
        if (literal != null) {
            int maxChars = Math.max(1, options.assertionPolicy().maxLiteralChars());
            boolean complete = literal.length() <= maxChars;
            return new ValueSummary(complete ? literal : literal.substring(0, maxChars), complete, !complete);
        }
        if (value != null && value.getClass().isArray()) {
            return arraySummary(value, options);
        }
        if (value instanceof Collection<?>) {
            return collectionSummary((Collection<?>) value, options);
        }
        if (value instanceof Map<?, ?>) {
            return mapSummary((Map<?, ?>) value, options);
        }
        return null;
    }

    private static ValueSummary arraySummary(Object array, PostProcessingOptions options) {
        int length = Array.getLength(array);
        int maxElements = options.contextLimits().collectionElements();
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

    private static ValueSummary collectionSummary(Collection<?> collection, PostProcessingOptions options) {
        int maxElements = options.contextLimits().collectionElements();
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

    private static ValueSummary mapSummary(Map<?, ?> map, PostProcessingOptions options) {
        int maxElements = options.contextLimits().collectionElements();
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
                                                        String typeName, Object runtimeValue,
                                                        Set<String> advertisedTypes,
                                                        PostProcessingOptions options) {
        if (variableId == null || returnValue == null || returnValue.isVoid() || returnValue.isPrimitive()) {
            return Collections.emptyList();
        }
        if (typeName == null) {
            return Collections.emptyList();
        }
        List<CallableMember> members = new ArrayList<>();
        Class<?> declaredClass = classForTypeName(typeName, runtimeValue);
        addCuratedCallableMembers(members, variableId, typeName, runtimeValue, options);
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
        if (options.assertionPolicy().callablePolicy() == Properties.LlmPostProcessingCallablePolicy.INSPECTORS_ONLY
                || options.assertionPolicy().callablePolicy() == Properties.LlmPostProcessingCallablePolicy.PURE_BOUNDED) {
            addInspectorMembers(members, variableId, declaredClass, runtimeValue);
        }
        if (options.assertionPolicy().callablePolicy() == Properties.LlmPostProcessingCallablePolicy.PURE_BOUNDED
                && declaredClass != null) {
            addPureBoundedMembers(members, variableId, declaredClass, advertisedTypes, 0, options);
        }
        return deduplicateCallableMembers(members);
    }

    private static void addCuratedCallableMembers(List<CallableMember> members, String receiverId,
                                                  String typeName, Object runtimeValue,
                                                  PostProcessingOptions options) {
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
                String elementType = commonElementTypeName(collection, options);
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
        if (typeName == null || typeName.trim().isEmpty()) {
            return null;
        }
        if (runtimeValue != null) {
            Class<?> matchingRuntimeType = namedSupertype(runtimeValue.getClass(), typeName,
                    new LinkedHashSet<Class<?>>());
            if (matchingRuntimeType != null) {
                return matchingRuntimeType;
            }
        }
        try {
            ClassLoader loader = org.evosuite.TestGenerationContext.getInstance().getClassLoaderForSUT();
            return Class.forName(typeName, false, loader);
        } catch (Throwable ignored) {
            // Fall back to EvoSuite's own loader for JDK and harness types.
        }
        try {
            return Class.forName(typeName);
        } catch (Throwable ignored) {
            // Some legacy/uninterpreted statements retain only a runtime-resolved
            // local type name (notably package-private JDK collection classes).
            // Method/constructor/field statements are handled above with their
            // recoverable compile-time declaration, so this fallback is limited
            // to cases where no declared class can be resolved at all.
            return runtimeValue == null ? null : runtimeValue.getClass();
        }
    }

    private static Class<?> namedSupertype(Class<?> type, String typeName, Set<Class<?>> visited) {
        if (type == null || !visited.add(type)) {
            return null;
        }
        if (typeName.equals(type.getTypeName()) || typeName.equals(type.getName())) {
            return type;
        }
        for (Class<?> interfaceType : type.getInterfaces()) {
            Class<?> match = namedSupertype(interfaceType, typeName, visited);
            if (match != null) {
                return match;
            }
        }
        return namedSupertype(type.getSuperclass(), typeName, visited);
    }

    private static boolean isStringType(String typeName, Class<?> type) {
        return String.class.equals(type) || "java.lang.String".equals(typeName) || "String".equals(typeName);
    }

    /**
     * Return the shared runtime element type of a collection when its sampled
     * elements are homogeneous, otherwise {@code null}. Used to give {@code get}
     * a concrete return type instead of the erased {@code Object}.
     */
    private static String commonElementTypeName(Collection<?> collection, PostProcessingOptions options) {
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
        int maxElements = Math.max(1, options.contextLimits().collectionElements());
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
                                              Set<String> advertisedTypes, int depth,
                                              PostProcessingOptions options) {
        if (receiverType == null || !TestUsageChecker.canUse(receiverType)) {
            return;
        }
        int limit = options.assertionPolicy().maxCallableMembersPerType();
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
            if (!isPureBoundedCallable(method, options)) {
                continue;
            }
            add(members, receiverId, receiverType.getTypeName(),
                    method.getName() + org.objectweb.asm.Type.getMethodDescriptor(method),
                    method.getReturnType().getTypeName());
            accepted++;
            addCallableTypeMembersForReturnType(
                    members, method.getReturnType(), advertisedTypes, depth + 1, options);
        }
    }

    private static void addCallableTypeMembersForReturnType(List<CallableMember> members, Class<?> returnType,
                                                            Set<String> advertisedTypes, int depth,
                                                            PostProcessingOptions options) {
        if (!options.assertionPolicy().allowChainedCalls()
                || returnType == null || returnType.equals(Void.TYPE)) {
            return;
        }
        if (depth > options.assertionPolicy().maxChainDepth()) {
            return;
        }
        int maxTypes = options.assertionPolicy().maxCallableTypesPerTest();
        if (maxTypes > 0 && advertisedTypes.size() >= maxTypes) {
            return;
        }
        String typeName = returnType.getTypeName();
        if (!advertisedTypes.add(typeName)) {
            return;
        }
        addCuratedCallableMembers(members, null, typeName, null, options);
        if (options.assertionPolicy().callablePolicy()
                == Properties.LlmPostProcessingCallablePolicy.PURE_BOUNDED) {
            addPureBoundedMembers(members, null, returnType, advertisedTypes, depth, options);
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

    private static boolean isPureBoundedCallable(Method method, PostProcessingOptions options) {
        if (method == null || !Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())
                || method.isBridge() || method.isSynthetic() || method.getReturnType().equals(Void.TYPE)) {
            return false;
        }
        if (method.getDeclaringClass().equals(Object.class) || method.getDeclaringClass().equals(Enum.class)
                || DENIED_INSPECTOR_METHODS.contains(method.getName())) {
            return false;
        }
        if (method.getParameterTypes().length > options.assertionPolicy().maxCallableArgs()) {
            return false;
        }
        if (!TestUsageChecker.canUse(method)) {
            return false;
        }
        return CheapPurityAnalyzer.getInstance().isPure(method);
    }

    private static void addInspectorMembers(List<CallableMember> members, String variableId,
                                            Class<?> declaredClass, Object runtimeValue) {
        if (declaredClass == null || runtimeValue == null) {
            return;
        }
        if (declaredClass.isArray()) {
            return;
        }
        for (Inspector inspector : InspectorManager.getInstance().getInspectors(declaredClass)) {
            Method method = inspector.getMethod();
            if (!isAllowedInspectorMethod(method)
                    || !method.getDeclaringClass().isAssignableFrom(declaredClass)) {
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
        if (value instanceof Double) {
            double doubleValue = (Double) value;
            if (Double.isNaN(doubleValue)) {
                return "Double.NaN";
            }
            if (doubleValue == Double.POSITIVE_INFINITY) {
                return "Double.POSITIVE_INFINITY";
            }
            if (doubleValue == Double.NEGATIVE_INFINITY) {
                return "Double.NEGATIVE_INFINITY";
            }
        }
        if (value instanceof Float) {
            float floatValue = (Float) value;
            if (Float.isNaN(floatValue)) {
                return "Float.NaN";
            }
            if (floatValue == Float.POSITIVE_INFINITY) {
                return "Float.POSITIVE_INFINITY";
            }
            if (floatValue == Float.NEGATIVE_INFINITY) {
                return "Float.NEGATIVE_INFINITY";
            }
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
        private final String phase;
        private final String receiverId;
        private final List<String> argumentIds;

        private StatementContext(String statementId, String variableId, String declaredType, String runtimeType,
                                 String code, String phase, String receiverId, List<String> argumentIds) {
            this.statementId = statementId;
            this.variableId = variableId;
            this.declaredType = declaredType;
            this.runtimeType = runtimeType;
            this.code = code;
            this.phase = phase;
            this.receiverId = receiverId;
            this.argumentIds = Collections.unmodifiableList(new ArrayList<>(argumentIds));
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

        public String getPhase() {
            return phase;
        }

        public String getReceiverId() {
            return receiverId;
        }

        public List<String> getArgumentIds() {
            return argumentIds;
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
        private final boolean messageComplete;
        private final boolean causeNull;

        private ExceptionContext(String statementId, String type, boolean explicit, String message,
                                 boolean messageComplete, boolean causeNull) {
            this.statementId = statementId;
            this.type = type;
            this.explicit = explicit;
            this.message = message;
            this.messageComplete = messageComplete;
            this.causeNull = causeNull;
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

        public boolean isMessageComplete() {
            return messageComplete;
        }

        public boolean isCauseNull() {
            return causeNull;
        }
    }

    public static final class CandidateFact {
        private final String candidateId;
        private final String statementId;
        private final String sourceId;
        private final String kind;
        private final String observedValue;
        private final List<String> referencedIds;
        private final String assertionKey;
        private final LlmPostProcessingResponseParser.SelectableCandidate selectableCandidate;
        private final String phase;
        private final int rank;
        private final String stability;
        private final String stabilityReason;
        private final boolean assertable;

        private CandidateFact(String candidateId, String statementId, String sourceId, String kind,
                              String observedValue, List<String> referencedIds, String assertionKey,
                              LlmPostProcessingResponseParser.SelectableCandidate selectableCandidate,
                              String phase, int rank, String stability, String stabilityReason,
                              boolean assertable) {
            this.candidateId = candidateId;
            this.statementId = statementId;
            this.sourceId = sourceId;
            this.kind = kind;
            this.observedValue = observedValue;
            this.referencedIds = Collections.unmodifiableList(new ArrayList<>(referencedIds));
            this.assertionKey = assertionKey;
            this.selectableCandidate = selectableCandidate;
            this.phase = phase;
            this.rank = rank;
            this.stability = stability;
            this.stabilityReason = stabilityReason;
            this.assertable = assertable;
        }

        private CandidateFact withCandidateId(String id) {
            return new CandidateFact(id, statementId, sourceId, kind, observedValue,
                    referencedIds, assertionKey, selectableCandidate, phase, rank,
                    stability, stabilityReason, assertable);
        }

        private CandidateFact withRoleAndRank(String candidatePhase, int candidateRank) {
            return new CandidateFact(candidateId, statementId, sourceId, kind, observedValue,
                    referencedIds, assertionKey, selectableCandidate, candidatePhase, candidateRank,
                    stability, stabilityReason, assertable);
        }

        public String getCandidateId() {
            return candidateId;
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

        public String getPhase() {
            return phase;
        }

        public int getRank() {
            return rank;
        }

        public String getStability() {
            return stability;
        }

        public String getStabilityReason() {
            return stabilityReason;
        }

        public boolean isAssertable() {
            return assertable;
        }

        LlmPostProcessingResponseParser.SelectableCandidate getSelectableCandidate() {
            return selectableCandidate;
        }
    }

    public static final class RelationalOpportunity {
        private final String id;
        private final String left;
        private final String right;
        private final String type;
        private final String relation;

        private RelationalOpportunity(String id, String left, String right, String type, String relation) {
            this.id = id;
            this.left = left;
            this.right = right;
            this.type = type;
            this.relation = relation;
        }

        public String getId() {
            return id;
        }

        public String getLeft() {
            return left;
        }

        public String getRight() {
            return right;
        }

        public String getType() {
            return type;
        }

        public String getRelation() {
            return relation;
        }
    }

    private static final class StabilityEvidence {
        private final String label;
        private final String reason;

        private StabilityEvidence(String label, String reason) {
            this.label = label;
            this.reason = reason;
        }
    }

    private static final class RelationalOperand {
        private final String expression;
        private final String type;
        private final String phase;

        private RelationalOperand(String expression, String type, String phase) {
            this.expression = expression;
            this.type = type;
            this.phase = phase;
        }
    }
}
