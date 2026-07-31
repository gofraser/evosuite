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
 * Aggregates per-test deltas and creates the phase-level metric snapshot.
 * Budget counters live here as well so the suite loop does not duplicate the
 * same field-by-field accounting logic.
 */
final class PostProcessingMetricsAccumulator {

    private int requestedTests;
    private int requestedCalls;
    private int requestedStatements;
    private int initialCalls;
    private int repairCalls;
    private int repairCallsSkippedBudget;
    private int acceptedTests;
    private int infrastructureFailures;
    private int skippedTests;
    private int capSkippedTests;
    private int processedTests;
    private int partiallyProcessedTests;
    private int testNamesProposed;
    private int testNamesApplied;
    private int variableNamesProposed;
    private int variableNamesApplied;
    private int commentsProposed;
    private int commentsApplied;
    private int sectionBreaksProposed;
    private int sectionBreaksApplied;
    private int assertionsProposed;
    private int assertionsAcceptedInitial;
    private int assertionsRepairRequested;
    private int assertionsProposedAfterRepair;
    private int assertionsAcceptedAfterRepair;
    private int assertionsApplied;
    private final LlmPostProcessor.DiagnosticCounters diagnosticCounters =
            new LlmPostProcessor.DiagnosticCounters();
    private final LlmPostProcessor.FallbackCounters fallbackCounters =
            new LlmPostProcessor.FallbackCounters();

    void add(LlmPostProcessor.TestProcessingResult result) {
        if (result == null) {
            return;
        }
        requestedTests += result.requestedTests;
        requestedCalls += result.calls;
        requestedStatements += result.requestedStatements;
        initialCalls += result.initialCalls;
        repairCalls += result.repairCalls;
        repairCallsSkippedBudget += result.repairCallsSkippedBudget;
        acceptedTests += result.acceptedTests;
        skippedTests += result.skippedTests;
        infrastructureFailures += result.infrastructureFailures;
        diagnosticCounters.add(result.diagnosticCounters);
        fallbackCounters.add(result.fallbackCounters);
        processedTests += result.processedTests;
        partiallyProcessedTests += result.partiallyProcessedTests;
        testNamesProposed += result.testNamesProposed;
        testNamesApplied += result.testNamesApplied;
        variableNamesProposed += result.variableNamesProposed;
        variableNamesApplied += result.variableNamesApplied;
        commentsProposed += result.commentsProposed;
        commentsApplied += result.commentsApplied;
        sectionBreaksProposed += result.sectionBreaksProposed;
        sectionBreaksApplied += result.sectionBreaksApplied;
        assertionsProposed += result.assertionsProposed;
        assertionsAcceptedInitial += result.assertionsAcceptedInitial;
        assertionsRepairRequested += result.assertionsRepairRequested;
        assertionsProposedAfterRepair += result.assertionsProposedAfterRepair;
        assertionsAcceptedAfterRepair += result.assertionsAcceptedAfterRepair;
        assertionsApplied += result.assertionsApplied;
    }

    void addCapSkippedTests(int count) {
        capSkippedTests += Math.max(0, count);
    }

    void addSkippedTest() {
        skippedTests++;
    }

    int requestedTests() {
        return requestedTests;
    }

    int requestedCalls() {
        return requestedCalls;
    }

    int requestedStatements() {
        return requestedStatements;
    }

    int acceptedTests() {
        return acceptedTests;
    }

    int testNamesApplied() {
        return testNamesApplied;
    }

    int variableNamesApplied() {
        return variableNamesApplied;
    }

    int commentsApplied() {
        return commentsApplied;
    }

    int sectionBreaksApplied() {
        return sectionBreaksApplied;
    }

    int assertionsApplied() {
        return assertionsApplied;
    }

    LlmPostProcessor.PostProcessingMetrics finish(String stopReason) {
        LlmPostProcessor.PostProcessingMetrics metrics =
                new LlmPostProcessor.PostProcessingMetrics(stopReason);
        metrics.requestedTests = requestedTests;
        metrics.requestedStatements = requestedStatements;
        metrics.initialCalls = initialCalls;
        metrics.repairCalls = repairCalls;
        metrics.repairCallsSkippedBudget = repairCallsSkippedBudget;
        metrics.acceptedResponses = acceptedTests;
        metrics.skippedTests = skippedTests;
        metrics.capSkippedTests = capSkippedTests;
        metrics.infrastructureFailures = infrastructureFailures;
        metrics.diagnosticCounters = diagnosticCounters;
        metrics.fallbackCounters = fallbackCounters;
        metrics.processedTests = processedTests;
        metrics.partiallyProcessedTests = partiallyProcessedTests;
        metrics.testNamesProposed = testNamesProposed;
        metrics.testNamesApplied = testNamesApplied;
        metrics.variableNamesProposed = variableNamesProposed;
        metrics.variableNamesApplied = variableNamesApplied;
        metrics.commentsProposed = commentsProposed;
        metrics.commentsApplied = commentsApplied;
        metrics.sectionBreaksProposed = sectionBreaksProposed;
        metrics.sectionBreaksApplied = sectionBreaksApplied;
        metrics.assertionsProposed = assertionsProposed;
        metrics.assertionsAcceptedInitial = assertionsAcceptedInitial;
        metrics.assertionsRepairRequested = assertionsRepairRequested;
        metrics.assertionsProposedAfterRepair = assertionsProposedAfterRepair;
        metrics.assertionsAcceptedAfterRepair = assertionsAcceptedAfterRepair;
        metrics.assertionsApplied = assertionsApplied;
        return metrics;
    }
}
