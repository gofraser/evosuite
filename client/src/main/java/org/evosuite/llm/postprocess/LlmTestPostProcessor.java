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

    LlmTestPostProcessor(LlmPostProcessor processor) {
        this.processor = processor;
    }

    LlmPostProcessor.TestProcessingResult process(LlmPostProcessor.WorkItem workItem,
                                                 MinimizationResult minimizationResult,
                                                 LlmPostProcessor.ProcessingLimits limits,
                                                 int requestedCallsBeforeTest,
                                                 long phaseStartMillis,
                                                 boolean assertionEligible) {
        LlmPostProcessor.TestProcessingResult result = new LlmPostProcessor.TestProcessingResult();
        int testIndex = workItem.originalIndex;
        TestChromosome chromosome = workItem.chromosome;
        TestCase test = chromosome.getTestCase();
        PostProcessingOptions options = processor.options;
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
            PromptVariantCapabilities capabilities = PromptVariantCapabilities.production();
            TestCase prePromptStabilityTest = null;
            ExecutionResult prePromptStabilityResult = null;
            if ((capabilities.hasStabilityLabels()
                    || capabilities.hasExceptionAdjacentPlacements()) && validationTest != null) {
                prePromptStabilityTest = validationTest.clone();
                prePromptStabilityTest.removeAssertions();
                prePromptStabilityResult = processor.stabilityExecutionRunner.execute(prePromptStabilityTest);
            }
            LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(
                    validationTest,
                    contextExecutionResult,
                    prePromptStabilityResult,
                    candidateCollection == null ? Collections.emptyList()
                            : candidateCollection.getAssertions(), options);
            assertionEligible = assertionEligible && LlmPostProcessor.hasAssertionOpportunity(context);
            if (Properties.LlmPostProcessingScope.ASSERTION_ELIGIBLE_TESTS
                    == options.phaseBudget().scope()
                    && !assertionEligible) {
                result.skippedTests = 1;
                return result;
            }
            boolean assertionsEnabledForTest = options.features().assertions() && assertionEligible;
            PromptResult prompt = LlmPostProcessingPromptBuilder.build(context, testIndex,
                    assertionsEnabledForTest, options);
            result.requestedTests = 1;
            result.calls = 1;
            result.initialCalls = 1;
            result.requestedStatements = test.size();
            String rawResponse = processor.queryWithPostProcessingTraceContext(prompt, testIndex,
                    minimizationResult, 1);
            LlmPostProcessingParseResult parseResult = LlmPostProcessingResponseParser.parse(
                    rawResponse, context.toParseContext());
            if (parseResult.isInfrastructureFailure()) {
                logger.warn("Unified LLM post-processing ignored response for test {}: {}",
                        testIndex, parseResult.getInfrastructureFailureReason());
                result.infrastructureFailures = 1;
                result.fallbackCounters.add(processor.runAssertionFallback(test, assertionEligible,
                        LlmPostProcessor.FallbackTrigger.INFRASTRUCTURE_FAILURE, options));
                return result;
            }
            result.diagnosticCounters.add(parseResult);
            DecodedPostProcessingResponse decodedResponse = parseResult.getDecodedResponse();
            LlmPostProcessingResponse response = decodedResponse == null
                    ? parseResult.getResponse() : decodedResponse.getResponse();
            processor.recordAssertionDiagnostics(parseResult.getDiagnostics(), response,
                    parseResult.getRawAssertions(), testIndex, minimizationResult, "initial", "parse");
            PostProcessingCounts proposedCounts = parseResult.getProposedCounts();
            result.testNamesProposed = proposedCounts.getTestNames();
            result.variableNamesProposed = proposedCounts.getVariableNames();
            result.commentsProposed = proposedCounts.getComments();
            result.sectionBreaksProposed = proposedCounts.getSectionBreaks();
            result.assertionsProposed = proposedCounts.getAssertions();
            if (assertionsEnabledForTest) {
                LlmPostProcessingResponse parsedResponse = response;
                List<LlmPostProcessingParseResult.Diagnostic> initialValidationDiagnostics =
                        Collections.emptyList();
                LlmPostProcessingPhase.StopReason stopReason = LlmPostProcessingPhase.StopReason.NONE;
                if (processor.resourceGuard.isLowMemory()) {
                    response = LlmPostProcessor.withoutAssertions(response);
                    stopReason = LlmPostProcessingPhase.StopReason.LOW_MEMORY;
                } else if (!response.getAssertions().isEmpty()) {
                    LlmPostProcessor.AssertionValidationResult validationResult =
                            processor.validateAssertionsAgainstScopes(response, validationTest,
                                    contextExecutionResult, prePromptStabilityTest, prePromptStabilityResult);
                    processor.recordAssertionDiagnostics(validationResult.plan.getDiagnostics(), response,
                            parseResult.getRawAssertions(), testIndex, minimizationResult,
                            "initial", "validation");
                    response = validationResult.plan.getResponse();
                    initialValidationDiagnostics = validationResult.plan.getDiagnostics();
                    result.diagnosticCounters.add(validationResult.plan.getDiagnostics());
                }
                if (stopReason == LlmPostProcessingPhase.StopReason.NONE
                        && processor.resourceGuard.isLowMemory()) {
                    stopReason = LlmPostProcessingPhase.StopReason.LOW_MEMORY;
                }
                if (stopReason == LlmPostProcessingPhase.StopReason.NONE
                        && processor.isPhaseTimedOut(phaseStartMillis)) {
                    stopReason = LlmPostProcessingPhase.StopReason.TIMEOUT;
                }
                if (stopReason == LlmPostProcessingPhase.StopReason.NONE) {
                    result.assertionsAcceptedInitial = response.getAssertions().size();
                    LlmPostProcessor.AssertionRepairResult repairResult =
                            processor.repairRejectedAssertionsIfPossible(parseResult,
                                    parsedResponse, response, initialValidationDiagnostics, context, validationTest,
                                    contextExecutionResult, limits,
                                    requestedCallsBeforeTest + result.calls, testIndex, minimizationResult,
                                    phaseStartMillis, options);
                    processor.recordAssertionDiagnostics(repairResult.diagnostics, repairResult.response,
                            repairResult.rawAssertions,
                            testIndex, minimizationResult, "repair", "validation");
                    response = repairResult.response;
                    result.calls += repairResult.calls;
                    result.repairCalls = repairResult.calls;
                    result.repairCallsSkippedBudget = repairResult.callsSkippedBudget;
                    repairSkippedForBudget = repairResult.callsSkippedBudget > 0;
                    result.assertionsRepairRequested = repairResult.assertionsRequested;
                    result.assertionsProposedAfterRepair = repairResult.assertionsProposed;
                    result.assertionsAcceptedAfterRepair = repairResult.assertionsAccepted;
                    result.diagnosticCounters.add(repairResult.diagnostics);
                } else {
                    result.assertionsAcceptedInitial = response.getAssertions().size();
                }
                result.stopReason = stopReason;
            } else if (!response.getAssertions().isEmpty()) {
                response = LlmPostProcessor.withoutAssertions(response);
            }
            processor.recordAssertionLifecycle(response.getAssertions(), testIndex,
                    minimizationResult, "accepted_final", "final");
            LlmPostProcessingEditApplier.ApplyResult applied = LlmPostProcessingEditApplier.apply(
                    test, context.getReferences(), response, assertionEligible, contextExecutionResult,
                    options);
            result.testNamesApplied = applied.getTestNamesApplied();
            result.variableNamesApplied = applied.getVariableNamesApplied();
            result.commentsApplied = applied.getCommentsApplied();
            result.sectionBreaksApplied = applied.getSectionBreaksApplied();
            result.assertionsApplied = applied.getAssertionsApplied();
            processor.recordAppliedAssertionLifecycle(response, applied.getAppliedAssertions(), testIndex,
                    minimizationResult);
            if (result.stopReason == LlmPostProcessingPhase.StopReason.LOW_MEMORY
                    || processor.resourceGuard.isLowMemory()) {
                result.stopReason = LlmPostProcessingPhase.StopReason.LOW_MEMORY;
                result.acceptedTests = 1;
                result.partiallyProcessedTests = 1;
                return result;
            }
            if (applied.getAssertionsApplied() == 0) {
                result.fallbackCounters.add(processor.runAssertionFallback(test, assertionEligible,
                        LlmPostProcessor.FallbackTrigger.NO_ACCEPTED_ASSERTIONS, options));
            }
            result.acceptedTests = 1;
            if (repairSkippedForBudget) {
                result.partiallyProcessedTests = 1;
            } else {
                result.processedTests = 1;
            }
            if (result.stopReason == LlmPostProcessingPhase.StopReason.TIMEOUT) {
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
            return result;
        } catch (RuntimeException | AssertionError e) {
            logger.warn("Unified LLM post-processing skipped test {} after per-test failure: {}",
                    testIndex, e.getMessage());
            result.infrastructureFailures = 1;
            result.fallbackCounters.add(processor.runAssertionFallback(test, assertionEligible,
                    LlmPostProcessor.FallbackTrigger.INFRASTRUCTURE_FAILURE, options));
            return result;
        }
    }
}
