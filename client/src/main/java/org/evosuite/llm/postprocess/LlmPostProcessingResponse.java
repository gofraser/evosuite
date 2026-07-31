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
 * Parsed schema-versioned edit plan returned by unified LLM post-processing.
 */
public class LlmPostProcessingResponse {

    public static final int SUPPORTED_SCHEMA_VERSION = LlmPostProcessingProtocol.MAX_RESPONSE_SCHEMA_VERSION;

    private final int schemaVersion;
    private String assertionDecision;
    private String noAssertionReason;
    private String testName;
    private final Map<String, String> variableNames = new LinkedHashMap<>();
    private final List<CommentProposal> comments = new ArrayList<>();
    private final Set<String> sectionBreaksAfter = new LinkedHashSet<>();
    private final List<AssertionProposal> assertions = new ArrayList<>();

    public LlmPostProcessingResponse(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public int getSchemaVersion() {
        return schemaVersion;
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

    public enum AssertionSite {
        END_OF_TEST,
        BEFORE_TRY,
        IN_CATCH,
        AFTER_CATCH
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
        private final String afterStatementId;
        private final String candidateId;
        private final AssertionSite site;
        private final String exceptionId;

        public AssertionProposal(String assertionId, AssertionKind kind, String expected,
                                 String actual, String delta, String purpose) {
            this(assertionId, kind, expected, actual, delta, purpose, null, null);
        }

        public AssertionProposal(String assertionId, AssertionKind kind, String expected,
                                 String actual, String delta, String purpose,
                                 String intent, String afterStatementId) {
            this(assertionId, kind, expected, actual, delta, purpose, intent, afterStatementId, null);
        }

        public AssertionProposal(String assertionId, AssertionKind kind, String expected,
                                 String actual, String delta, String purpose,
                                 String intent, String afterStatementId, String candidateId) {
            this.assertionId = assertionId;
            this.kind = kind;
            this.expected = expected;
            this.actual = actual;
            this.delta = delta;
            this.purpose = purpose;
            this.intent = intent;
            this.afterStatementId = afterStatementId;
            this.candidateId = candidateId;
            this.site = afterStatementId == null ? AssertionSite.END_OF_TEST : AssertionSite.BEFORE_TRY;
            this.exceptionId = null;
        }

        public AssertionProposal(String assertionId, AssertionKind kind, String expected,
                                 String actual, String delta, String purpose,
                                 String intent, AssertionSite site, String afterStatementId,
                                 String exceptionId, String candidateId) {
            this.assertionId = assertionId;
            this.kind = kind;
            this.expected = expected;
            this.actual = actual;
            this.delta = delta;
            this.purpose = purpose;
            this.intent = intent;
            this.afterStatementId = afterStatementId;
            this.candidateId = candidateId;
            this.site = site == null ? AssertionSite.END_OF_TEST : site;
            this.exceptionId = exceptionId;
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

        public String getAfterStatementId() {
            return afterStatementId;
        }

        public String getCandidateId() {
            return candidateId;
        }

        public AssertionSite getSite() {
            return site;
        }

        public String getExceptionId() {
            return exceptionId;
        }

        public String getSource() {
            return candidateId == null ? "SYNTHESIZED" : "SELECTED_CANDIDATE";
        }
    }
}
