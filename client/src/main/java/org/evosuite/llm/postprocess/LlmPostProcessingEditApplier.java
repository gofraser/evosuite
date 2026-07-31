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

import org.evosuite.assertion.TemplateCodeAssertion;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestCodeVisitor;
import org.evosuite.testcase.TestPresentationMetadata;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.Statement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies parsed non-assertion unified post-processing edits as test metadata.
 */
public final class LlmPostProcessingEditApplier {
    private static final Pattern LOCAL_DECLARATION_NAME =
            Pattern.compile("\\b[A-Za-z_$][A-Za-z0-9_$<>\\[\\]., ?]*\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:=|;)");

    private LlmPostProcessingEditApplier() {
        // Utility class.
    }

    public static ApplyResult apply(TestCase test, LlmPostProcessingReferences references,
                                    LlmPostProcessingResponse response) {
        return apply(test, references, response, true);
    }

    public static ApplyResult apply(TestCase test, LlmPostProcessingReferences references,
                                    LlmPostProcessingResponse response, boolean assertionsAllowed) {
        return apply(test, references, response, assertionsAllowed, null);
    }

    public static ApplyResult apply(TestCase test, LlmPostProcessingReferences references,
                                    LlmPostProcessingResponse response, boolean assertionsAllowed,
                                    ExecutionResult executionResult) {
        return apply(test, references, response, assertionsAllowed, executionResult, null);
    }

    /** Apply using one phase snapshot; null is retained only for old callers. */
    public static ApplyResult apply(TestCase test, LlmPostProcessingReferences references,
                                    LlmPostProcessingResponse response, boolean assertionsAllowed,
                                    ExecutionResult executionResult, PostProcessingOptions options) {
        if (test == null || references == null || response == null) {
            return new ApplyResult(0, 0, 0, 0, java.util.Collections.<TemplateCodeAssertion>emptyList());
        }
        PostProcessingOptions effectiveOptions = options == null
                ? PostProcessingOptions.fromProperties() : options;
        PostProcessingOptions.Features features = effectiveOptions.features();
        TestPresentationMetadata metadata = TestPresentationMetadata.getOrCreate(test);
        Integer firstExceptionPosition = executionResult == null
                ? null
                : executionResult.getFirstPositionOfThrownException();
        int testNames = 0;
        int variableNames = 0;
        int comments = 0;
        int sectionBreaks = 0;
        List<TemplateCodeAssertion> appliedAssertions = new ArrayList<>();

        if (features.testNames() && response.getTestName() != null) {
            testNames = applyPresentationCategory(metadata, test, executionResult, () -> {
                metadata.setTestName(response.getTestName());
                return 1;
            });
        }

        if (features.variableNames()) {
            variableNames = applyPresentationCategory(metadata, test, executionResult, () -> {
                Map<Integer, String> proposalsByPosition = new TreeMap<>();
                for (Map.Entry<String, String> entry : response.getVariableNames().entrySet()) {
                    if (!references.hasVariableId(entry.getKey())) {
                        continue;
                    }
                    proposalsByPosition.put(references.getVariablePosition(entry.getKey()), entry.getValue());
                }
                Set<String> usedNames = renderedLocalVariableNames(test, executionResult);
                Map<Integer, String> currentNames = renderedVariableNames(test, executionResult);
                for (Integer renamedPosition : proposalsByPosition.keySet()) {
                    usedNames.remove(currentNames.get(renamedPosition));
                }
                for (Map.Entry<Integer, String> entry : proposalsByPosition.entrySet()) {
                    metadata.putVariableName(entry.getKey(), uniqueName(entry.getValue(), usedNames));
                }
                return proposalsByPosition.size();
            });
        }

        if (features.comments()) {
            comments = applyPresentationCategory(metadata, test, executionResult, () -> {
                int applied = 0;
                for (LlmPostProcessingResponse.CommentProposal comment : response.getComments()) {
                    if (!references.hasStatementId(comment.getAfterStatementId())) {
                        continue;
                    }
                    int position = references.getStatementPosition(comment.getAfterStatementId());
                    if (isAfterTerminatingException(position, firstExceptionPosition)) {
                        continue;
                    }
                    metadata.addCommentAfter(position, comment.getText());
                    applied++;
                }
                return applied;
            });
        }

        if (features.sectionBreaks()) {
            sectionBreaks = applyPresentationCategory(metadata, test, executionResult, () -> {
                int applied = 0;
                for (String statementId : response.getSectionBreaksAfter()) {
                    if (!references.hasStatementId(statementId)) {
                        continue;
                    }
                    int position = references.getStatementPosition(statementId);
                    if (isAfterTerminatingException(position, firstExceptionPosition)) {
                        continue;
                    }
                    metadata.addSectionBreakAfter(position);
                    applied++;
                }
                return applied;
            });
        }

        if (assertionsAllowed && features.assertions() && test.size() > 0) {
            List<AttachedAssertion> attached = new ArrayList<>();
            for (LlmPostProcessingResponse.AssertionProposal proposal : response.getAssertions()) {
                TemplateCodeAssertion assertion = toTemplateAssertion(proposal, references);
                if (assertion == null) {
                    continue;
                }
                Statement host = hostStatementForAssertion(test, references, proposal);
                try {
                    assertion.setStatement(host);
                    host.addAssertion(assertion);
                    attached.add(new AttachedAssertion(host, assertion));
                    appliedAssertions.add(assertion);
                } catch (RuntimeException | AssertionError e) {
                    for (AttachedAssertion attachedAssertion : attached) {
                        attachedAssertion.host.removeAssertion(attachedAssertion.assertion);
                    }
                    appliedAssertions.clear();
                    break;
                }
            }
            if (!appliedAssertions.isEmpty() && !canRender(test, executionResult)) {
                for (AttachedAssertion attachedAssertion : attached) {
                    attachedAssertion.host.removeAssertion(attachedAssertion.assertion);
                }
                appliedAssertions.clear();
            }
        }

        return new ApplyResult(testNames, variableNames, comments, sectionBreaks, appliedAssertions);
    }

