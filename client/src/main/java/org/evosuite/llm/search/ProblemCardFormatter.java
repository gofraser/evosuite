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

import java.util.List;

/**
 * Renders problem cards into compact prompt text.
 */
public final class ProblemCardFormatter {

    private ProblemCardFormatter() {}

    public static String format(List<ProblemCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return "No ranked problem cards available.";
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
                    .append(String.format("%.3f", card.getPriority()))
                    .append(", goals=")
                    .append(card.getRelatedGoals().size())
                    .append(")\n");
            for (String evidence : card.getEvidence()) {
                sb.append("   - ").append(evidence).append('\n');
            }
            String actionHint = actionHint(card.getType());
            if (!actionHint.isEmpty()) {
                sb.append("   - Suggested action: ").append(actionHint).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static String actionHint(ProblemCardType type) {
        if (type == null) {
            return "";
        }
        switch (type) {
            case UNREACHED_METHOD:
                return "Reuse a working acquisition/setup prefix and append a direct call to the target method; "
                        + "focus on reaching it and skip assertions.";
            case BRANCH_POLARITY_GAP:
                return "Reuse a working prefix, then vary one input/state axis at a time to flip the target "
                        + "predicate outcome. Prefer small orthogonal variants; assertions are unnecessary.";
            case STATE_DIVERSIFICATION_GAP:
                return "Keep the working call sequence, then explore orthogonal regimes by changing exactly one of: "
                        + "inputs, receiver state, or dependency shape. Avoid near-duplicate variants; assertions "
                        + "are unnecessary.";
            case UNINSTANTIABLE_TYPE:
                return "Try alternate object acquisition paths (different constructor args, "
                        + "factory/builder methods, or intermediate dependency creation), and aim to obtain "
                        + "one usable instance that can survive long enough for the next step.";
            case STATE_SETUP_BARRIER:
                return "Construct the object first, then drive a valid setup/lifecycle sequence "
                        + "before invoking target behavior.";
            case INDIRECT_REACHABILITY_BARRIER:
                return "Drive the outer entrypoint workflow or reuse any working outer workflow/setup prefix "
                        + "that gets close to the target type, then append the missing direct target invocation; "
                        + "assertions are unnecessary.";
            case EXCEPTION_BARRIER:
                return "Avoid previously failing call patterns and try guarded preconditions "
                        + "or alternate call order.";
            default:
                return "";
        }
    }
}
