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

    LlmPostProcessingPhase(LlmPostProcessor processor,
                           LlmPostProcessingPhaseContext phaseContext) {
        this.processor = processor;
        this.testProcessor = new LlmTestPostProcessor(processor, phaseContext);
    }

    Result run(TestSuiteChromosome suite, MinimizationResult minimizationResult,
               LlmPostProcessingPhaseContext phaseContext) {
        PostProcessingOptions options = phaseContext.options();
        boolean limitedIncompleteMinimization = minimizationResult.isIncomplete()
                && options.phaseBudget().incompletePolicy()
                == Properties.LlmPostProcessingOnIncompleteMinimization.LIMITED;
        LlmPostProcessor.ProcessingLimits limits = LlmPostProcessor.ProcessingLimits.from(
                options.phaseBudget(), minimizationResult.isIncomplete());
        PostProcessingMetricsAccumulator metricsAccumulator = new PostProcessingMetricsAccumulator();
        StopReason stopReason = StopReason.NONE;
        List<TestChromosome> tests = suite.getTestChromosomes();
        List<LlmPostProcessor.WorkItem> workItems = LlmPostProcessor.WorkItem.from(
                tests, limitedIncompleteMinimization);
        for (int workIndex = 0; workIndex < workItems.size(); workIndex++) {
            if (processor.isPhaseTimedOut(phaseContext)) {
                stopReason = StopReason.TIMEOUT;
                metricsAccumulator.addCapSkippedTests(
                        LlmPostProcessor.remainingItems(workItems, workIndex));
                break;
            }
            if (limits.maxTests > 0 && metricsAccumulator.requestedTests() >= limits.maxTests) {
                stopReason = StopReason.MAX_TESTS;
                metricsAccumulator.addCapSkippedTests(
                        LlmPostProcessor.remainingItems(workItems, workIndex));
                break;
            }
            if (limits.maxCalls > 0 && metricsAccumulator.requestedCalls() >= limits.maxCalls) {
                stopReason = StopReason.MAX_CALLS;
                metricsAccumulator.addCapSkippedTests(
                        LlmPostProcessor.remainingItems(workItems, workIndex));
                break;
            }
            LlmPostProcessor.WorkItem workItem = workItems.get(workIndex);
            int testIndex = workItem.originalIndex;
            TestChromosome chromosome = workItem.chromosome;
            if (chromosome == null || chromosome.getTestCase() == null) {
                metricsAccumulator.addSkippedTest();
                continue;
            }
            TestCase test = chromosome.getTestCase();
            if (processor.isLowMemory(phaseContext)) {
                logger.info("Unified LLM post-processing stopped before test {}: low memory", testIndex);
                stopReason = StopReason.LOW_MEMORY;
                metricsAccumulator.addCapSkippedTests(
                        LlmPostProcessor.remainingItems(workItems, workIndex));
                break;
            }
            if (limits.maxTotalStatements > 0 && metricsAccumulator.requestedStatements() + test.size()
                    > limits.maxTotalStatements) {
                stopReason = StopReason.MAX_TOTAL_STATEMENTS;
                metricsAccumulator.addCapSkippedTests(
                        LlmPostProcessor.remainingItems(workItems, workIndex));
                break;
            }
            boolean assertionEligible = processor.isAssertionEligibleForVersion1(chromosome);
            if (test.size() == 0 && !options.features().testNames()) {
                metricsAccumulator.addSkippedTest();
                continue;
            }
            if (!processor.hasLlmBudget()) {
                logger.info("Unified LLM post-processing stopped: LLM call budget exhausted");
                stopReason = StopReason.BUDGET_EXHAUSTED;
                metricsAccumulator.addCapSkippedTests(
                        LlmPostProcessor.remainingItems(workItems, workIndex));
                break;
            }
            if (!processor.canStartAnotherLlmCall(phaseContext)) {
                stopReason = StopReason.TIMEOUT;
                metricsAccumulator.addCapSkippedTests(
                        LlmPostProcessor.remainingItems(workItems, workIndex));
                break;
            }

            LlmPostProcessor.TestProcessingResult result = testProcessor.process(
                    workItem, minimizationResult, limits, metricsAccumulator.requestedCalls(),
                    assertionEligible);
            metricsAccumulator.add(result);
            if (result.stopReason != StopReason.NONE) {
                stopReason = result.stopReason;
                metricsAccumulator.addCapSkippedTests(
                        LlmPostProcessor.remainingItems(workItems, workIndex + 1));
                break;
            }
        }

        logger.info("Unified LLM post-processing requested {} test(s), accepted {} response(s): testNames={}, "
                        + "variableNames={}, comments={}, sectionBreaks={}, assertions={}",
                metricsAccumulator.requestedTests(), metricsAccumulator.acceptedTests(),
                metricsAccumulator.testNamesApplied(), metricsAccumulator.variableNamesApplied(),
                metricsAccumulator.commentsApplied(), metricsAccumulator.sectionBreaksApplied(),
                metricsAccumulator.assertionsApplied());
        return new Result(metricsAccumulator.finish(stopReason.value));
    }

    static final class Result {
        final LlmPostProcessor.PostProcessingMetrics metrics;

        private Result(LlmPostProcessor.PostProcessingMetrics metrics) {
            this.metrics = metrics;
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
