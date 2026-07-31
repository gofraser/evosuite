/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 */
package org.evosuite.llm.postprocess;

import org.evosuite.Properties;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testsuite.MinimizationResult;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Suite-level scheduler and aggregator for unified post-processing.
 * Per-test model interaction and validation remain behind the processor
 * callback until the next extraction step.
 */
final class LlmPostProcessingPhase {

    private static final Logger logger = LoggerFactory.getLogger(LlmPostProcessingPhase.class);

    private final LlmPostProcessor processor;
    private final LlmTestPostProcessor testProcessor;

    LlmPostProcessingPhase(LlmPostProcessor processor) {
        this.processor = processor;
        this.testProcessor = new LlmTestPostProcessor(processor);
    }

    Result run(TestSuiteChromosome suite, MinimizationResult minimizationResult,
               PostProcessingOptions options) {
        boolean limitedIncompleteMinimization = minimizationResult.isIncomplete()
                && options.phaseBudget().incompletePolicy()
                == Properties.LlmPostProcessingOnIncompleteMinimization.LIMITED;
        LlmPostProcessor.ProcessingLimits limits = LlmPostProcessor.ProcessingLimits.from(
                options.phaseBudget(), minimizationResult.isIncomplete());
        int requestedTests = 0;
        int requestedCalls = 0;
        int requestedStatements = 0;
        int initialCalls = 0;
        int repairCalls = 0;
        int repairCallsSkippedBudget = 0;
        int acceptedTests = 0;
        int infrastructureFailures = 0;
        int processedTests = 0;
        int partiallyProcessedTests = 0;
        int skippedTests = 0;
        int capSkippedTests = 0;
        LlmPostProcessor.DiagnosticCounters diagnosticCounters =
                new LlmPostProcessor.DiagnosticCounters();
        LlmPostProcessor.FallbackCounters fallbackCounters =
                new LlmPostProcessor.FallbackCounters();
        int testNamesProposed = 0;
        int testNamesApplied = 0;
        int variableNamesProposed = 0;
        int variableNamesApplied = 0;
        int commentsProposed = 0;
        int commentsApplied = 0;
        int sectionBreaksProposed = 0;
        int sectionBreaksApplied = 0;
        int assertionsProposed = 0;
        int assertionsAcceptedInitial = 0;
        int assertionsRepairRequested = 0;
        int assertionsProposedAfterRepair = 0;
        int assertionsAcceptedAfterRepair = 0;
        int assertionsApplied = 0;
        StopReason stopReason = StopReason.NONE;
        long phaseStartMillis = processor.phaseStartMillis();

        List<TestChromosome> tests = suite.getTestChromosomes();
        List<LlmPostProcessor.WorkItem> workItems = LlmPostProcessor.WorkItem.from(
                tests, limitedIncompleteMinimization);
        for (int workIndex = 0; workIndex < workItems.size(); workIndex++) {
            if (processor.isPhaseTimedOut(phaseStartMillis)) {
                stopReason = StopReason.TIMEOUT;
                capSkippedTests += LlmPostProcessor.remainingItems(workItems, workIndex);
                break;
            }
            if (limits.maxTests > 0 && requestedTests >= limits.maxTests) {
                stopReason = StopReason.MAX_TESTS;
                capSkippedTests += LlmPostProcessor.remainingItems(workItems, workIndex);
                break;
            }
            if (limits.maxCalls > 0 && requestedCalls >= limits.maxCalls) {
                stopReason = StopReason.MAX_CALLS;
                capSkippedTests += LlmPostProcessor.remainingItems(workItems, workIndex);
                break;
            }
            LlmPostProcessor.WorkItem workItem = workItems.get(workIndex);
            int testIndex = workItem.originalIndex;
            TestChromosome chromosome = workItem.chromosome;
            if (chromosome == null || chromosome.getTestCase() == null) {
                skippedTests++;
                continue;
            }
            TestCase test = chromosome.getTestCase();
            if (processor.isLowMemory()) {
                logger.info("Unified LLM post-processing stopped before test {}: low memory", testIndex);
                stopReason = StopReason.LOW_MEMORY;
                capSkippedTests += LlmPostProcessor.remainingItems(workItems, workIndex);
                break;
            }
            if (limits.maxTotalStatements > 0 && requestedStatements + test.size()
                    > limits.maxTotalStatements) {
                stopReason = StopReason.MAX_TOTAL_STATEMENTS;
                capSkippedTests += LlmPostProcessor.remainingItems(workItems, workIndex);
                break;
            }
            boolean assertionEligible = processor.isAssertionEligibleForVersion1(chromosome);
            if (test.size() == 0 && !options.features().testNames()) {
                skippedTests++;
                continue;
            }
            if (!processor.hasLlmBudget()) {
                logger.info("Unified LLM post-processing stopped: LLM call budget exhausted");
                stopReason = StopReason.BUDGET_EXHAUSTED;
                capSkippedTests += LlmPostProcessor.remainingItems(workItems, workIndex);
                break;
            }
            if (!processor.canStartAnotherLlmCall(phaseStartMillis)) {
                stopReason = StopReason.TIMEOUT;
                capSkippedTests += LlmPostProcessor.remainingItems(workItems, workIndex);
                break;
            }

            LlmPostProcessor.TestProcessingResult result = testProcessor.process(
                    workItem, minimizationResult, limits, requestedCalls, phaseStartMillis,
                    assertionEligible);
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
            if (result.stopReason != StopReason.NONE) {
                stopReason = result.stopReason;
                capSkippedTests += LlmPostProcessor.remainingItems(workItems, workIndex + 1);
                break;
            }
        }

        logger.info("Unified LLM post-processing requested {} test(s), accepted {} response(s): testNames={}, "
                        + "variableNames={}, comments={}, sectionBreaks={}, assertions={}",
                requestedTests, acceptedTests, testNamesApplied, variableNamesApplied,
                commentsApplied, sectionBreaksApplied, assertionsApplied);
        LlmPostProcessor.PostProcessingMetrics metrics =
                new LlmPostProcessor.PostProcessingMetrics(stopReason.value);
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
        return new Result(metrics, assertionsApplied);
    }

    static final class Result {
        final LlmPostProcessor.PostProcessingMetrics metrics;
        final int assertionsApplied;

        private Result(LlmPostProcessor.PostProcessingMetrics metrics, int assertionsApplied) {
            this.metrics = metrics;
            this.assertionsApplied = assertionsApplied;
        }
    }

    enum StopReason {
        NONE(""),
        TIMEOUT("timeout"),
        MAX_TESTS("max_tests"),
        MAX_CALLS("max_calls"),
        MAX_TOTAL_STATEMENTS("max_total_statements"),
        LOW_MEMORY("low_memory"),
        BUDGET_EXHAUSTED("budget_exhausted");

        final String value;

        StopReason(String value) {
            this.value = value;
        }
    }
}
