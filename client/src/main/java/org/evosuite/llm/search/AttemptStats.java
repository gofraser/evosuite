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
 * Per-goal-method attempt accumulator. The extractor walks each chromosome's
 * statements and increments the relevant counters here, plus per-context,
 * per-signature, and per-upstream sub-buckets for sharper barrier evidence.
 */
final class AttemptStats {
    int attempts;
    int successes;
    int exceptions;
    int coveredInFailingTests;
    final Map<String, Integer> exceptionTypeCounts = new HashMap<>();
    final Map<String, Integer> failingInvocationLabelCounts = new LinkedHashMap<>();
    final Map<String, ContextAttemptStats> contextStats = new LinkedHashMap<>();
    final Map<String, SignatureAttemptStats> signatureStats = new LinkedHashMap<>();
    final Map<String, UpstreamAttemptStats> upstreamStats = new LinkedHashMap<>();
    int failingInvocationsWithSuccessfulPrefix;

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

    ContextAttemptStats contextStats(InvocationContextClassifier.InvocationContext context) {
        InvocationContextClassifier.InvocationContext effective = context == null
                ? InvocationContextClassifier.DEFAULT_CONTEXT
                : context;
        return contextStats.computeIfAbsent(effective.getKey(),
                ignored -> new ContextAttemptStats(effective.getKey(), effective.getLabel()));
    }

    SignatureAttemptStats signatureStats(String signatureKey, String displayLabel) {
        String key = signatureKey == null || signatureKey.isEmpty() ? "" : signatureKey;
        return signatureStats.computeIfAbsent(key,
                ignored -> new SignatureAttemptStats(key, displayLabel));
    }

    UpstreamAttemptStats upstreamStats(String blockerExecutionKey, String blockerDisplayLabel) {
        if (blockerExecutionKey == null || blockerExecutionKey.isEmpty()) {
            return new UpstreamAttemptStats("", blockerDisplayLabel);
        }
        return upstreamStats.computeIfAbsent(blockerExecutionKey,
                ignored -> new UpstreamAttemptStats(blockerExecutionKey, blockerDisplayLabel));
    }
}
