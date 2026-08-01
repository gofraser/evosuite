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

/**
 * Applies parsed non-assertion unified post-processing edits as test metadata.
 */
public final class LlmPostProcessingEditApplier {
    private LlmPostProcessingEditApplier() {
        // Utility class.
    }

    public static ApplyResult apply(TestCase test, LlmPostProcessingReferences references,
                                    LlmPostProcessingResponse response, boolean assertionsAllowed,
                                    ExecutionResult executionResult, PostProcessingOptions options) {
        if (test == null || references == null || response == null) {
            return new ApplyResult(0, 0, 0, 0, java.util.Collections.<TemplateCodeAssertion>emptyList());
        }
        if (options == null) {
            throw new IllegalArgumentException("Post-processing options are required");
        }
        PostProcessingOptions.Features features = options.features();
        TestPresentationMetadata metadata = TestPresentationMetadata.getOrCreate(test);
        TestPresentationMetadata originalMetadata = metadata.copy();
        Integer firstExceptionPosition = executionResult == null
                ? null
                : executionResult.getFirstPositionOfThrownException();
        int testNames = 0;
        int variableNames = 0;
        int comments = 0;
        int sectionBreaks = 0;
        List<TemplateCodeAssertion> appliedAssertions = new ArrayList<>();

        if (features.testNames() && response.getTestName() != null) {
            metadata.setTestName(response.getTestName());
            testNames = 1;
        }

        if (features.variableNames()) {
            Map<Integer, String> proposalsByPosition = new TreeMap<>();
            for (Map.Entry<String, String> entry : response.getVariableNames().entrySet()) {
                if (!references.hasVariableId(entry.getKey())) {
                    continue;
                }
                proposalsByPosition.put(references.getVariablePosition(entry.getKey()), entry.getValue());
            }
            Map<Integer, String> currentNames = renderedVariableNames(test, executionResult);
            Set<String> usedNames = new LinkedHashSet<>(currentNames.values());
            for (Integer renamedPosition : proposalsByPosition.keySet()) {
                usedNames.remove(currentNames.get(renamedPosition));
            }
            for (Map.Entry<Integer, String> entry : proposalsByPosition.entrySet()) {
                metadata.putVariableName(entry.getKey(), uniqueName(entry.getValue(), usedNames));
            }
            variableNames = proposalsByPosition.size();
        }

        if (features.comments()) {
            for (LlmPostProcessingResponse.CommentProposal comment : response.getComments()) {
                if (!references.hasStatementId(comment.getAfterStatementId())) {
                    continue;
                }
                int position = references.getStatementPosition(comment.getAfterStatementId());
                if (isAfterTerminatingException(position, firstExceptionPosition)) {
                    continue;
                }
                metadata.addCommentAfter(position, comment.getText());
                comments++;
            }
        }

        if (features.sectionBreaks()) {
            for (String statementId : response.getSectionBreaksAfter()) {
                if (!references.hasStatementId(statementId)) {
                    continue;
                }
                int position = references.getStatementPosition(statementId);
                if (isAfterTerminatingException(position, firstExceptionPosition)) {
                    continue;
                }
                metadata.addSectionBreakAfter(position);
                sectionBreaks++;
            }
        }

        if (testNames > 0 || variableNames > 0 || comments > 0 || sectionBreaks > 0) {
            if (!canRender(test, executionResult)) {
                metadata.replaceWith(originalMetadata);
                testNames = 0;
                variableNames = 0;
                comments = 0;
                sectionBreaks = 0;
            }
        }

        if (assertionsAllowed && features.assertions() && test.size() > 0) {
            List<AttachedAssertion> attached = new ArrayList<>();
            for (LlmPostProcessingResponse.AssertionProposal proposal : response.getAssertions()) {
                TemplateCodeAssertion assertion = toTemplateAssertionForValidation(proposal, references);
                if (assertion == null) {
                    continue;
                }
                Statement host = test.getStatement(test.size() - 1);
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
                TemplateCodeAssertion.Placement.END_OF_TEST);
        if (proposal.getPurpose() != null && !proposal.getPurpose().trim().isEmpty()) {
            assertion.setComment("// " + proposal.getPurpose().trim());
        }
        return assertion;
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
