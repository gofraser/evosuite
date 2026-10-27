/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 */
package org.evosuite.llm.postprocess;

import org.evosuite.Properties;
import org.evosuite.llm.LlmBudgetExceededException;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testsuite.MinimizationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * Owns the request and validation workflow for one scheduled test.  The
 * enclosing processor supplies shared safety helpers and the phase snapshot;
 * this class returns only a per-test delta for suite aggregation.
 */
final class LlmTestPostProcessor {

    private static final Logger logger = LoggerFactory.getLogger(LlmTestPostProcessor.class);

    private final LlmPostProcessor processor;
    private final LlmPostProcessingPhaseContext phaseContext;

    LlmTestPostProcessor(LlmPostProcessor processor,
                         LlmPostProcessingPhaseContext phaseContext) {
        this.processor = processor;
        this.phaseContext = phaseContext;
    }

    LlmPostProcessor.TestProcessingResult process(LlmPostProcessor.WorkItem workItem,
                                                 MinimizationResult minimizationResult,
                                                 LlmPostProcessor.ProcessingLimits limits,
                                                 int requestedCallsBeforeTest,
                                                 boolean assertionEligible) {
        LlmPostProcessor.TestProcessingResult result = new LlmPostProcessor.TestProcessingResult();
        int testIndex = workItem.originalIndex;
        TestChromosome chromosome = workItem.chromosome;
        TestCase test = chromosome.getTestCase();
        phaseContext.recordAttemptedTest(test);
        PostProcessingOptions options = phaseContext.options();
        try {
            boolean repairSkippedForBudget = false;
            boolean collectAssertionContext = options.features().assertions() && assertionEligible;
            org.evosuite.assertion.CompleteAssertionGenerator.CandidateCollection candidateCollection =
                    collectAssertionContext ? processor.assertionCandidateRunner.collectCandidates(test) : null;
            LlmPostProcessor.detachCandidateAssertions(candidateCollection);
            TestCase validationTest = candidateCollection == null ? test : candidateCollection.getTest();
            ExecutionResult contextExecutionResult = candidateCollection == null
                    ? chromosome.getLastExecutionResult()
                    : candidateCollection.getExecutionResult();
            OracleContext oracleContext = OracleContext.from(
                    validationTest, contextExecutionResult,
                    candidateCollection == null ? Collections.emptyList()
                            : candidateCollection.getAssertions(), options);
            assertionEligible = assertionEligible && LlmPostProcessor.hasAssertionOpportunity(oracleContext);
            if (Properties.LlmPostProcessingScope.ASSERTION_ELIGIBLE_TESTS
                    == options.phaseBudget().scope()
                    && !assertionEligible) {
                result.set(PostProcessingCounter.SKIPPED_TESTS, 1);
                return result;
            }
            boolean assertionsEnabledForTest = options.features().assertions() && assertionEligible;
            PromptResult prompt = PostProcessingPromptRenderer.build(oracleContext,
                    assertionsEnabledForTest, options);
            result.set(PostProcessingCounter.REQUESTED_TESTS, 1);
            result.set(PostProcessingCounter.REQUESTED_CALLS, 1);
            result.set(PostProcessingCounter.INITIAL_CALLS, 1);
            result.set(PostProcessingCounter.REQUESTED_STATEMENTS, test.size());
            String rawResponse = processor.queryWithPostProcessingTraceContext(phaseContext, prompt,
                    testIndex, minimizationResult, 1);
            LlmPostProcessingParseResult parseResult = LlmPostProcessingResponseParser.parse(
                    rawResponse, oracleContext.toParseContext());
            if (parseResult.isInfrastructureFailure()) {
                logger.warn("Unified LLM post-processing ignored response for test {}: {}",
                        testIndex, parseResult.getInfrastructureFailureReason());
                result.set(PostProcessingCounter.INFRASTRUCTURE_FAILURES, 1);
                if (options.features().assertions()) {
                    phaseContext.recordFailedAssertionTest(test);
                }
                result.fallbackCounters.add(processor.runAssertionFallback(test, assertionEligible,
                        LlmPostProcessor.FallbackTrigger.INFRASTRUCTURE_FAILURE, options));
                return result;
            }
            result.diagnosticCounters.add(parseResult);
            LlmPostProcessingResponse response = parseResult.getResponse();
            processor.recordAssertionDiagnostics(parseResult.getDiagnostics(), response,
                    parseResult.getRawAssertions(), testIndex, minimizationResult, "initial", "parse");
            PostProcessingCounts proposedCounts = parseResult.getProposedCounts();
            result.set(PostProcessingCounter.TEST_NAMES_PROPOSED, proposedCounts.getTestNames());
            result.set(PostProcessingCounter.VARIABLE_NAMES_PROPOSED, proposedCounts.getVariableNames());
            result.set(PostProcessingCounter.COMMENTS_PROPOSED, proposedCounts.getComments());
            result.set(PostProcessingCounter.SECTION_BREAKS_PROPOSED, proposedCounts.getSectionBreaks());
            result.set(PostProcessingCounter.ASSERTIONS_PROPOSED, proposedCounts.getAssertions());
            if (assertionsEnabledForTest) {
                List<LlmPostProcessingParseResult.Diagnostic> initialValidationDiagnostics =
                        Collections.emptyList();
                LlmPostProcessingPhase.StopReason stopReason = LlmPostProcessingPhase.StopReason.NONE;
                if (processor.isLowMemory(phaseContext)) {
                    response = response.withoutAssertions();
                    stopReason = LlmPostProcessingPhase.StopReason.LOW_MEMORY;
                } else if (!response.getAssertions().isEmpty()) {
                    ValidatedEditPlan validationResult =
                            processor.validateAssertionsAgainstScopes(phaseContext, response, validationTest,
                                    contextExecutionResult, null, null);
                    processor.recordAssertionDiagnostics(validationResult.getDiagnostics(), response,
                            parseResult.getRawAssertions(), testIndex, minimizationResult,
                            "initial", "validation");
                    response = validationResult.getResponse();
                    initialValidationDiagnostics = validationResult.getDiagnostics();
                    result.diagnosticCounters.add(validationResult.getDiagnostics());
                }
                if (stopReason == LlmPostProcessingPhase.StopReason.NONE
                        && processor.isLowMemory(phaseContext)) {
                    stopReason = LlmPostProcessingPhase.StopReason.LOW_MEMORY;
                }
                if (stopReason == LlmPostProcessingPhase.StopReason.NONE
                        && phaseContext.isPhaseTimedOut()) {
                    stopReason = LlmPostProcessingPhase.StopReason.TIMEOUT;
                }
                if (stopReason == LlmPostProcessingPhase.StopReason.NONE) {
                    result.set(PostProcessingCounter.ASSERTIONS_ACCEPTED_INITIAL, response.getAssertions().size());
                    LlmPostProcessor.AssertionRepairResult repairResult =
                            processor.repairRejectedAssertionsIfPossible(phaseContext, parseResult,
                                    response, initialValidationDiagnostics, oracleContext, validationTest,
                                    contextExecutionResult, limits,
                                    requestedCallsBeforeTest
                                            + result.get(PostProcessingCounter.REQUESTED_CALLS), testIndex,
                                    minimizationResult,
                                    options);
                    processor.recordAssertionDiagnostics(repairResult.diagnostics, repairResult.response,
                            repairResult.rawAssertions,
                            testIndex, minimizationResult, "repair", "validation");
                    response = repairResult.response;
                    result.add(PostProcessingCounter.REQUESTED_CALLS, repairResult.calls);
                    result.set(PostProcessingCounter.REPAIR_CALLS, repairResult.calls);
                    result.set(PostProcessingCounter.REPAIR_CALLS_SKIPPED_BUDGET, repairResult.callsSkippedBudget);
                    repairSkippedForBudget = repairResult.callsSkippedBudget > 0;
                    result.set(PostProcessingCounter.ASSERTIONS_REPAIR_REQUESTED, repairResult.assertionsRequested);
                    result.set(PostProcessingCounter.ASSERTIONS_PROPOSED_AFTER_REPAIR, repairResult.assertionsProposed);
                    result.set(PostProcessingCounter.ASSERTIONS_ACCEPTED_AFTER_REPAIR, repairResult.assertionsAccepted);
                    result.diagnosticCounters.add(repairResult.diagnostics);
                } else {
                    result.set(PostProcessingCounter.ASSERTIONS_ACCEPTED_INITIAL, response.getAssertions().size());
                }
                result.stopReason = stopReason;
            } else if (!response.getAssertions().isEmpty()) {
                response = response.withoutAssertions();
            }
            processor.recordAssertionLifecycle(response.getAssertions(), testIndex,
                    minimizationResult, "accepted_final", "final");
            LlmPostProcessingEditApplier.ApplyResult applied = LlmPostProcessingEditApplier.apply(
                    test, oracleContext.getReferences(), response, assertionEligible, contextExecutionResult,
                    options);
            result.set(PostProcessingCounter.TEST_NAMES_APPLIED, applied.getTestNamesApplied());
            result.set(PostProcessingCounter.VARIABLE_NAMES_APPLIED, applied.getVariableNamesApplied());
            result.set(PostProcessingCounter.COMMENTS_APPLIED, applied.getCommentsApplied());
            result.set(PostProcessingCounter.SECTION_BREAKS_APPLIED, applied.getSectionBreaksApplied());
            result.set(PostProcessingCounter.ASSERTIONS_APPLIED, applied.getAssertionsApplied());
            processor.recordAppliedAssertionLifecycle(phaseContext, response, applied.getAppliedAssertions(), testIndex,
                    minimizationResult);
            if (result.stopReason == LlmPostProcessingPhase.StopReason.LOW_MEMORY
                    || processor.isLowMemory(phaseContext)) {
                result.stopReason = LlmPostProcessingPhase.StopReason.LOW_MEMORY;
                if (options.features().assertions() && result.get(PostProcessingCounter.ASSERTIONS_APPLIED) == 0) {
                    phaseContext.recordFailedAssertionTest(test);
                }
                result.set(PostProcessingCounter.ACCEPTED_RESPONSES, 1);
                result.set(PostProcessingCounter.PARTIALLY_PROCESSED_TESTS, 1);
                return result;
            }
            if (applied.getAssertionsApplied() == 0) {
                result.fallbackCounters.add(processor.runAssertionFallback(test, assertionEligible,
                        LlmPostProcessor.FallbackTrigger.NO_ACCEPTED_ASSERTIONS, options));
            }
            result.set(PostProcessingCounter.ACCEPTED_RESPONSES, 1);
            if (repairSkippedForBudget) {
                result.set(PostProcessingCounter.PARTIALLY_PROCESSED_TESTS, 1);
            } else {
                result.set(PostProcessingCounter.PROCESSED_TESTS, 1);
            }
            if (result.stopReason == LlmPostProcessingPhase.StopReason.TIMEOUT) {
                if (options.features().assertions() && result.get(PostProcessingCounter.ASSERTIONS_APPLIED) == 0) {
                    phaseContext.recordFailedAssertionTest(test);
                }
                return result;
            }
            if (!parseResult.getDiagnostics().isEmpty()) {
                logger.debug("Unified LLM post-processing accepted test {} with {} diagnostic(s)",
                        testIndex, parseResult.getDiagnostics().size());
            }
            result.stopReason = LlmPostProcessingPhase.StopReason.NONE;
            return result;
        } catch (LlmBudgetExceededException e) {
            logger.info("Unified LLM post-processing stopped: {}", e.getMessage());
            result.stopReason = LlmPostProcessingPhase.StopReason.BUDGET_EXHAUSTED;
            if (options.features().assertions()) {
                phaseContext.recordFailedAssertionTest(test);
            }
            return result;
        } catch (RuntimeException | AssertionError e) {
            if (LlmPostProcessor.isInterruption(e)) {
                // An interrupt is a request to stop, not a provider failure:
                // do not run the fallback and let the phase end here.
                logger.info("Unified LLM post-processing stopped at test {}: thread interrupted", testIndex);
                result.stopReason = LlmPostProcessingPhase.StopReason.INTERRUPTED;
                if (options.features().assertions()) {
                    phaseContext.recordFailedAssertionTest(test);
                }
                return result;
            }
            logger.warn("Unified LLM post-processing skipped test {} after per-test failure: {}",
                    testIndex, e.getMessage());
            result.set(PostProcessingCounter.INFRASTRUCTURE_FAILURES, 1);
            if (options.features().assertions()) {
                phaseContext.recordFailedAssertionTest(test);
            }
            result.fallbackCounters.add(processor.runAssertionFallback(test, assertionEligible,
                    LlmPostProcessor.FallbackTrigger.INFRASTRUCTURE_FAILURE, options));
            return result;
        }
    }
}
