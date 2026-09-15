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
 * Renders the block a prompt devotes to one thing the model should act on:
 * a heading, then evidence, concrete examples (exemplar tests), the suggested
 * action and finally any lines saying how to proceed (which test to start
 * from, what each parent covers). Diagnostic problem cards and operator
 * targets both render through {@link #appendBlock}, so the two prompt shapes
 * describe a goal in the same order and the wording lives in one place.
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
     * Appends one complete block: the heading line, then the detail lines in
     * their fixed order — up to {@code maxEvidence} evidence lines, the branch
     * operand hint (last-evaluation values and what the target polarity needs)
     * when non-null/non-empty, every concrete example (as a reference to an
     * existing test when it is one of {@code existingTests}, otherwise inlined
     * and truncated), the action hint when non-empty, and last the
     * {@code howToProceed} lines.
     */
    public static void appendBlock(StringBuilder sb, String heading,
                                   List<String> evidence, int maxEvidence,
                                   String operandHint,
                                   List<ProblemCard.ConcreteExample> examples,
                                   List<TestCase> existingTests,
                                   String actionHint,
                                   List<String> howToProceed) {
        sb.append(heading).append('\n');
        appendDetails(sb, evidence, maxEvidence, operandHint, examples, existingTests, actionHint);
        for (String line : howToProceed) {
            sb.append(DETAIL_PREFIX).append(line).append('\n');
        }
    }

    static void appendDetails(StringBuilder sb,
                              List<String> evidence, int maxEvidence,
                              String operandHint,
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
        if (operandHint != null && !operandHint.isEmpty()) {
            sb.append(DETAIL_PREFIX).append(operandHint).append('\n');
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
            return normaliseWhitespaceOutsideLiterals(test.toCode()).trim();
        } catch (RuntimeException e) {
            return "id_" + System.identityHashCode(test);
        }
    }

    /**
     * Collapses every run of whitespace (space, tab, CR, LF, form feed) outside
     * string ({@code "..."}) and character ({@code '...'}) literals to a single
     * space, while copying literals verbatim so that whitespace inside them
     * (e.g. {@code "a  b"} vs. {@code "a b"}) is preserved and keeps tests with
     * otherwise-identical code distinguishable. Backslash escapes inside a
     * literal (e.g. {@code \"}, {@code \\}, {@code \'}) are copied along with
     * the character they escape so an escaped quote does not end the literal.
     * An unterminated literal is copied verbatim to the end of the input. Text
     * blocks ({@code """..."""}) cannot appear in {@link TestCase#toCode()}
     * output at the Java 8 source level this project targets, so they are not
     * handled.
     */
    static String normaliseWhitespaceOutsideLiterals(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        int length = code.length();
        boolean lastWasSpace = false;
        while (i < length) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                i = copyLiteral(code, i, c, out);
                lastWasSpace = false;
                continue;
            }
            if (isWhitespace(c)) {
                if (!lastWasSpace) {
                    out.append(' ');
                    lastWasSpace = true;
                }
                i++;
                continue;
            }
            out.append(c);
            lastWasSpace = false;
            i++;
        }
        return out.toString();
    }

    /** Copies the literal delimited by {@code quote} starting at {@code start} verbatim into {@code out}; returns the index past its closing delimiter, or the input length when it is unterminated. */
    private static int copyLiteral(String code, int start, char quote, StringBuilder out) {
        int length = code.length();
        out.append(quote);
        int i = start + 1;
        while (i < length) {
            char c = code.charAt(i);
            out.append(c);
            if (c == '\\' && i + 1 < length) {
                out.append(code.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        return length;
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f';
    }
}
