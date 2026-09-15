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

import org.evosuite.llm.prompt.TargetSection;
import org.evosuite.testcase.TestCase;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Renders problem cards into compact prompt text; the detail lines under each
 * card come from {@link TargetSection}, shared with operator targets.
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
            TargetSection.appendDetails(sb, card.getEvidence(), Integer.MAX_VALUE,
                    card.getConcreteExamples(), existingTests,
                    card.getType() == null ? "" : card.getType().getActionHint());
        }
        return sb.toString().trim();
    }
}
