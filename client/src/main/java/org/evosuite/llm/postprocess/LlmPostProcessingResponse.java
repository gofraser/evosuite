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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parsed canonical edit plan returned by unified LLM post-processing.
 */
public class LlmPostProcessingResponse {

    private String assertionDecision;
    private String noAssertionReason;
    private String testName;
    private final Map<String, String> variableNames = new LinkedHashMap<>();
    private final List<CommentProposal> comments = new ArrayList<>();
    private final Set<String> sectionBreaksAfter = new LinkedHashSet<>();
    private final List<AssertionProposal> assertions = new ArrayList<>();

    public LlmPostProcessingResponse() {
    }

    public String getAssertionDecision() {
        return assertionDecision;
    }

    void setAssertionDecision(String assertionDecision) {
        this.assertionDecision = assertionDecision;
    }

    public String getNoAssertionReason() {
        return noAssertionReason;
    }

    void setNoAssertionReason(String noAssertionReason) {
        this.noAssertionReason = noAssertionReason;
    }

    public String getTestName() {
        return testName;
    }

    public void setTestName(String testName) {
        this.testName = testName;
    }

    public Map<String, String> getVariableNames() {
        return Collections.unmodifiableMap(variableNames);
    }

    void addVariableName(String variableId, String name) {
        variableNames.put(variableId, name);
    }

    public List<CommentProposal> getComments() {
        return Collections.unmodifiableList(comments);
    }

    void addComment(CommentProposal comment) {
        comments.add(comment);
    }

    public Set<String> getSectionBreaksAfter() {
        return Collections.unmodifiableSet(sectionBreaksAfter);
    }

    void addSectionBreakAfter(String statementId) {
        sectionBreaksAfter.add(statementId);
    }

    public List<AssertionProposal> getAssertions() {
        return Collections.unmodifiableList(assertions);
    }

    void addAssertion(AssertionProposal assertion) {
        assertions.add(assertion);
    }

    /**
     * Returns the non-assertion edits as a separate response for validation or
     * repair flows that temporarily remove assertion proposals.
     */
    LlmPostProcessingResponse withoutAssertions() {
        return copy(false);
    }

    /** Create an independent domain snapshot for a validated edit plan. */
    LlmPostProcessingResponse copy() {
        return copy(true);
    }

    private LlmPostProcessingResponse copy(boolean includeAssertions) {
        LlmPostProcessingResponse copy = new LlmPostProcessingResponse();
        copy.setAssertionDecision(assertionDecision);
        copy.setNoAssertionReason(noAssertionReason);
        copy.setTestName(testName);
        for (Map.Entry<String, String> entry : variableNames.entrySet()) {
            copy.addVariableName(entry.getKey(), entry.getValue());
        }
        for (CommentProposal comment : comments) {
            copy.addComment(new CommentProposal(comment.getAfterStatementId(), comment.getText()));
        }
        for (String sectionBreak : sectionBreaksAfter) {
            copy.addSectionBreakAfter(sectionBreak);
        }
        if (includeAssertions) {
            for (AssertionProposal assertion : assertions) {
                copy.addAssertion(new AssertionProposal(
                        assertion.getAssertionId(), assertion.getKind(), assertion.getExpected(),
                        assertion.getActual(), assertion.getDelta(), assertion.getPurpose(),
                        assertion.getIntent(), assertion.getCandidateId()));
            }
        }
        return copy;
    }

    public enum AssertionKind {
        EQUALS,
        NOT_EQUALS,
        TRUE,
        FALSE,
        NULL,
        NOT_NULL,
        SAME,
        NOT_SAME,
        CONTAINS,
        NOT_CONTAINS,
        SIZE_EQUALS,
        MAP_CONTAINS_KEY,
        IS_EMPTY,
        GREATER,
        LESS,
        GREATER_EQUALS,
        LESS_EQUALS
    }

    public static final class CommentProposal {
        private final String afterStatementId;
        private final String text;

        public CommentProposal(String afterStatementId, String text) {
            this.afterStatementId = afterStatementId;
            this.text = text;
        }

        public String getAfterStatementId() {
            return afterStatementId;
        }

        public String getText() {
            return text;
        }
    }

    public static final class AssertionProposal {
        private final String assertionId;
        private final AssertionKind kind;
        private final String expected;
        private final String actual;
        private final String delta;
        private final String purpose;
        private final String intent;
        private final String candidateId;

        public AssertionProposal(String assertionId, AssertionKind kind, String expected,
                                 String actual, String delta, String purpose) {
            this(assertionId, kind, expected, actual, delta, purpose, null, null);
        }

        public AssertionProposal(String assertionId, AssertionKind kind, String expected,
                                 String actual, String delta, String purpose,
                                 String intent, String candidateId) {
            this.assertionId = assertionId;
            this.kind = kind;
            this.expected = expected;
            this.actual = actual;
            this.delta = delta;
            this.purpose = purpose;
            this.intent = intent;
            this.candidateId = candidateId;
        }

        public String getAssertionId() {
            return assertionId;
        }

        public AssertionKind getKind() {
            return kind;
        }

        public String getExpected() {
            return expected;
        }

        public String getActual() {
            return actual;
        }

        public String getDelta() {
            return delta;
        }

        public String getPurpose() {
            return purpose;
        }

        public String getIntent() {
            return intent;
        }

        public String getCandidateId() {
            return candidateId;
        }

        public String getSource() {
            return candidateId == null ? "SYNTHESIZED" : "SELECTED_CANDIDATE";
        }
    }
}