    private static TemplateCodeAssertion toTemplateAssertion(LlmPostProcessingResponse.AssertionProposal proposal,
                                                             LlmPostProcessingReferences references) {
        return toTemplateAssertionForValidation(proposal, references);
    }

    static TemplateCodeAssertion toTemplateAssertionForValidation(
            LlmPostProcessingResponse.AssertionProposal proposal,
            LlmPostProcessingReferences references) {
        Map<String, Integer> bindings = new LinkedHashMap<>();
        if (!bindExpression(proposal.getExpected(), references, bindings)) {
            return null;
        }
        if (!bindExpression(proposal.getActual(), references, bindings)) {
            return null;
        }
        if (!bindExpression(proposal.getDelta(), references, bindings)) {
            return null;
        }
        TemplateCodeAssertion assertion = new TemplateCodeAssertion(proposal.getAssertionId(), proposal.getKind(),
                proposal.getExpected(), proposal.getActual(), proposal.getDelta(), bindings, proposal.getPurpose(),
                templatePlacement(proposal.getSite()));
        if (proposal.getPurpose() != null && !proposal.getPurpose().trim().isEmpty()) {
            assertion.setComment("// " + proposal.getPurpose().trim());
        }
        return assertion;
    }

    private static Statement hostStatementForAssertion(TestCase test, LlmPostProcessingReferences references,
                                                       LlmPostProcessingResponse.AssertionProposal proposal) {
        if (proposal.getSite() == LlmPostProcessingResponse.AssertionSite.BEFORE_TRY
                && proposal.getAfterStatementId() != null
                && references.hasStatementId(proposal.getAfterStatementId())) {
            return references.resolveStatement(test, proposal.getAfterStatementId());
        }
        return test.getStatement(test.size() - 1);
    }

    private static TemplateCodeAssertion.Placement templatePlacement(
            LlmPostProcessingResponse.AssertionSite site) {
        if (site == null) {
            return TemplateCodeAssertion.Placement.END_OF_TEST;
        }
        switch (site) {
            case BEFORE_TRY:
                return TemplateCodeAssertion.Placement.BEFORE_TRY;
            case IN_CATCH:
                return TemplateCodeAssertion.Placement.IN_CATCH;
            case AFTER_CATCH:
                return TemplateCodeAssertion.Placement.AFTER_CATCH;
            case END_OF_TEST:
            default:
                return TemplateCodeAssertion.Placement.END_OF_TEST;
        }
    }

    private static String uniqueName(String requestedName, Set<String> usedNames) {
        String base = requestedName == null ? "value" : requestedName.trim();
        if (base.isEmpty()) {
            base = "value";
        }
        if (usedNames.add(base)) {
            return base;
        }
        int suffix = 2;
        String candidate;
        do {
            candidate = base + suffix;
            suffix++;
        } while (!usedNames.add(candidate));
        return candidate;
    }

    private static boolean isAfterTerminatingException(int position, Integer firstExceptionPosition) {
        return firstExceptionPosition != null && position >= firstExceptionPosition;
    }

