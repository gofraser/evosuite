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
package org.evosuite.llm.prompt;

import org.evosuite.llm.search.ProblemCard;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.Statement;

import java.util.List;

/**
 * Renders the detail lines a prompt attaches to one thing the model should
 * act on: evidence, concrete examples (exemplar tests) and the suggested
 * action. Diagnostic problem cards and operator targets share this block, so
 * the two prompt shapes describe a goal the same way and the wording lives
 * in one place.
 */
public final class TargetSection {

    static final String DETAIL_PREFIX = "   - ";
    static final String SUGGESTED_ACTION_PREFIX = "Suggested action: ";
    static final String SEE_EXISTING_TEST_FORMAT = ": see Existing test #%d.";
    static final String INLINE_EXAMPLE_FORMAT = " (inline, first %d statements):";
    static final String TRUNCATION_MARKER = "// ... (truncated)";
    static final int INLINE_EXAMPLE_STATEMENTS = 8;

    private TargetSection() {}

    /**
     * Appends the detail lines: up to {@code maxEvidence} evidence lines, every
     * concrete example (as a reference to an existing test when it is one of
     * {@code existingTests}, otherwise inlined and truncated), and the action
     * hint when non-empty.
     */
    public static void appendDetails(StringBuilder sb,
                                     List<String> evidence, int maxEvidence,
                                     List<ProblemCard.ConcreteExample> examples,
                                     List<TestCase> existingTests,
                                     String actionHint) {
        int emitted = 0;
        for (String line : evidence) {
            if (emitted++ >= maxEvidence) {
                break;
            }
            sb.append(DETAIL_PREFIX).append(line).append('\n');
        }
        for (ProblemCard.ConcreteExample example : examples) {
            appendExample(sb, example, existingTests);
        }
        if (actionHint != null && !actionHint.isEmpty()) {
            sb.append(DETAIL_PREFIX).append(SUGGESTED_ACTION_PREFIX).append(actionHint).append('\n');
        }
    }

    private static void appendExample(StringBuilder sb, ProblemCard.ConcreteExample example,
                                      List<TestCase> existingTests) {
        int existingIndex = indexOfTest(existingTests, example.getTestCase());
        sb.append(DETAIL_PREFIX).append(example.getDescription());
        if (existingIndex >= 0) {
            sb.append(String.format(SEE_EXISTING_TEST_FORMAT, existingIndex + 1)).append('\n');
            return;
        }
        sb.append(String.format(INLINE_EXAMPLE_FORMAT, INLINE_EXAMPLE_STATEMENTS)).append('\n')
                .append("```java\n")
                .append(renderStatements(example.getTestCase(), INLINE_EXAMPLE_STATEMENTS))
                .append("\n```\n");
    }

    /** Position of {@code target} in {@code tests} (by identity, then by whitespace-normalised code), or -1. */
    public static int indexOfTest(List<TestCase> tests, TestCase target) {
        if (tests == null || target == null) {
            return -1;
        }
        String targetCode = canonicalCode(target);
        for (int i = 0; i < tests.size(); i++) {
            TestCase candidate = tests.get(i);
            if (candidate == target || canonicalCode(candidate).equals(targetCode)) {
                return i;
            }
        }
        return -1;
    }

    private static String renderStatements(TestCase test, int maxStatements) {
        if (test == null || maxStatements <= 0) {
            return "";
        }
        StringBuilder code = new StringBuilder();
        int count = Math.min(test.size(), maxStatements);
        for (int i = 0; i < count; i++) {
            Statement statement = test.getStatement(i);
            if (statement != null) {
                code.append(statement.getCode()).append('\n');
            }
        }
        if (test.size() > count) {
            code.append(TRUNCATION_MARKER);
        }
        return code.toString().trim();
    }

    /** Whitespace-normalised code used to recognise the same test across copies; falls back to identity when rendering fails. */
    public static String canonicalCode(TestCase test) {
        if (test == null) {
            return "";
        }
        try {
            return test.toCode().replaceAll("\\s+", " ").trim();
        } catch (RuntimeException e) {
            return "id_" + System.identityHashCode(test);
        }
    }
}
