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
import org.evosuite.assertion.TemplateCodeAssertion;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.Statement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
        if (test == null || references == null || response == null) {
            return new ApplyResult(0, 0, 0, 0, 0);
        }
        LlmPostProcessingMetadata metadata = LlmPostProcessingMetadata.getOrCreate(test);
        Integer firstExceptionPosition = executionResult == null
                ? null
                : executionResult.getFirstPositionOfThrownException();
        int testNames = 0;
        int variableNames = 0;
        int comments = 0;
        int sectionBreaks = 0;
        int assertions = 0;

        if (Properties.LLM_POSTPROCESSING_TEST_NAMES && response.getTestName() != null) {
            LlmPostProcessingMetadata snapshot = metadata.copy();
            try {
                metadata.setTestName(response.getTestName());
                testNames = 1;
                if (!canRender(test, executionResult)) {
                    metadata.replaceWith(snapshot);
                    testNames = 0;
                }
            } catch (RuntimeException | AssertionError e) {
                metadata.replaceWith(snapshot);
                testNames = 0;
            }
        }

        if (Properties.LLM_POSTPROCESSING_VARIABLE_NAMES) {
            LlmPostProcessingMetadata snapshot = metadata.copy();
            try {
                Map<Integer, String> proposalsByPosition = new TreeMap<>();
                for (Map.Entry<String, String> entry : response.getVariableNames().entrySet()) {
                    if (!references.hasVariableId(entry.getKey())) {
                        continue;
                    }
                    proposalsByPosition.put(references.getVariablePosition(entry.getKey()), entry.getValue());
                }
                Set<String> usedNames = renderedLocalVariableNames(test, executionResult);
                for (Map.Entry<Integer, String> entry : proposalsByPosition.entrySet()) {
                    metadata.putVariableName(entry.getKey(), uniqueName(entry.getValue(), usedNames));
                    variableNames++;
                }
                if (!canRender(test, executionResult)) {
                    metadata.replaceWith(snapshot);
                    variableNames = 0;
                }
            } catch (RuntimeException | AssertionError e) {
                metadata.replaceWith(snapshot);
                variableNames = 0;
            }
        }

        if (Properties.LLM_POSTPROCESSING_COMMENTS) {
            LlmPostProcessingMetadata snapshot = metadata.copy();
            try {
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
                if (!canRender(test, executionResult)) {
                    metadata.replaceWith(snapshot);
                    comments = 0;
                }
            } catch (RuntimeException | AssertionError e) {
                metadata.replaceWith(snapshot);
                comments = 0;
            }
        }

        if (Properties.LLM_POSTPROCESSING_SECTION_BREAKS) {
            LlmPostProcessingMetadata snapshot = metadata.copy();
            try {
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
                if (!canRender(test, executionResult)) {
                    metadata.replaceWith(snapshot);
                    sectionBreaks = 0;
                }
            } catch (RuntimeException | AssertionError e) {
                metadata.replaceWith(snapshot);
                sectionBreaks = 0;
            }
        }

        if (assertionsAllowed && Properties.LLM_POSTPROCESSING_ASSERTIONS && test.size() > 0) {
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
                    assertions++;
                } catch (RuntimeException | AssertionError e) {
                    for (AttachedAssertion attachedAssertion : attached) {
                        attachedAssertion.host.removeAssertion(attachedAssertion.assertion);
                    }
                    assertions = 0;
                    break;
                }
            }
            if (assertions > 0 && !canRender(test, executionResult)) {
                for (AttachedAssertion attachedAssertion : attached) {
                    attachedAssertion.host.removeAssertion(attachedAssertion.assertion);
                }
                assertions = 0;
            }
        }

        return new ApplyResult(testNames, variableNames, comments, sectionBreaks, assertions);
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
                proposal.getExpected(), proposal.getActual(), proposal.getDelta(), bindings, proposal.getPurpose());
        if (proposal.getPurpose() != null && !proposal.getPurpose().trim().isEmpty()) {
            assertion.setComment("// " + proposal.getPurpose().trim());
        }
        return assertion;
    }

    private static Statement hostStatementForAssertion(TestCase test, LlmPostProcessingReferences references,
                                                       LlmPostProcessingResponse.AssertionProposal proposal) {
        String afterStatementId = proposal.getAfterStatementId();
        if (afterStatementId != null && references.hasStatementId(afterStatementId)) {
            int position = references.getStatementPosition(afterStatementId);
            if (position >= 0 && position < test.size()) {
                return test.getStatement(position);
            }
        }
        return test.getStatement(test.size() - 1);
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
        private final int assertionsApplied;

        private ApplyResult(int testNamesApplied, int variableNamesApplied,
                            int commentsApplied, int sectionBreaksApplied, int assertionsApplied) {
            this.testNamesApplied = testNamesApplied;
            this.variableNamesApplied = variableNamesApplied;
            this.commentsApplied = commentsApplied;
            this.sectionBreaksApplied = sectionBreaksApplied;
            this.assertionsApplied = assertionsApplied;
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
            return assertionsApplied;
        }

        public boolean hasAppliedEdits() {
            return testNamesApplied > 0
                    || variableNamesApplied > 0
                    || commentsApplied > 0
                    || sectionBreaksApplied > 0
                    || assertionsApplied > 0;
        }
    }
}
