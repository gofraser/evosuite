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
 * Outcome counts of the invocations of one bucket (a method, an invocation
 * context, an overload or an upstream blocker) that the exception-barrier
 * cards reason about.
 */
class BarrierCounts {
    final String key;
    String label;
    int attempts;
    int successes;
    int exceptions;
    int failingInvocationsWithSuccessfulPrefix;
    final Map<String, Integer> exceptionTypeCounts = new HashMap<>();
    final Map<String, Integer> failingInvocationLabelCounts = new LinkedHashMap<>();
    private String pinnedException;
    private String pinnedInvocationLabel;

    BarrierCounts(String key, String label) {
        this.key = key == null ? "" : key;
        this.label = label == null ? "" : label;
    }

    /**
     * Counts one invocation. A timed out invocation that did not throw is neither a
     * success nor an exception.
     */
    void record(boolean exceptionOutcome, boolean timeout, String invocationLabel,
                String exceptionKey, boolean hadSuccessfulPrefix) {
        attempts++;
        if (exceptionOutcome) {
            exceptions++;
            if (invocationLabel != null && !invocationLabel.isEmpty()) {
                failingInvocationLabelCounts.merge(invocationLabel, 1, Integer::sum);
                if (label.isEmpty()) {
                    label = invocationLabel;
                }
            }
            if (hadSuccessfulPrefix) {
                failingInvocationsWithSuccessfulPrefix++;
            }
            if (!exceptionKey.isEmpty()) {
                exceptionTypeCounts.merge(exceptionKey, 1, Integer::sum);
            }
        } else if (!timeout) {
            successes++;
        }
    }

    /**
     * Adds the counts of another observation window, including the exception and
     * invocation label histograms, so the dominant ones are those of the sum.
     */
    void add(BarrierCounts other) {
        addCounters(other);
        other.exceptionTypeCounts.forEach((name, count) -> exceptionTypeCounts.merge(name, count, Integer::sum));
        other.failingInvocationLabelCounts.forEach(
                (name, count) -> failingInvocationLabelCounts.merge(name, count, Integer::sum));
    }

    /**
     * Adds the counters of evidence from another source, such as the historical
     * window onto the current population. The dominant exception and invocation
     * label stay those of the first source that has one; the histograms are not merged.
     */
    void combine(BarrierCounts other) {
        String exception = dominantException();
        if (exception.isEmpty()) {
            exception = other.dominantException();
        }
        String invocationLabel = dominantFailingInvocationLabel();
        if (invocationLabel.isEmpty()) {
            invocationLabel = other.dominantFailingInvocationLabel();
        }
        addCounters(other);
        pinnedException = exception;
        pinnedInvocationLabel = invocationLabel;
    }

    private void addCounters(BarrierCounts other) {
        attempts += other.attempts;
        successes += other.successes;
        exceptions += other.exceptions;
        failingInvocationsWithSuccessfulPrefix += other.failingInvocationsWithSuccessfulPrefix;
        if (label.isEmpty()) {
            label = other.label;
        }
    }

    String dominantException() {
        return pinnedException != null ? pinnedException : ProblemCardLabels.dominantLabel(exceptionTypeCounts);
    }

    String dominantFailingInvocationLabel() {
        return pinnedInvocationLabel != null
                ? pinnedInvocationLabel
                : ProblemCardLabels.dominantLabel(failingInvocationLabelCounts);
    }

    double blockage() {
        return attempts <= 0 ? 0.0 : ((double) exceptions / (double) attempts);
    }

    boolean isStrongerThan(BarrierCounts other) {
        if (other == null) {
            return true;
        }
        int exceptionCompare = Integer.compare(exceptions, other.exceptions);
        if (exceptionCompare != 0) {
            return exceptionCompare > 0;
        }
        int blockageCompare = Double.compare(blockage(), other.blockage());
        if (blockageCompare != 0) {
            return blockageCompare > 0;
        }
        int attemptsCompare = Integer.compare(attempts, other.attempts);
        if (attemptsCompare != 0) {
            return attemptsCompare > 0;
        }
        return key.compareTo(other.key) < 0;
    }
}
