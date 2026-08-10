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
 */
final class LlmPostProcessingPhase {

    private static final Logger logger = LoggerFactory.getLogger(LlmPostProcessingPhase.class);

    private final LlmPostProcessor processor;
    private final LlmPostProcessingPhaseContext phaseContext;
    private final LlmTestPostProcessor testProcessor;

    LlmPostProcessingPhase(LlmPostProcessor processor,
                           LlmPostProcessingPhaseContext phaseContext) {
        this.processor = processor;
        this.phaseContext = phaseContext;
        this.testProcessor = new LlmTestPostProcessor(processor, phaseContext);
    }

    LlmPostProcessor.PostProcessingMetrics run(TestSuiteChromosome suite,
                                               MinimizationResult minimizationResult) {
        PostProcessingOptions options = phaseContext.options();
        boolean limitedIncompleteMinimization = minimizationResult.isIncomplete()
                && options.phaseBudget().incompletePolicy()
                == Properties.LlmPostProcessingOnIncompleteMinimization.LIMITED;
        LlmPostProcessor.ProcessingLimits limits = LlmPostProcessor.ProcessingLimits.from(
                options.phaseBudget(), minimizationResult.isIncomplete());
        LlmPostProcessor.PostProcessingMetrics metrics =
                new LlmPostProcessor.PostProcessingMetrics(null);
        StopReason stopReason = StopReason.NONE;
        List<TestChromosome> tests = suite.getTestChromosomes();
        List<LlmPostProcessor.WorkItem> workItems = LlmPostProcessor.WorkItem.from(
                tests, limitedIncompleteMinimization);
        for (int workIndex = 0; workIndex < workItems.size(); workIndex++) {
            if (processor.isPhaseTimedOut(phaseContext)) {
                stopReason = StopReason.TIMEOUT;
                metrics.capSkippedTests += LlmPostProcessor.remainingItems(workItems, workIndex);
                break;
            }
            if (limits.maxTests > 0 && metrics.requestedTests >= limits.maxTests) {
                stopReason = StopReason.MAX_TESTS;
                metrics.capSkippedTests += LlmPostProcessor.remainingItems(workItems, workIndex);
                break;
            }
            if (limits.maxCalls > 0 && metrics.requestedCalls >= limits.maxCalls) {
                stopReason = StopReason.MAX_CALLS;
                metrics.capSkippedTests += LlmPostProcessor.remainingItems(workItems, workIndex);
                break;
            }
            LlmPostProcessor.WorkItem workItem = workItems.get(workIndex);
            int testIndex = workItem.originalIndex;
            TestChromosome chromosome = workItem.chromosome;
            if (chromosome == null || chromosome.getTestCase() == null) {
                metrics.skippedTests++;
                continue;
            }
            TestCase test = chromosome.getTestCase();
            if (processor.isLowMemory(phaseContext)) {
                logger.info("Unified LLM post-processing stopped before test {}: low memory", testIndex);
                stopReason = StopReason.LOW_MEMORY;
                metrics.capSkippedTests += LlmPostProcessor.remainingItems(workItems, workIndex);
                break;
            }
            if (limits.maxTotalStatements > 0 && metrics.requestedStatements + test.size()
                    > limits.maxTotalStatements) {
                stopReason = StopReason.MAX_TOTAL_STATEMENTS;
                metrics.capSkippedTests += LlmPostProcessor.remainingItems(workItems, workIndex);
                break;
            }
            boolean assertionEligible = processor.isAssertionEligible(chromosome);
            if (test.size() == 0 && !options.features().testNames()) {
                metrics.skippedTests++;
                continue;
            }
            if (!processor.hasLlmBudget()) {
                logger.info("Unified LLM post-processing stopped: LLM call budget exhausted");
                stopReason = StopReason.BUDGET_EXHAUSTED;
                metrics.capSkippedTests += LlmPostProcessor.remainingItems(workItems, workIndex);
                break;
            }
            if (!processor.canStartAnotherLlmCall(phaseContext)) {
                stopReason = StopReason.TIMEOUT;
                metrics.capSkippedTests += LlmPostProcessor.remainingItems(workItems, workIndex);
                break;
            }

            LlmPostProcessor.TestProcessingResult result = testProcessor.process(
                    workItem, minimizationResult, limits, metrics.requestedCalls,
                    assertionEligible);
            metrics.add(result);
            if (result.stopReason != StopReason.NONE) {
                stopReason = result.stopReason;
                metrics.capSkippedTests += LlmPostProcessor.remainingItems(workItems, workIndex + 1);
                break;
            }
        }

        logger.info("Unified LLM post-processing requested {} test(s), accepted {} response(s): testNames={}, "
                        + "variableNames={}, comments={}, sectionBreaks={}, assertions={}",
                metrics.requestedTests, metrics.acceptedResponses,
                metrics.testNamesApplied, metrics.variableNamesApplied,
                metrics.commentsApplied, metrics.sectionBreaksApplied,
                metrics.assertionsApplied);
        String terminalReason = stopReason.value;
        if (terminalReason.isEmpty() && metrics.infrastructureFailures > 0) {
            terminalReason = "infrastructure_failure";
        }
        return metrics.snapshot(terminalReason);
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
