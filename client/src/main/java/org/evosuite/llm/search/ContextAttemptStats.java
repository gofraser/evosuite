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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-invocation-context attempt accumulator used by the exception-barrier
 * card builder to surface "blocked in this argument context" evidence (e.g.
 * arg0=null) when method-wide rates are diluted.
 */
final class ContextAttemptStats {
    final String contextKey;
    final String contextLabel;
    int attempts;
    int successes;
    int exceptions;
    final Map<String, Integer> exceptionTypeCounts = new HashMap<>();
    final Map<String, Integer> failingInvocationLabelCounts = new LinkedHashMap<>();
    int failingInvocationsWithSuccessfulPrefix;

    ContextAttemptStats(String contextKey, String contextLabel) {
        this.contextKey = contextKey;
        this.contextLabel = contextLabel;
    }

    void recordFailingInvocation(String label, boolean hadSuccessfulPrefix) {
        if (label != null && !label.isEmpty()) {
            failingInvocationLabelCounts.put(label,
                    failingInvocationLabelCounts.getOrDefault(label, 0) + 1);
        }
        if (hadSuccessfulPrefix) {
            failingInvocationsWithSuccessfulPrefix++;
        }
    }

    String dominantFailingInvocationLabel() {
        return ProblemCardLabels.dominantLabel(failingInvocationLabelCounts);
    }
}
