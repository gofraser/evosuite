/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.llm.postprocess;

/**
 * Aggregates per-test metric deltas and creates the phase-level snapshot.
 * The shared metric value model owns the counter definitions and addition
 * logic; this class only owns suite scheduling counters and the live total.
 */
final class PostProcessingMetricsAccumulator {

    private final LlmPostProcessor.PostProcessingMetrics metrics =
            new LlmPostProcessor.PostProcessingMetrics(null);

    void add(LlmPostProcessor.TestProcessingResult result) {
        if (result == null) {
            return;
        }
        metrics.add(result);
    }

    void addCapSkippedTests(int count) {
        metrics.capSkippedTests += Math.max(0, count);
    }

    void addSkippedTest() {
        metrics.skippedTests++;
    }

    int requestedTests() {
        return metrics.requestedTests;
    }

    int requestedCalls() {
        return metrics.requestedCalls;
    }

    int requestedStatements() {
        return metrics.requestedStatements;
    }

    int acceptedTests() {
        return metrics.acceptedResponses;
    }

    int testNamesApplied() {
        return metrics.testNamesApplied;
    }

    int variableNamesApplied() {
        return metrics.variableNamesApplied;
    }

    int commentsApplied() {
        return metrics.commentsApplied;
    }

    int sectionBreaksApplied() {
        return metrics.sectionBreaksApplied;
    }

    int assertionsApplied() {
        return metrics.assertionsApplied;
    }

    LlmPostProcessor.PostProcessingMetrics finish(String stopReason) {
        return metrics.snapshot(stopReason);
    }
}
