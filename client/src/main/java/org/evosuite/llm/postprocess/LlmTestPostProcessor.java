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
                    validationTest, contextExecutionResult, null,
                    candidateCollection == null ? Collections.emptyList()
                            : candidateCollection.getAssertions(), options);
            assertionEligible = assertionEligible && LlmPostProcessor.hasAssertionOpportunity(oracleContext);
            if (Properties.LlmPostProcessingScope.ASSERTION_ELIGIBLE_TESTS
                    == options.phaseBudget().scope()
                    && !assertionEligible) {
                result.skippedTests = 1;
                return result;
            }
            boolean assertionsEnabledForTest = options.features().assertions() && assertionEligible;
            PromptResult prompt = PostProcessingPromptRenderer.build(oracleContext,
                    assertionsEnabledForTest, options);
            result.requestedTests = 1;
            result.requestedCalls = 1;
            result.initialCalls = 1;
            result.requestedStatements = test.size();
            String rawResponse = processor.queryWithPostProcessingTraceContext(prompt, testIndex,
                    minimizationResult, 1);
            LlmPostProcessingParseResult parseResult = LlmPostProcessingResponseParser.parse(
                    rawResponse, oracleContext.toParseContext());
            if (parseResult.isInfrastructureFailure()) {
                logger.warn("Unified LLM post-processing ignored response for test {}: {}",
                        testIndex, parseResult.getInfrastructureFailureReason());
                result.infrastructureFailures = 1;
                result.fallbackCounters.add(processor.runAssertionFallback(test, assertionEligible,
                        LlmPostProcessor.FallbackTrigger.INFRASTRUCTURE_FAILURE, options));
                return result;
            }
            result.diagnosticCounters.add(parseResult);
            LlmPostProcessingResponse response = parseResult.getResponse();
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
                        && processor.isPhaseTimedOut(phaseContext)) {
                    stopReason = LlmPostProcessingPhase.StopReason.TIMEOUT;
                }
                if (stopReason == LlmPostProcessingPhase.StopReason.NONE) {
                    result.assertionsAcceptedInitial = response.getAssertions().size();
                    LlmPostProcessor.AssertionRepairResult repairResult =
                            processor.repairRejectedAssertionsIfPossible(phaseContext, parseResult,
                                    parsedResponse, response, initialValidationDiagnostics, oracleContext, validationTest,
                                    contextExecutionResult, limits,
                                    requestedCallsBeforeTest + result.requestedCalls, testIndex, minimizationResult,
                                    options);
                    processor.recordAssertionDiagnostics(repairResult.diagnostics, repairResult.response,
                            repairResult.rawAssertions,
                            testIndex, minimizationResult, "repair", "validation");
                    response = repairResult.response;
                    result.requestedCalls += repairResult.calls;
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
                response = response.withoutAssertions();
            }
            processor.recordAssertionLifecycle(response.getAssertions(), testIndex,
                    minimizationResult, "accepted_final", "final");
            LlmPostProcessingEditApplier.ApplyResult applied = LlmPostProcessingEditApplier.apply(
                    test, oracleContext.getReferences(), response, assertionEligible, contextExecutionResult,
                    options);
            result.testNamesApplied = applied.getTestNamesApplied();
            result.variableNamesApplied = applied.getVariableNamesApplied();
            result.commentsApplied = applied.getCommentsApplied();
            result.sectionBreaksApplied = applied.getSectionBreaksApplied();
            result.assertionsApplied = applied.getAssertionsApplied();
            processor.recordAppliedAssertionLifecycle(phaseContext, response, applied.getAppliedAssertions(), testIndex,
                    minimizationResult);
            if (result.stopReason == LlmPostProcessingPhase.StopReason.LOW_MEMORY
                    || processor.isLowMemory(phaseContext)) {
                result.stopReason = LlmPostProcessingPhase.StopReason.LOW_MEMORY;
                result.acceptedResponses = 1;
                result.partiallyProcessedTests = 1;
                return result;
            }
            if (applied.getAssertionsApplied() == 0) {
                result.fallbackCounters.add(processor.runAssertionFallback(test, assertionEligible,
                        LlmPostProcessor.FallbackTrigger.NO_ACCEPTED_ASSERTIONS, options));
            }
            result.acceptedResponses = 1;
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