    private static boolean canRender(TestCase test, ExecutionResult executionResult) {
        try {
            if (executionResult == null) {
                test.toCode();
            } else {
                test.toCode(executionResult.getCopyOfExceptionMapping());
            }
            return true;
        } catch (RuntimeException | AssertionError e) {
            return false;
        }
    }

    /**
     * Apply one presentation category as an independent transaction.  A
     * failed category restores only its own snapshot, so other accepted
     * presentation categories remain committed.
     */
    private static int applyPresentationCategory(TestPresentationMetadata metadata,
                                                 TestCase test,
                                                 ExecutionResult executionResult,
                                                 IntSupplier mutation) {
        TestPresentationMetadata snapshot = metadata.copy();
        try {
            int applied = mutation.getAsInt();
            if (!canRender(test, executionResult)) {
                metadata.replaceWith(snapshot);
                return 0;
            }
            return applied;
        } catch (RuntimeException | AssertionError e) {
            metadata.replaceWith(snapshot);
            return 0;
        }
    }

    private static Set<String> renderedLocalVariableNames(TestCase test, ExecutionResult executionResult) {
        Set<String> names = new LinkedHashSet<>();
        if (test == null) {
            return names;
        }
        try {
            String code = executionResult == null
                    ? test.toCode()
                    : test.toCode(executionResult.getCopyOfExceptionMapping());
            Matcher matcher = LOCAL_DECLARATION_NAME.matcher(code);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        } catch (RuntimeException | AssertionError e) {
            // The render consistency check handles broken tests; do not reject names eagerly here.
        }
        return names;
    }

    private static Map<Integer, String> renderedVariableNames(TestCase test, ExecutionResult executionResult) {
        Map<Integer, String> names = new LinkedHashMap<>();
        if (test == null) {
            return names;
        }
        try {
            TestCodeVisitor visitor = new TestCodeVisitor();
            if (executionResult != null) {
                visitor.setExceptions(executionResult.getCopyOfExceptionMapping());
            }
            visitor.visitTestCase(test);
            for (int position = 0; position < test.size(); position++) {
                if (test.getStatement(position).getReturnValue() != null) {
                    names.put(position, visitor.getVariableName(test.getStatement(position).getReturnValue()));
                }
            }
        } catch (RuntimeException | AssertionError ignored) {
            // The category render check remains the authoritative fallback.
        }
        return names;
    }

    private static boolean bindExpression(String expression, LlmPostProcessingReferences references,
                                          Map<String, Integer> bindings) {
        Set<String> symbolicVariables = TemplateCodeAssertion.extractSymbolicVariables(expression);
        for (String variableId : symbolicVariables) {
            if ("e0".equals(variableId)) {
                continue;
            }
            if (!references.hasVariableId(variableId)) {
                return false;
            }
            bindings.put(variableId, references.getVariablePosition(variableId));
        }
        return true;
    }

    private static final class AttachedAssertion {
        private final Statement host;
        private final TemplateCodeAssertion assertion;

        private AttachedAssertion(Statement host, TemplateCodeAssertion assertion) {
            this.host = host;
            this.assertion = assertion;
        }
    }

    public static final class ApplyResult {
        private final int testNamesApplied;
        private final int variableNamesApplied;
        private final int commentsApplied;
        private final int sectionBreaksApplied;
        private final List<TemplateCodeAssertion> appliedAssertions;

        private ApplyResult(int testNamesApplied, int variableNamesApplied,
                            int commentsApplied, int sectionBreaksApplied,
                            List<TemplateCodeAssertion> appliedAssertions) {
            this.testNamesApplied = testNamesApplied;
            this.variableNamesApplied = variableNamesApplied;
            this.commentsApplied = commentsApplied;
            this.sectionBreaksApplied = sectionBreaksApplied;
            this.appliedAssertions = java.util.Collections.unmodifiableList(
                    new ArrayList<>(appliedAssertions));
        }

        public int getTestNamesApplied() {
            return testNamesApplied;
        }

        public int getVariableNamesApplied() {
            return variableNamesApplied;
        }

        public int getCommentsApplied() {
            return commentsApplied;
        }

        public int getSectionBreaksApplied() {
            return sectionBreaksApplied;
        }

        public int getAssertionsApplied() {
            return appliedAssertions.size();
        }

        public List<TemplateCodeAssertion> getAppliedAssertions() {
            return appliedAssertions;
        }

        public boolean hasAppliedEdits() {
            return testNamesApplied > 0
                    || variableNamesApplied > 0
                    || commentsApplied > 0
                    || sectionBreaksApplied > 0
                    || !appliedAssertions.isEmpty();
        }
    }
}
