/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
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

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class ExtractorTelemetry {
    private final EnumMap<ExtractorRejectReason, Integer> rejectCounts =
            new EnumMap<>(ExtractorRejectReason.class);
    private final EnumMap<ExtractorCandidateMetric, Integer> candidateCounts =
            new EnumMap<>(ExtractorCandidateMetric.class);
    private final Map<String, Integer> upstreamExceptionObservations = new LinkedHashMap<>();
    private final Set<String> upstreamBlockedGoalMethods = new LinkedHashSet<>();

    void increment(ExtractorRejectReason reason) {
        if (reason == null) {
            return;
        }
        rejectCounts.put(reason, rejectCounts.getOrDefault(reason, 0) + 1);
    }

    void increment(ExtractorCandidateMetric metric) {
        if (metric == null) {
            return;
        }
        candidateCounts.put(metric, candidateCounts.getOrDefault(metric, 0) + 1);
    }

    void recordUpstreamExceptionObservation(String executionKey) {
        if (executionKey == null || executionKey.isEmpty()) {
            return;
        }
        upstreamExceptionObservations.put(executionKey,
                upstreamExceptionObservations.getOrDefault(executionKey, 0) + 1);
    }

    void recordUpstreamBlockedGoalMethods(Collection<String> blockedGoalMethods) {
        if (blockedGoalMethods == null || blockedGoalMethods.isEmpty()) {
            return;
        }
        for (String blockedGoalMethod : blockedGoalMethods) {
            if (blockedGoalMethod != null && !blockedGoalMethod.isEmpty()) {
                upstreamBlockedGoalMethods.add(blockedGoalMethod);
            }
        }
    }

    Map<ExtractorRejectReason, Integer> snapshotRejectCounts() {
        return rejectCounts.isEmpty()
                ? Collections.<ExtractorRejectReason, Integer>emptyMap()
                : new EnumMap<>(rejectCounts);
    }

    Map<ExtractorCandidateMetric, Integer> snapshotCandidateCounts() {
        return snapshotCandidateCounts(2);
    }

    Map<ExtractorCandidateMetric, Integer> snapshotCandidateCounts(int repeatedSourceThreshold) {
        EnumMap<ExtractorCandidateMetric, Integer> snapshot =
                new EnumMap<>(ExtractorCandidateMetric.class);
        snapshot.putAll(candidateCounts);
        int repeatedSources = 0;
        for (Integer observationCount : upstreamExceptionObservations.values()) {
            if (observationCount != null && observationCount >= repeatedSourceThreshold) {
                repeatedSources++;
            }
        }
        if (repeatedSources > 0) {
            snapshot.put(ExtractorCandidateMetric.UPSTREAM_EXCEPTION_REPEATED_SOURCES, repeatedSources);
        }
        if (!upstreamBlockedGoalMethods.isEmpty()) {
            snapshot.put(ExtractorCandidateMetric.UPSTREAM_EXCEPTION_BLOCKED_GOAL_METHODS,
                    upstreamBlockedGoalMethods.size());
        }
        return snapshot.isEmpty()
                ? Collections.<ExtractorCandidateMetric, Integer>emptyMap()
                : snapshot;
    }
}
