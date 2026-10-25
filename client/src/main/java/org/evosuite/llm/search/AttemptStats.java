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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-goal-method attempt accumulator. The extractor walks each chromosome's
 * statements and records the outcomes here, plus in per-context, per-signature,
 * and per-upstream sub-buckets for sharper barrier evidence.
 */
final class AttemptStats extends BarrierCounts {
    int coveredInFailingTests;
    final Map<String, BarrierCounts> contextStats = new LinkedHashMap<>();
    final Map<String, BarrierCounts> signatureStats = new LinkedHashMap<>();
    final Map<String, BarrierCounts> upstreamStats = new LinkedHashMap<>();

    AttemptStats() {
        super("", "");
    }

    BarrierCounts contextStats(InvocationContextClassifier.InvocationContext context) {
        InvocationContextClassifier.InvocationContext effective = context == null
                ? InvocationContextClassifier.DEFAULT_CONTEXT
                : context;
        return contextStats.computeIfAbsent(effective.getKey(),
                ignored -> new BarrierCounts(effective.getKey(), effective.getLabel()));
    }

    BarrierCounts signatureStats(String signatureKey, String displayLabel) {
        String key = signatureKey == null || signatureKey.isEmpty() ? "" : signatureKey;
        return signatureStats.computeIfAbsent(key, ignored -> new BarrierCounts(key, displayLabel));
    }

    BarrierCounts upstreamStats(String blockerExecutionKey, String blockerDisplayLabel) {
        if (blockerExecutionKey == null || blockerExecutionKey.isEmpty()) {
            return new BarrierCounts("", blockerDisplayLabel);
        }
        return upstreamStats.computeIfAbsent(blockerExecutionKey,
                ignored -> new BarrierCounts(blockerExecutionKey, blockerDisplayLabel));
    }

    /**
     * Adds another observation window, including its sub-buckets.
     */
    void addAll(AttemptStats other) {
        add(other);
        coveredInFailingTests += other.coveredInFailingTests;
        addBuckets(contextStats, other.contextStats);
        addBuckets(signatureStats, other.signatureStats);
        addBuckets(upstreamStats, other.upstreamStats);
    }

    private static void addBuckets(Map<String, BarrierCounts> target, Map<String, BarrierCounts> source) {
        for (BarrierCounts bucket : source.values()) {
            target.computeIfAbsent(bucket.key, ignored -> new BarrierCounts(bucket.key, bucket.label)).add(bucket);
        }
    }
}
