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
package org.evosuite.llm.search;

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.Statement;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Renders problem cards into compact prompt text.
 */
public final class ProblemCardFormatter {

    private ProblemCardFormatter() {}

    /**
     * Renders the given non-empty card list as prompt text. Callers
     * ({@code StagnationDetector.buildDiagnosticPrompt}) must check for an empty
     * list before calling — the diagnostic path falls back to the pool prompt
     * rather than asking the LLM to act on no cards.
     */
    public static String format(List<ProblemCard> cards) {
        return format(cards, Collections.<TestCase>emptyList());
    }

    public static String format(List<ProblemCard> cards, List<TestCase> existingTests) {
        if (cards == null) {
            throw new IllegalArgumentException("cards must not be null");
        }
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (ProblemCard card : cards) {
            if (card == null) {
                continue;
            }
            sb.append(index++)
                    .append(". [")
                    .append(card.getType().name())
                    .append("] ")
                    .append(card.getTitle())
                    .append(" (priority=")
                    .append(String.format(Locale.ROOT, "%.3f", card.getPriority()))
                    .append(", goals=")
                    .append(card.getRelatedGoals().size())
                    .append(")\n");
            for (String evidence : card.getEvidence()) {
                sb.append("   - ").append(evidence).append('\n');
            }
            appendConcreteExamples(sb, card, existingTests);
            String actionHint = card.getType() == null ? "" : card.getType().getActionHint();
            if (!actionHint.isEmpty()) {
                sb.append("   - Suggested action: ").append(actionHint).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static void appendConcreteExamples(StringBuilder sb,
                                               ProblemCard card,
                                               List<TestCase> existingTests) {
        for (ProblemCard.ConcreteExample example : card.getConcreteExamples()) {
            int existingIndex = indexOfTest(existingTests, example.getTestCase());
            if (existingIndex >= 0) {
                sb.append("   - ").append(example.getDescription())
                        .append(": see Existing test #").append(existingIndex + 1).append(".\n");
                continue;
            }
            sb.append("   - ").append(example.getDescription())
                    .append(" (inline, first 8 statements):\n")
                    .append("```java\n")
                    .append(renderStatements(example.getTestCase(), 8))
                    .append("\n```\n");
        }
    }

    private static int indexOfTest(List<TestCase> tests, TestCase target) {
        if (tests == null || target == null) {
            return -1;
        }
        String targetCode = safeCode(target);
        for (int i = 0; i < tests.size(); i++) {
            TestCase candidate = tests.get(i);
            if (candidate == target || safeCode(candidate).equals(targetCode)) {
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
            code.append("// ... (truncated)");
        }
        return code.toString().trim();
    }

    private static String safeCode(TestCase test) {
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
