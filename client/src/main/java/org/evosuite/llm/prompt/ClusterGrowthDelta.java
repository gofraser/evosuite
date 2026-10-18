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

import org.evosuite.setup.TestCluster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Renders a bounded, model-facing signature delta for test-cluster classes that
 * became available after a dependency summary was already sent to the model —
 * either because {@link PromptBuilder} memoised the summary into the system
 * message ({@code Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT}) before on-demand
 * cluster expansion added new classes, or because expansion happened mid-repair
 * in {@code TestRepairLoop}. Owns the single header string for this delta so it
 * exists in exactly one place, unlike the rest of the dependency summary (owned
 * by {@link TestClusterSummarizer}/{@link StaticPromptContext}).
 */
public final class ClusterGrowthDelta {

    /** Header for the signature delta; one of the two model-facing strings this class owns. */
    static final String HEADER = "Additional dependency types now available in the test cluster:";
    /** Suffix appended when the name-only fallback had to drop classes to fit the budget. */
    static final String TRUNCATION_FORMAT = " ... (+%d more)";

    // A full dependency summary can run to tens of KB; a delta describing a
    // handful of newly-added classes should stay far smaller. Cap it at a
    // quarter of the resolved dependency-summary budget, with a floor so small
    // budgets (or an unresolved/unbounded one) still get useful signatures --
    // but never above the configured budget itself when that is smaller.
    private static final int DEFAULT_BUDGET_CHARS = 4000;
    private static final int MIN_BUDGET_CHARS = 1000;
    private static final int BUDGET_DIVISOR = 4;

    private ClusterGrowthDelta() {
    }

    /**
     * Returns the header plus a budget-bounded signature summary for exactly
     * {@code addedClassNames}, or {@code null} when there is nothing to render.
     * Falls back to the header plus a sorted, comma-separated list of the class
     * names when the summarizer has nothing to say about them (e.g. all JDK
     * types or nothing source-accessible) so the model still learns they exist.
     */
    public static String render(TestClusterSummarizer summarizer, TestCluster cluster,
                                String targetClassName, Set<String> addedClassNames) {
        if (addedClassNames == null || addedClassNames.isEmpty()) {
            return null;
        }
        int deltaBudget = resolveDeltaBudget(PromptBuilder.resolveDependencySummaryBudget().budgetChars);

        String text = null;
        if (summarizer != null && cluster != null) {
            text = summarizer.summarizeDependencies(cluster, targetClassName, deltaBudget, addedClassNames)
                    .getText();
        }
        if (text == null || text.trim().isEmpty()) {
            text = joinNamesWithinBudget(new TreeSet<>(addedClassNames), deltaBudget);
        }
        return HEADER + "\n" + text;
    }

    static int resolveDeltaBudget(int summaryBudgetChars) {
        if (summaryBudgetChars <= 0) {
            return DEFAULT_BUDGET_CHARS;
        }
        return Math.min(summaryBudgetChars,
                Math.max(MIN_BUDGET_CHARS, summaryBudgetChars / BUDGET_DIVISOR));
    }

    /**
     * Comma-separates as many names as fit in {@code budgetChars}, keeping
     * room for the truncation suffix that reports how many were dropped. At
     * least one name is always emitted so the delta never degenerates to a
     * bare header.
     */
    static String joinNamesWithinBudget(Collection<String> sortedNames, int budgetChars) {
        List<String> names = new ArrayList<>(sortedNames);
        StringBuilder joined = new StringBuilder();
        int emitted = 0;
        for (String name : names) {
            int remaining = names.size() - emitted - 1;
            String suffix = remaining > 0 ? String.format(Locale.ROOT, TRUNCATION_FORMAT, remaining) : "";
            int lengthIfAdded = joined.length() + (emitted > 0 ? 2 : 0) + name.length();
            if (emitted > 0 && lengthIfAdded + suffix.length() > budgetChars) {
                break;
            }
            if (emitted > 0) {
                joined.append(", ");
            }
            joined.append(name);
            emitted++;
        }
        int dropped = names.size() - emitted;
        if (dropped > 0) {
            joined.append(String.format(Locale.ROOT, TRUNCATION_FORMAT, dropped));
        }
        return joined.toString();
    }
}
