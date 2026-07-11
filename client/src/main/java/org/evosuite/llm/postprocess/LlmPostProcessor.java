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
package org.evosuite.llm.postprocess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.evosuite.Properties;
import org.evosuite.assertion.AssertionGenerator;
import org.evosuite.assertion.CompleteAssertionGenerator;
import org.evosuite.assertion.SimpleMutationAssertionGenerator;
import org.evosuite.assertion.Assertion;
import org.evosuite.assertion.TemplateCodeAssertion;
import org.evosuite.llm.LlmBudgetExceededException;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.LlmTraceRecorder;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutableSnippetEngine;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testsuite.MinimizationResult;
import org.evosuite.testsuite.MinimizationStatus;
import org.evosuite.testsuite.MinimizationStopCause;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Type;

/**
 * Orchestrator for unified LLM post-processing. The unified phase runs after
 * structural post-processing and before test writing.
 *
 * <p>All features degrade gracefully on LLM failure—no feature failure blocks
 * test output generation.
 */
public class LlmPostProcessor {

    private static final Logger logger = LoggerFactory.getLogger(LlmPostProcessor.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final LlmService llmService;
    private final AssertionFallbackRunner assertionFallbackRunner;
    private final AssertionCandidateRunner assertionCandidateRunner;
    private final StabilityExecutionRunner stabilityExecutionRunner;
    private final AssertionEvaluationRunner assertionEvaluationRunner;
    private final ResourceGuard resourceGuard;
    private final PhaseClock phaseClock;

    public LlmPostProcessor() {
        this(LlmService.getInstance());
    }

    LlmPostProcessor(LlmService llmService) {
        this(llmService, new TraceAssertionFallbackRunner());
    }

    LlmPostProcessor(LlmService llmService, AssertionFallbackRunner assertionFallbackRunner) {
        this(llmService, assertionFallbackRunner, new TraceAssertionCandidateRunner());
    }

    LlmPostProcessor(LlmService llmService, AssertionFallbackRunner assertionFallbackRunner,
                     AssertionCandidateRunner assertionCandidateRunner) {
        this(llmService, assertionFallbackRunner, assertionCandidateRunner, new DefaultStabilityExecutionRunner());
    }

    LlmPostProcessor(LlmService llmService, AssertionFallbackRunner assertionFallbackRunner,
                     AssertionCandidateRunner assertionCandidateRunner,
                     StabilityExecutionRunner stabilityExecutionRunner) {
        this(llmService, assertionFallbackRunner, assertionCandidateRunner, stabilityExecutionRunner,
                new TemplateAssertionEvaluationRunner());
    }

    LlmPostProcessor(LlmService llmService, AssertionFallbackRunner assertionFallbackRunner,
                     AssertionCandidateRunner assertionCandidateRunner,
                     StabilityExecutionRunner stabilityExecutionRunner,
                     AssertionEvaluationRunner assertionEvaluationRunner) {
        this(llmService, assertionFallbackRunner, assertionCandidateRunner, stabilityExecutionRunner,
                assertionEvaluationRunner, new DefaultResourceGuard());
    }

    LlmPostProcessor(LlmService llmService, AssertionFallbackRunner assertionFallbackRunner,
                     AssertionCandidateRunner assertionCandidateRunner,
                     StabilityExecutionRunner stabilityExecutionRunner,
                     AssertionEvaluationRunner assertionEvaluationRunner,
                     ResourceGuard resourceGuard) {
        this(llmService, assertionFallbackRunner, assertionCandidateRunner, stabilityExecutionRunner,
                assertionEvaluationRunner, resourceGuard, new SystemPhaseClock());
    }

    LlmPostProcessor(LlmService llmService, AssertionFallbackRunner assertionFallbackRunner,
                     AssertionCandidateRunner assertionCandidateRunner,
                     StabilityExecutionRunner stabilityExecutionRunner,
                     AssertionEvaluationRunner assertionEvaluationRunner,
                     ResourceGuard resourceGuard,
                     PhaseClock phaseClock) {
        this.llmService = llmService;
        this.assertionFallbackRunner = assertionFallbackRunner;
        this.assertionCandidateRunner = assertionCandidateRunner;
        this.stabilityExecutionRunner = stabilityExecutionRunner;
        this.assertionEvaluationRunner = assertionEvaluationRunner;
        this.resourceGuard = resourceGuard == null ? new DefaultResourceGuard() : resourceGuard;
        this.phaseClock = phaseClock == null ? new SystemPhaseClock() : phaseClock;
    }

    /**
     * Check if unified LLM post-processing is enabled, has at least one selected
     * response feature, and the LLM provider is configured.
     */
    public static boolean isAnyFeatureEnabled() {
        if (!Properties.LLM_POSTPROCESSING_ENABLED) {
            return false;
        }
        if (Properties.LLM_PROVIDER == Properties.LlmProvider.NONE) {
            return false;
        }
        return isAnyResponseFeatureEnabled();
    }

    /**
     * Whether the unified response schema has any enabled edit category.
     */
    public static boolean isAnyResponseFeatureEnabled() {
        return Properties.LLM_POSTPROCESSING_ASSERTIONS
                || Properties.LLM_POSTPROCESSING_TEST_NAMES
                || Properties.LLM_POSTPROCESSING_VARIABLE_NAMES
                || Properties.LLM_POSTPROCESSING_COMMENTS
                || Properties.LLM_POSTPROCESSING_SECTION_BREAKS;
    }

    /**
     * Run unified LLM post-processing.
     *
     * @param suite the final structurally post-processed suite
     */
    public void runUnifiedPostProcessing(TestSuiteChromosome suite) {
        runUnifiedPostProcessing(suite, MinimizationResult.disabled(suite));
    }

    /**
     * Run unified LLM post-processing.
     *
     * @param suite the final structurally post-processed suite
     * @param minimizationIncomplete whether the preceding minimization phase was skipped or incomplete
     * @deprecated use {@link #runUnifiedPostProcessing(TestSuiteChromosome, MinimizationResult)} when the real
     * minimization result is available. This overload is retained only as a compatibility shim for older callers.
     */
    @Deprecated
    public void runUnifiedPostProcessing(TestSuiteChromosome suite, boolean minimizationIncomplete) {
        MinimizationResult result;
        if (minimizationIncomplete) {
            int tests = suite == null ? 0 : suite.size();
            int length = suite == null ? 0 : suite.totalLengthOfTestCases();
            result = new MinimizationResult(MinimizationStatus.TIMED_OUT, MinimizationStopCause.TIMEOUT,
                    tests, length, tests, length, 0L);
        } else {
            result = MinimizationResult.disabled(suite);
        }
        runUnifiedPostProcessing(suite, result);
    }

    /**
     * Run unified LLM post-processing.
     *
     * @param suite the final structurally post-processed suite
     * @param minimizationResult explicit result of the preceding minimization phase
     */
    public void runUnifiedPostProcessing(TestSuiteChromosome suite, MinimizationResult minimizationResult) {
        MinimizationResult effectiveMinimizationResult = minimizationResult == null
                ? MinimizationResult.disabled(suite)
                : minimizationResult;
        publishMinimizationContext(effectiveMinimizationResult);
        if (!Properties.LLM_POSTPROCESSING_ENABLED) {
            publishZeroMetrics("disabled");
            return;
        }
        if (effectiveMinimizationResult.isIncomplete()
                && Properties.LLM_POSTPROCESSING_ON_INCOMPLETE_MINIMIZATION
                == Properties.LlmPostProcessingOnIncompleteMinimization.SKIP) {
            logger.info("Unified LLM post-processing skipped: minimization incomplete ({})",
                    effectiveMinimizationResult.getStatus());
            publishZeroMetrics("incomplete_minimization_" + effectiveMinimizationResult.getStatus().name());
            return;
        }
        if (Properties.LLM_PROVIDER == Properties.LlmProvider.NONE) {
            logger.info("Unified LLM post-processing skipped: no LLM provider configured");
            publishZeroMetrics("no_provider");
            return;
        }
        if (!isAnyResponseFeatureEnabled()) {
            logger.info("Unified LLM post-processing skipped: no response features enabled");
            publishZeroMetrics("no_features_enabled");
            return;
        }
        if (suite == null || suite.size() == 0) {
            publishZeroMetrics("empty_suite");
            return;
        }
        if (llmService == null || !llmService.isAvailable() || !llmService.hasBudget()) {
            logger.info("Unified LLM post-processing skipped: LLM service unavailable or budget exhausted");
            publishZeroMetrics("service_unavailable_or_no_budget");
            return;
        }

        boolean limitedIncompleteMinimization = effectiveMinimizationResult.isIncomplete()
                && Properties.LLM_POSTPROCESSING_ON_INCOMPLETE_MINIMIZATION
                == Properties.LlmPostProcessingOnIncompleteMinimization.LIMITED;
        ProcessingLimits limits = ProcessingLimits.fromProperties(effectiveMinimizationResult.isIncomplete());
        int requestedTests = 0;
        int requestedCalls = 0;
        int requestedStatements = 0;
        int acceptedTests = 0;
        int infrastructureFailures = 0;
        int processedTests = 0;
        int partiallyProcessedTests = 0;
        int skippedTests = 0;
        int capSkippedTests = 0;
        DiagnosticCounters diagnosticCounters = new DiagnosticCounters();
        FallbackCounters fallbackCounters = new FallbackCounters();
        int testNamesProposed = 0;
        int testNamesApplied = 0;
        int variableNamesProposed = 0;
        int variableNamesApplied = 0;
        int commentsProposed = 0;
        int commentsApplied = 0;
        int sectionBreaksProposed = 0;
        int sectionBreaksApplied = 0;
        int assertionsProposed = 0;
        int assertionsApplied = 0;
        String stopReason = "";
        long phaseStartMillis = phaseClock.currentTimeMillis();

        List<TestChromosome> tests = suite.getTestChromosomes();
        List<WorkItem> workItems = WorkItem.from(tests, limitedIncompleteMinimization);
        for (int workIndex = 0; workIndex < workItems.size(); workIndex++) {
            if (isPhaseTimedOut(phaseStartMillis)) {
                stopReason = "timeout";
                capSkippedTests += remainingItems(workItems, workIndex);
                break;
            }
            if (limits.maxTests > 0 && requestedTests >= limits.maxTests) {
                stopReason = "max_tests";
                capSkippedTests += remainingItems(workItems, workIndex);
                break;
            }
            if (limits.maxCalls > 0 && requestedCalls >= limits.maxCalls) {
                stopReason = "max_calls";
                capSkippedTests += remainingItems(workItems, workIndex);
                break;
            }
            WorkItem workItem = workItems.get(workIndex);
            int testIndex = workItem.originalIndex;
            TestChromosome chromosome = workItem.chromosome;
            if (chromosome == null || chromosome.getTestCase() == null) {
                skippedTests++;
                continue;
            }
            TestCase test = chromosome.getTestCase();
            if (resourceGuard.isLowMemory()) {
                logger.info("Unified LLM post-processing stopped before test {}: low memory", testIndex);
                stopReason = "low_memory";
                capSkippedTests += remainingItems(workItems, workIndex);
                break;
            }
            if (limits.maxTotalStatements > 0 && requestedStatements + test.size() > limits.maxTotalStatements) {
                stopReason = "max_total_statements";
                capSkippedTests += remainingItems(workItems, workIndex);
                break;
            }
            boolean assertionEligible = isAssertionEligibleForVersion1(chromosome);
            if (Properties.LLM_POSTPROCESSING_SCOPE == Properties.LlmPostProcessingScope.ASSERTION_ELIGIBLE_TESTS
                    && !assertionEligible) {
                skippedTests++;
                continue;
            }
            if (test.size() == 0 && !Properties.LLM_POSTPROCESSING_TEST_NAMES) {
                skippedTests++;
                continue;
            }
            if (!llmService.hasBudget()) {
                logger.info("Unified LLM post-processing stopped: LLM call budget exhausted");
                stopReason = "budget_exhausted";
                capSkippedTests += remainingItems(workItems, workIndex);
                break;
            }
            if (!canStartAnotherLlmCall(phaseStartMillis)) {
                stopReason = "timeout";
                capSkippedTests += remainingItems(workItems, workIndex);
                break;
            }

            boolean assertionsEnabledForTest = Properties.LLM_POSTPROCESSING_ASSERTIONS && assertionEligible;
            CompleteAssertionGenerator.CandidateCollection candidateCollection =
                    assertionsEnabledForTest ? assertionCandidateRunner.collectCandidates(test) : null;
            ExecutionResult contextExecutionResult = candidateCollection == null
                    ? chromosome.getLastExecutionResult()
                    : candidateCollection.getExecutionResult();
            LlmPostProcessingPromptContext context = LlmPostProcessingPromptContext.from(
                    test,
                    contextExecutionResult,
                    candidateCollection == null ? java.util.Collections.emptyList()
                            : candidateCollection.getAssertions());
            PromptResult prompt = LlmPostProcessingPromptBuilder.build(context, testIndex, assertionsEnabledForTest);
            requestedTests++;
            requestedCalls++;
            requestedStatements += test.size();
            try {
                String rawResponse = queryWithPostProcessingTraceContext(prompt, testIndex,
                        effectiveMinimizationResult, 1);
                RawProposedCounts rawProposedCounts = RawProposedCounts.from(rawResponse);
                LlmPostProcessingParseResult parseResult = LlmPostProcessingResponseParser.parse(
                        rawResponse, context.toParseContext());
                if (parseResult.isInfrastructureFailure()) {
                    logger.warn("Unified LLM post-processing ignored response for test {}: {}",
                            testIndex, parseResult.getInfrastructureFailureReason());
                    infrastructureFailures++;
                    fallbackCounters.add(runAssertionFallback(test, assertionEligible,
                            FallbackTrigger.INFRASTRUCTURE_FAILURE));
                    continue;
                }
                diagnosticCounters.add(parseResult);
                LlmPostProcessingResponse response = parseResult.getResponse();
                testNamesProposed += rawProposedCounts.testNames;
                variableNamesProposed += rawProposedCounts.variableNames;
                commentsProposed += rawProposedCounts.comments;
                sectionBreaksProposed += rawProposedCounts.sectionBreaks;
                assertionsProposed += rawProposedCounts.assertions;
                if (assertionsEnabledForTest) {
                    TestCase validationTest = candidateCollection == null ? test : candidateCollection.getTest();
                    LlmPostProcessingResponse parsedResponse = response;
                    if (resourceGuard.isLowMemory()) {
                        response = withoutAssertions(response);
                        stopReason = "low_memory";
                    } else if (!response.getAssertions().isEmpty()) {
                        AssertionValidationResult validationResult = validateAssertionsAgainstScopes(response,
                                validationTest, contextExecutionResult);
                        response = validationResult.response;
                        diagnosticCounters.add(validationResult.diagnostics);
                    }
                    if (!"low_memory".equals(stopReason) && resourceGuard.isLowMemory()) {
                        stopReason = "low_memory";
                    }
                    if (!"low_memory".equals(stopReason) && isPhaseTimedOut(phaseStartMillis)) {
                        stopReason = "timeout";
                    }
                    if (!"low_memory".equals(stopReason) && !"timeout".equals(stopReason)) {
                        AssertionRepairResult repairResult = repairRejectedAssertionsIfPossible(rawResponse, parseResult,
                                parsedResponse, response, context, validationTest, contextExecutionResult, limits,
                                requestedCalls, testIndex, effectiveMinimizationResult, phaseStartMillis);
                        response = repairResult.response;
                        requestedCalls += repairResult.calls;
                        diagnosticCounters.add(repairResult.diagnostics);
                    }
                } else if (!assertionsEnabledForTest && !response.getAssertions().isEmpty()) {
                    response = withoutAssertions(response);
                }
                LlmPostProcessingEditApplier.ApplyResult applied = LlmPostProcessingEditApplier.apply(
                        test, context.getReferences(), response, assertionEligible, contextExecutionResult);
                testNamesApplied += applied.getTestNamesApplied();
                variableNamesApplied += applied.getVariableNamesApplied();
                commentsApplied += applied.getCommentsApplied();
                sectionBreaksApplied += applied.getSectionBreaksApplied();
                assertionsApplied += applied.getAssertionsApplied();
                if ("low_memory".equals(stopReason) || resourceGuard.isLowMemory()) {
                    stopReason = "low_memory";
                    acceptedTests++;
                    partiallyProcessedTests++;
                    capSkippedTests += remainingItems(workItems, workIndex + 1);
                    break;
                }
                if (applied.getAssertionsApplied() == 0) {
                    fallbackCounters.add(runAssertionFallback(test, assertionEligible,
                            FallbackTrigger.NO_ACCEPTED_ASSERTIONS));
                }
                acceptedTests++;
                if (applied.hasAppliedEdits()) {
                    processedTests++;
                } else {
                    partiallyProcessedTests++;
                }
                if ("timeout".equals(stopReason)) {
                    capSkippedTests += remainingItems(workItems, workIndex + 1);
                    break;
                }
                if (!parseResult.getDiagnostics().isEmpty()) {
                    logger.debug("Unified LLM post-processing accepted test {} with {} diagnostic(s)",
                            testIndex, parseResult.getDiagnostics().size());
                }
            } catch (LlmBudgetExceededException e) {
                logger.info("Unified LLM post-processing stopped: {}", e.getMessage());
                stopReason = "budget_exhausted";
                capSkippedTests += remainingItems(workItems, workIndex + 1);
                break;
            } catch (RuntimeException e) {
                logger.warn("Unified LLM post-processing skipped test {} after LLM failure: {}",
                        testIndex, e.getMessage());
                infrastructureFailures++;
                fallbackCounters.add(runAssertionFallback(test, assertionEligible,
                        FallbackTrigger.INFRASTRUCTURE_FAILURE));
            }
        }

        logger.info("Unified LLM post-processing requested {} test(s), accepted {} response(s): testNames={}, "
                        + "variableNames={}, comments={}, sectionBreaks={}, assertions={}",
                requestedTests, acceptedTests, testNamesApplied, variableNamesApplied,
                commentsApplied, sectionBreaksApplied, assertionsApplied);
        PostProcessingMetrics metrics = new PostProcessingMetrics(stopReason);
        metrics.requestedTests = requestedTests;
        metrics.requestedStatements = requestedStatements;
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
        metrics.assertionsApplied = assertionsApplied;
        publishMetrics(metrics);
    }

    private boolean isPhaseTimedOut(long phaseStartMillis) {
        int timeoutSeconds = Math.max(0, Properties.LLM_POSTPROCESSING_TIMEOUT);
        if (timeoutSeconds <= 0) {
            return false;
        }
        long elapsedMillis = phaseClock.currentTimeMillis() - phaseStartMillis;
        return elapsedMillis >= timeoutSeconds * 1000L;
    }

    private boolean canStartAnotherLlmCall(long phaseStartMillis) {
        int timeoutSeconds = Math.max(0, Properties.LLM_POSTPROCESSING_TIMEOUT);
        if (timeoutSeconds <= 0) {
            return true;
        }
        long elapsedMillis = phaseClock.currentTimeMillis() - phaseStartMillis;
        long remainingMillis = timeoutSeconds * 1000L - elapsedMillis;
        long callWindowMillis = Math.max(1000L, Properties.LLM_TIMEOUT_SECONDS * 1000L);
        return remainingMillis > callWindowMillis;
    }

    private String queryWithPostProcessingTraceContext(PromptResult prompt, int testIndex,
                                                       MinimizationResult minimizationResult,
                                                       int repairAttempt) {
        LlmTraceRecorder.setPostProcessingTraceContext(
                minimizationResult.getStatus().name(),
                minimizationResult.getUnderlyingStopCause().name(),
                testIndex);
        try {
            if (repairAttempt <= 1) {
                return llmService.query(prompt, LlmFeature.POST_PROCESSING);
            }
            return llmService.query(prompt.getMessages(), LlmFeature.POST_PROCESSING, repairAttempt);
        } finally {
            LlmTraceRecorder.clearPostProcessingTraceContext();
        }
    }

    private String queryWithPostProcessingTraceContext(List<LlmMessage> messages, int testIndex,
                                                       MinimizationResult minimizationResult,
                                                       int repairAttempt) {
        LlmTraceRecorder.setPostProcessingTraceContext(
                minimizationResult.getStatus().name(),
                minimizationResult.getUnderlyingStopCause().name(),
                testIndex);
        try {
            return llmService.query(messages, LlmFeature.POST_PROCESSING, repairAttempt);
        } finally {
            LlmTraceRecorder.clearPostProcessingTraceContext();
        }
    }

    public static void publishSkippedPostProcessingMetrics(String skipReason, MinimizationResult minimizationResult) {
        publishMinimizationContext(minimizationResult == null
                ? MinimizationResult.disabled(null)
                : minimizationResult);
        publishZeroMetrics(skipReason);
    }

    private static void publishZeroMetrics(String skipReason) {
        publishMetrics(new PostProcessingMetrics(skipReason));
    }

    private static void publishMinimizationContext(MinimizationResult minimizationResult) {
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Minimization_Status,
                minimizationResult.getStatus().name());
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Minimization_Stop_Cause,
                minimizationResult.getUnderlyingStopCause().name());
    }

    private static void publishMetrics(PostProcessingMetrics metrics) {
        DiagnosticCounters diagnosticCounters = metrics.diagnosticCounters;
        FallbackCounters fallbackCounters = metrics.fallbackCounters;
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Skip_Reason,
                metrics.skipReason == null ? "" : metrics.skipReason);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Requested_Tests, metrics.requestedTests);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Requested_Statements, metrics.requestedStatements);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Accepted_Responses, metrics.acceptedResponses);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Skipped_Tests, metrics.skippedTests);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Cap_Skipped_Tests, metrics.capSkippedTests);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Infrastructure_Failures,
                metrics.infrastructureFailures);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Rejected_Edits, diagnosticCounters.total());
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Rejected_Unknown_Ids,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.UNKNOWN_ID));
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Rejected_Duplicates,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.DUPLICATE));
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Rejected_Invalid_Fields,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.INVALID_FIELD));
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Rejected_Unsupported_Kinds,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.UNSUPPORTED_KIND));
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Rejected_Limit_Exceeded,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.LIMIT_EXCEEDED));
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Rejected_Compile,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.COMPILE));
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Rejected_Observed_Execution,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.OBSERVED_EXECUTION));
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Rejected_Stability_Execution,
                diagnosticCounters.get(LlmPostProcessingParseResult.DiagnosticCode.STABILITY_EXECUTION));
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks, fallbackCounters.total);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_Infrastructure,
                fallbackCounters.infrastructure);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_No_Accepted,
                fallbackCounters.noAccepted);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_All, fallbackCounters.all);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertion_Fallbacks_Mutation, fallbackCounters.mutation);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Fallback_Assertions_Applied,
                fallbackCounters.assertionsApplied);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Processed_Tests, metrics.processedTests);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Partially_Processed_Tests,
                metrics.partiallyProcessedTests);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Test_Names_Proposed, metrics.testNamesProposed);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Test_Names_Applied, metrics.testNamesApplied);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Variable_Names_Proposed,
                metrics.variableNamesProposed);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Variable_Names_Applied,
                metrics.variableNamesApplied);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Comments_Proposed, metrics.commentsProposed);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Comments_Applied, metrics.commentsApplied);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Section_Breaks_Proposed,
                metrics.sectionBreaksProposed);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Section_Breaks_Applied,
                metrics.sectionBreaksApplied);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertions_Proposed, metrics.assertionsProposed);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertions_Applied, metrics.assertionsApplied);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertions_Removed_Unstable, 0);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertions_Shipped, 0);
    }

    public static int countUnifiedTemplateAssertions(TestSuiteChromosome suite) {
        if (suite == null) {
            return 0;
        }
        int count = 0;
        for (TestChromosome chromosome : suite.getTestChromosomes()) {
            if (chromosome == null || chromosome.getTestCase() == null) {
                continue;
            }
            count += countUnifiedTemplateAssertions(chromosome.getTestCase(), true);
        }
        return count;
    }

    public static void publishFinalAssertionReconciliation(TestSuiteChromosome suite,
                                                           int initiallyAppliedAssertions) {
        FinalAssertionReconciliation reconciliation = finalAssertionReconciliation(suite,
                initiallyAppliedAssertions);
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertions_Removed_Unstable,
                reconciliation.getRemovedUnstable());
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Assertions_Shipped,
                reconciliation.getShipped());
    }

    static FinalAssertionReconciliation finalAssertionReconciliation(TestSuiteChromosome suite,
                                                                     int initiallyAppliedAssertions) {
        int shipped = 0;
        int stillPresentButCommented = 0;
        if (suite != null) {
            for (TestChromosome chromosome : suite.getTestChromosomes()) {
                if (chromosome == null || chromosome.getTestCase() == null) {
                    continue;
                }
                TestCase test = chromosome.getTestCase();
                if (test.isUnstable()) {
                    stillPresentButCommented += countUnifiedTemplateAssertions(test, true);
                } else {
                    shipped += countUnifiedTemplateAssertions(test, true);
                }
            }
        }
        int removedUnstable = Math.max(0, initiallyAppliedAssertions - shipped);
        removedUnstable = Math.max(removedUnstable, stillPresentButCommented);
        return new FinalAssertionReconciliation(shipped, removedUnstable);
    }

    private static int countUnifiedTemplateAssertions(TestCase test, boolean includeUnstable) {
        if (test == null || (!includeUnstable && test.isUnstable())) {
            return 0;
        }
        int count = 0;
        for (Assertion assertion : test.getAssertions()) {
            if (assertion instanceof TemplateCodeAssertion) {
                count++;
            }
        }
        return count;
    }

    private static int remainingItems(List<WorkItem> workItems, int startIndex) {
        if (workItems == null || startIndex >= workItems.size()) {
            return 0;
        }
        return Math.max(0, workItems.size() - Math.max(0, startIndex));
    }

    private boolean isAssertionEligibleForVersion1(TestChromosome chromosome) {
        if (chromosome == null || chromosome.getTestCase() == null || chromosome.getTestCase().size() == 0) {
            return false;
        }
        ExecutionResult result = executionResultForEligibility(chromosome);
        if (result == null) {
            return false;
        }
        return !result.hasTimeout()
                && !result.hasTestException()
                && result.noThrownExceptions();
    }

    private ExecutionResult executionResultForEligibility(TestChromosome chromosome) {
        ExecutionResult result = chromosome.getLastExecutionResult();
        if (result != null) {
            return result;
        }
        try {
            result = stabilityExecutionRunner.execute(chromosome.getTestCase());
            chromosome.setLastExecutionResult(result);
            return result;
        } catch (RuntimeException | AssertionError e) {
            logger.debug("Unified LLM post-processing could not refresh missing execution result: {}",
                    e.getMessage());
            return null;
        }
    }

    private AssertionValidationResult validateAssertionsAgainstScopes(
            LlmPostProcessingResponse response, TestCase validationTest, ExecutionResult executionResult) {
        if (response.getAssertions().isEmpty()) {
            return AssertionValidationResult.success(response);
        }
        TestCase stabilityTest = validationTest == null ? null : validationTest.clone();
        if (stabilityTest != null) {
            stabilityTest.removeAssertions();
        }
        ExecutionResult stabilityExecutionResult = stabilityTest == null
                ? null
                : stabilityExecutionRunner.execute(stabilityTest);
        return filterAssertionsByValidatedScopes(response, validationTest, executionResult,
                stabilityTest, stabilityExecutionResult, assertionEvaluationRunner);
    }

    private static AssertionValidationResult filterAssertionsByValidatedScopes(
            LlmPostProcessingResponse response,
            TestCase validationTest,
            ExecutionResult executionResult,
            TestCase stabilityTest,
            ExecutionResult stabilityExecutionResult,
            AssertionEvaluationRunner assertionEvaluationRunner) {
        if (response.getAssertions().isEmpty()) {
            return AssertionValidationResult.success(response);
        }
        if (validationTest == null || validationTest.size() == 0
                || executionResult == null || executionResult.getFinalScope() == null) {
            return AssertionValidationResult.rejectedAll(response,
                    LlmPostProcessingParseResult.DiagnosticCode.OBSERVED_EXECUTION,
                    "Original observed final scope is unavailable");
        }
        if (stabilityTest == null || stabilityTest.size() == 0
                || stabilityExecutionResult == null || stabilityExecutionResult.getFinalScope() == null
                || stabilityExecutionResult.hasTimeout()
                || stabilityExecutionResult.hasTestException()
                || !stabilityExecutionResult.noThrownExceptions()) {
            return AssertionValidationResult.rejectedAll(response,
                    LlmPostProcessingParseResult.DiagnosticCode.STABILITY_EXECUTION,
                    "Stability re-execution did not produce a normal final scope");
        }
        LlmPostProcessingReferences validationReferences = LlmPostProcessingReferences.from(validationTest);
        LlmPostProcessingReferences stabilityReferences = LlmPostProcessingReferences.from(stabilityTest);
        Scope originalFinalScope = executionResult.getFinalScope();
        Scope stabilityFinalScope = stabilityExecutionResult.getFinalScope();
        LlmPostProcessingResponse filtered = copyWithoutAssertions(response);
        List<LlmPostProcessingParseResult.Diagnostic> diagnostics = new ArrayList<>();
        for (LlmPostProcessingResponse.AssertionProposal proposal : response.getAssertions()) {
            EvaluationOutcome originalOutcome = assertionEvaluationRunner.evaluate(proposal, validationTest,
                    validationReferences, originalFinalScope);
            if (!originalOutcome.isAccepted()) {
                diagnostics.add(validationDiagnostic(proposal, originalOutcome,
                        "Assertion rejected against original observed final scope"));
                continue;
            }
            EvaluationOutcome stabilityOutcome = assertionEvaluationRunner.evaluate(proposal, stabilityTest,
                    stabilityReferences, stabilityFinalScope);
            if (!stabilityOutcome.isAccepted()) {
                diagnostics.add(validationDiagnostic(proposal, stabilityOutcome.asStabilityFailure(),
                        "Assertion rejected against stability final scope"));
                continue;
            }
            filtered.addAssertion(proposal);
        }
        return new AssertionValidationResult(filtered, diagnostics);
    }

    private static LlmPostProcessingParseResult.Diagnostic validationDiagnostic(
            LlmPostProcessingResponse.AssertionProposal proposal,
            EvaluationOutcome outcome,
            String defaultMessage) {
        String id = proposal == null ? "?" : proposal.getAssertionId();
        String message = outcome.message == null ? defaultMessage : outcome.message;
        return new LlmPostProcessingParseResult.Diagnostic(outcome.diagnosticCode,
                "assertions[" + id + "]", message);
    }

    private static LlmPostProcessingResponse withoutAssertions(LlmPostProcessingResponse response) {
        return copyWithoutAssertions(response);
    }

    private static LlmPostProcessingResponse copyWithoutAssertions(LlmPostProcessingResponse response) {
        LlmPostProcessingResponse copy = new LlmPostProcessingResponse(response.getSchemaVersion());
        copy.setTestName(response.getTestName());
        for (Map.Entry<String, String> entry : response.getVariableNames().entrySet()) {
            copy.addVariableName(entry.getKey(), entry.getValue());
        }
        for (LlmPostProcessingResponse.CommentProposal comment : response.getComments()) {
            copy.addComment(comment);
        }
        for (String sectionBreak : response.getSectionBreaksAfter()) {
            copy.addSectionBreakAfter(sectionBreak);
        }
        return copy;
    }

    private AssertionRepairResult repairRejectedAssertionsIfPossible(
            String rawResponse,
            LlmPostProcessingParseResult parseResult,
            LlmPostProcessingResponse parsedResponse,
            LlmPostProcessingResponse acceptedResponse,
            LlmPostProcessingPromptContext context,
            TestCase validationTest,
            ExecutionResult executionResult,
            ProcessingLimits limits,
            int requestedCalls,
            int testIndex,
            MinimizationResult minimizationResult,
            long phaseStartMillis) {
        if (Properties.LLM_POSTPROCESSING_ASSERTION_REPAIR_ATTEMPTS <= 0) {
            return AssertionRepairResult.noCall(acceptedResponse);
        }
        if (!llmService.hasBudget() || (limits.maxCalls > 0 && requestedCalls >= limits.maxCalls)) {
            return AssertionRepairResult.noCall(acceptedResponse);
        }
        if (!canStartAnotherLlmCall(phaseStartMillis)) {
            return AssertionRepairResult.noCall(acceptedResponse);
        }
        if (acceptedResponse.getAssertions().size() >= Properties.LLM_POSTPROCESSING_MAX_ASSERTIONS_PER_TEST) {
            return AssertionRepairResult.noCall(acceptedResponse);
        }

        List<LlmAssertionRepairer.RejectedAssertion> rejected =
                LlmAssertionRepairer.collectRepairableRejectedAssertions(
                        rawResponse, parseResult, parsedResponse, acceptedResponse);
        if (rejected.isEmpty()) {
            return AssertionRepairResult.noCall(acceptedResponse);
        }

        Set<String> repairableIds = new HashSet<>();
        for (LlmAssertionRepairer.RejectedAssertion candidate : rejected) {
            repairableIds.add(candidate.getAssertionId());
        }

        try {
            String rawRepairResponse = queryWithPostProcessingTraceContext(
                    LlmAssertionRepairer.buildRepairMessages(context, rejected),
                    testIndex,
                    minimizationResult,
                    2);
            LlmPostProcessingResponse repaired = LlmAssertionRepairer.parseRepairResponse(
                    rawRepairResponse, context.toParseContext(), repairableIds);
            AssertionValidationResult validationResult = validateAssertionsAgainstScopes(repaired, validationTest,
                    executionResult);
            return new AssertionRepairResult(mergeAcceptedAndRepairedAssertions(acceptedResponse,
                    validationResult.response), 1, validationResult.diagnostics);
        } catch (LlmBudgetExceededException e) {
            throw e;
        } catch (RuntimeException e) {
            logger.debug("Unified LLM post-processing assertion repair skipped after failure: {}",
                    e.getMessage());
            return AssertionRepairResult.noCall(acceptedResponse);
        }
    }

    private static LlmPostProcessingResponse mergeAcceptedAndRepairedAssertions(
            LlmPostProcessingResponse acceptedResponse,
            LlmPostProcessingResponse repairedResponse) {
        LlmPostProcessingResponse merged = copyWithoutAssertions(acceptedResponse);
        Set<String> seenIds = new HashSet<>();
        int accepted = 0;
        for (LlmPostProcessingResponse.AssertionProposal proposal : acceptedResponse.getAssertions()) {
            if (accepted >= Properties.LLM_POSTPROCESSING_MAX_ASSERTIONS_PER_TEST) {
                return merged;
            }
            merged.addAssertion(proposal);
            seenIds.add(proposal.getAssertionId());
            accepted++;
        }
        for (LlmPostProcessingResponse.AssertionProposal proposal : repairedResponse.getAssertions()) {
            if (accepted >= Properties.LLM_POSTPROCESSING_MAX_ASSERTIONS_PER_TEST) {
                break;
            }
            if (seenIds.add(proposal.getAssertionId())) {
                merged.addAssertion(proposal);
                accepted++;
            }
        }
        return merged;
    }

    private FallbackCounters runAssertionFallback(TestCase test, boolean assertionEligible,
                                                  FallbackTrigger trigger) {
        if (!shouldRunAssertionFallback(assertionEligible, trigger)) {
            return FallbackCounters.none();
        }
        try {
            int applied = assertionFallbackRunner.applyFallbackAssertions(test,
                    Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK_STRATEGY);
            if (applied == 0) {
                return FallbackCounters.none();
            }
            return FallbackCounters.applied(trigger, applied,
                    Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK_STRATEGY);
        } catch (RuntimeException e) {
            logger.warn("Unified LLM post-processing assertion fallback failed: {}", e.getMessage());
            return FallbackCounters.none();
        }
    }

    private static boolean shouldRunAssertionFallback(boolean assertionEligible, FallbackTrigger trigger) {
        if (!Properties.LLM_POSTPROCESSING_ASSERTIONS || !assertionEligible) {
            return false;
        }
        if (Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK
                == Properties.LlmPostProcessingAssertionFallback.NONE) {
            return false;
        }
        if (trigger == FallbackTrigger.INFRASTRUCTURE_FAILURE) {
            return Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK
                    == Properties.LlmPostProcessingAssertionFallback.ON_INFRASTRUCTURE_FAILURE
                    || Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK
                    == Properties.LlmPostProcessingAssertionFallback.ON_NO_ACCEPTED_ASSERTIONS;
        }
        return Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK
                == Properties.LlmPostProcessingAssertionFallback.ON_NO_ACCEPTED_ASSERTIONS;
    }

    private static AssertionGenerator createFallbackGenerator(
            Properties.LlmPostProcessingAssertionFallbackStrategy strategy) {
        if (strategy == Properties.LlmPostProcessingAssertionFallbackStrategy.MUTATION) {
            return new SimpleMutationAssertionGenerator();
        }
        return new CompleteAssertionGenerator();
    }

    interface AssertionFallbackRunner {
        int applyFallbackAssertions(TestCase test,
                                    Properties.LlmPostProcessingAssertionFallbackStrategy strategy);
    }

    interface AssertionCandidateRunner {
        CompleteAssertionGenerator.CandidateCollection collectCandidates(TestCase test);
    }

    interface StabilityExecutionRunner {
        ExecutionResult execute(TestCase test);
    }

    interface AssertionEvaluationRunner {
        EvaluationOutcome evaluate(LlmPostProcessingResponse.AssertionProposal proposal,
                                   TestCase validationTest,
                                   LlmPostProcessingReferences references,
                                   Scope finalScope);
    }

    interface ResourceGuard {
        boolean isLowMemory();
    }

    interface PhaseClock {
        long currentTimeMillis();
    }

    private static final class SystemPhaseClock implements PhaseClock {
        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }
    }

    private static final class DefaultResourceGuard implements ResourceGuard {
        @Override
        public boolean isLowMemory() {
            Runtime runtime = Runtime.getRuntime();
            long threshold = Properties.MIN_FREE_MEM * 2L;
            long freeMem = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
            if (freeMem >= threshold) {
                return false;
            }
            System.gc();
            freeMem = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
            return freeMem < threshold;
        }
    }

    private static final class TraceAssertionCandidateRunner implements AssertionCandidateRunner {
        @Override
        public CompleteAssertionGenerator.CandidateCollection collectCandidates(TestCase test) {
            TestCase candidateTest = test.clone();
            candidateTest.removeAssertions();
            CompleteAssertionGenerator generator = new CompleteAssertionGenerator();
            return generator.collectCandidates(candidateTest);
        }
    }

    private static final class DefaultStabilityExecutionRunner implements StabilityExecutionRunner {
        @Override
        public ExecutionResult execute(TestCase test) {
            return TestCaseExecutor.runTest(test);
        }
    }

    private static final class TemplateAssertionEvaluationRunner implements AssertionEvaluationRunner {
        @Override
        public EvaluationOutcome evaluate(LlmPostProcessingResponse.AssertionProposal proposal,
                                          TestCase validationTest,
                                          LlmPostProcessingReferences references,
                                          Scope finalScope) {
            try {
                TemplateCodeAssertion assertion = LlmPostProcessingEditApplier.toTemplateAssertionForValidation(
                        proposal, references);
                if (assertion == null || validationTest == null || validationTest.size() == 0 || finalScope == null) {
                    return EvaluationOutcome.observedFailure("Assertion could not be bound to the final scope");
                }
                assertion.setStatement(validationTest.getStatement(validationTest.size() - 1));
                Map<String, Type> variableTypes = new LinkedHashMap<>();
                Map<String, Object> variableValues = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> entry : assertion.getBindings().entrySet()) {
                    int position = entry.getValue();
                    if (position < 0 || position >= validationTest.size()) {
                        return EvaluationOutcome.observedFailure("Assertion binding is outside the test");
                    }
                    VariableReference variable = validationTest.getStatement(position).getReturnValue();
                    if (variable == null) {
                        return EvaluationOutcome.observedFailure("Assertion binding has no variable");
                    }
                    variableTypes.put(entry.getKey(), variable.getType());
                    variableValues.put(entry.getKey(), variable.getObject(finalScope));
                }
                int timeoutMs = Math.max(0, Properties.LLM_POSTPROCESSING_ASSERTION_EVAL_TIMEOUT_MS);
                return ExecutableSnippetEngine.INSTANCE.evaluateAssertion(assertion.render(null),
                        variableTypes, variableValues, timeoutMs, timeoutMs)
                        ? EvaluationOutcome.accepted()
                        : EvaluationOutcome.observedFailure("Assertion did not hold in the final scope");
            } catch (ExecutableSnippetEngine.AssertionCompilationTimeoutException e) {
                return EvaluationOutcome.compileFailure(e.getMessage());
            } catch (ExecutableSnippetEngine.AssertionEvaluationTimeoutException e) {
                return EvaluationOutcome.observedFailure(e.getMessage());
            } catch (RuntimeException | AssertionError e) {
                return EvaluationOutcome.compileFailure(e.getMessage());
            } catch (Throwable e) {
                return EvaluationOutcome.observedFailure(e.getMessage());
            }
        }
    }

    private static final class TraceAssertionFallbackRunner implements AssertionFallbackRunner {
        @Override
        public int applyFallbackAssertions(TestCase test,
                                           Properties.LlmPostProcessingAssertionFallbackStrategy strategy) {
            TestCase fallbackTest = test.clone();
            fallbackTest.removeAssertions();
            AssertionGenerator generator = createFallbackGenerator(strategy);
            generator.addAssertions(fallbackTest);
            if (fallbackTest.getAssertions().isEmpty()) {
                return 0;
            }
            List<StagedAssertion> staged = new ArrayList<>();
            for (int i = 0; i < test.size() && i < fallbackTest.size(); i++) {
                for (Assertion assertion : fallbackTest.getStatement(i).getAssertions()) {
                    if (assertion != null && !test.getStatement(i).getAssertions().contains(assertion)) {
                        staged.add(new StagedAssertion(i, assertion.clone(test)));
                    }
                }
            }
            List<StagedAssertion> attached = new ArrayList<>();
            try {
                for (StagedAssertion stagedAssertion : staged) {
                    test.getStatement(stagedAssertion.statementPosition).addAssertion(stagedAssertion.assertion);
                    attached.add(stagedAssertion);
                }
                return attached.size();
            } catch (RuntimeException | AssertionError e) {
                for (StagedAssertion stagedAssertion : attached) {
                    if (stagedAssertion.assertion.getStatement() != null) {
                        stagedAssertion.assertion.getStatement().removeAssertion(stagedAssertion.assertion);
                    }
                }
                throw e;
            }
        }
    }

    private static final class StagedAssertion {
        private final int statementPosition;
        private final Assertion assertion;

        private StagedAssertion(int statementPosition, Assertion assertion) {
            this.statementPosition = statementPosition;
            this.assertion = assertion;
        }
    }

    private static final class PostProcessingMetrics {
        private final String skipReason;
        private int requestedTests;
        private int requestedStatements;
        private int acceptedResponses;
        private int skippedTests;
        private int capSkippedTests;
        private int infrastructureFailures;
        private DiagnosticCounters diagnosticCounters = new DiagnosticCounters();
        private FallbackCounters fallbackCounters = new FallbackCounters();
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
        private int assertionsApplied;

        private PostProcessingMetrics(String skipReason) {
            this.skipReason = skipReason;
        }
    }

    private static final class RawProposedCounts {
        private final int testNames;
        private final int variableNames;
        private final int comments;
        private final int sectionBreaks;
        private final int assertions;

        private RawProposedCounts(int testNames, int variableNames, int comments, int sectionBreaks, int assertions) {
            this.testNames = testNames;
            this.variableNames = variableNames;
            this.comments = comments;
            this.sectionBreaks = sectionBreaks;
            this.assertions = assertions;
        }

        private static RawProposedCounts from(String rawResponse) {
            if (rawResponse == null || rawResponse.trim().isEmpty()) {
                return none();
            }
            try {
                JsonNode root = JSON_MAPPER.readTree(
                        LlmPostProcessingResponseParser.normalizeJsonResponse(rawResponse));
                if (!root.isObject()) {
                    return none();
                }
                return new RawProposedCounts(
                        root.hasNonNull("testName") ? 1 : 0,
                        sizeIfObject(root.get("variableNames")),
                        sizeIfArray(root.get("comments")),
                        sizeIfArray(root.get("sectionBreaksAfter")),
                        sizeIfArray(root.get("assertions")));
            } catch (Exception e) {
                return none();
            }
        }

        private static RawProposedCounts none() {
            return new RawProposedCounts(0, 0, 0, 0, 0);
        }

        private static int sizeIfObject(JsonNode node) {
            return node != null && node.isObject() ? node.size() : 0;
        }

        private static int sizeIfArray(JsonNode node) {
            return node != null && node.isArray() ? node.size() : 0;
        }
    }

    private enum FallbackTrigger {
        INFRASTRUCTURE_FAILURE,
        NO_ACCEPTED_ASSERTIONS
    }

    private static final class WorkItem {
        private final int originalIndex;
        private final TestChromosome chromosome;
        private final int uniquelyCoveredGoals;
        private final int totalCoveredGoals;
        private final int statementCount;

        private WorkItem(int originalIndex, TestChromosome chromosome, Map<Object, Integer> goalCounts) {
            this.originalIndex = originalIndex;
            this.chromosome = chromosome;
            Set<?> goals = coveredGoals(chromosome);
            int unique = 0;
            for (Object goal : goals) {
                Integer count = goalCounts.get(goal);
                if (count != null && count == 1) {
                    unique++;
                }
            }
            this.uniquelyCoveredGoals = unique;
            this.totalCoveredGoals = goals.size();
            this.statementCount = chromosome == null || chromosome.getTestCase() == null
                    ? Integer.MAX_VALUE
                    : chromosome.getTestCase().size();
        }

        private static List<WorkItem> from(List<TestChromosome> tests, boolean limitedOrdering) {
            List<WorkItem> items = new ArrayList<>();
            if (tests == null) {
                return items;
            }
            Map<Object, Integer> goalCounts = countCoveredGoals(tests);
            for (int i = 0; i < tests.size(); i++) {
                items.add(new WorkItem(i, tests.get(i), goalCounts));
            }
            if (limitedOrdering) {
                items.sort(Comparator
                        .comparingInt((WorkItem item) -> item.uniquelyCoveredGoals).reversed()
                        .thenComparing(Comparator.comparingInt((WorkItem item) -> item.totalCoveredGoals).reversed())
                        .thenComparingInt(item -> item.statementCount)
                        .thenComparingInt(item -> item.originalIndex));
            }
            return items;
        }

        private static Map<Object, Integer> countCoveredGoals(List<TestChromosome> tests) {
            Map<Object, Integer> counts = new HashMap<>();
            for (TestChromosome test : tests) {
                for (Object goal : coveredGoals(test)) {
                    Integer count = counts.get(goal);
                    counts.put(goal, count == null ? 1 : count + 1);
                }
            }
            return counts;
        }

        private static Set<?> coveredGoals(TestChromosome chromosome) {
            if (chromosome == null || chromosome.getTestCase() == null) {
                return java.util.Collections.emptySet();
            }
            Set<?> goals = chromosome.getTestCase().getCoveredGoals();
            return goals == null ? java.util.Collections.emptySet() : goals;
        }
    }

    private static final class DiagnosticCounters {
        private final Map<LlmPostProcessingParseResult.DiagnosticCode, Integer> byCode =
                new EnumMap<>(LlmPostProcessingParseResult.DiagnosticCode.class);
        private int total;

        private void add(LlmPostProcessingParseResult parseResult) {
            for (LlmPostProcessingParseResult.Diagnostic diagnostic : parseResult.getDiagnostics()) {
                add(diagnostic);
            }
        }

        private void add(List<LlmPostProcessingParseResult.Diagnostic> diagnostics) {
            if (diagnostics == null) {
                return;
            }
            for (LlmPostProcessingParseResult.Diagnostic diagnostic : diagnostics) {
                add(diagnostic);
            }
        }

        private void add(LlmPostProcessingParseResult.Diagnostic diagnostic) {
            if (diagnostic == null) {
                return;
            }
            total++;
            LlmPostProcessingParseResult.DiagnosticCode code = diagnostic.getCode();
            byCode.put(code, get(code) + 1);
        }

        private int total() {
            return total;
        }

        private int get(LlmPostProcessingParseResult.DiagnosticCode code) {
            Integer count = byCode.get(code);
            return count == null ? 0 : count;
        }
    }

    private static final class FallbackCounters {
        private int total;
        private int infrastructure;
        private int noAccepted;
        private int all;
        private int mutation;
        private int assertionsApplied;

        private static FallbackCounters none() {
            return new FallbackCounters();
        }

        private static FallbackCounters applied(FallbackTrigger trigger, int assertionsApplied,
                                                Properties.LlmPostProcessingAssertionFallbackStrategy strategy) {
            FallbackCounters counters = new FallbackCounters();
            counters.total = 1;
            counters.assertionsApplied = assertionsApplied;
            if (trigger == FallbackTrigger.INFRASTRUCTURE_FAILURE) {
                counters.infrastructure = 1;
            } else {
                counters.noAccepted = 1;
            }
            if (strategy == Properties.LlmPostProcessingAssertionFallbackStrategy.MUTATION) {
                counters.mutation = 1;
            } else {
                counters.all = 1;
            }
            return counters;
        }

        private void add(FallbackCounters other) {
            total += other.total;
            infrastructure += other.infrastructure;
            noAccepted += other.noAccepted;
            all += other.all;
            mutation += other.mutation;
            assertionsApplied += other.assertionsApplied;
        }
    }

    private static final class ProcessingLimits {
        private static final int DEFAULT_LIMITED_MAX_TESTS = 20;
        private static final int DEFAULT_LIMITED_MAX_TOTAL_STATEMENTS = 400;
        private static final int DEFAULT_LIMITED_MAX_CALLS = 40;

        private final int maxTests;
        private final int maxTotalStatements;
        private final int maxCalls;

        private ProcessingLimits(int maxTests, int maxTotalStatements, int maxCalls) {
            this.maxTests = maxTests;
            this.maxTotalStatements = maxTotalStatements;
            this.maxCalls = maxCalls;
        }

        private static ProcessingLimits fromProperties(boolean minimizationIncomplete) {
            int maxTests = Math.max(0, Properties.LLM_POSTPROCESSING_MAX_TESTS);
            int maxTotalStatements = Math.max(0, Properties.LLM_POSTPROCESSING_MAX_TOTAL_STATEMENTS);
            int maxCalls = Math.max(0, Properties.LLM_POSTPROCESSING_MAX_CALLS);
            if (minimizationIncomplete
                    && Properties.LLM_POSTPROCESSING_ON_INCOMPLETE_MINIMIZATION
                    == Properties.LlmPostProcessingOnIncompleteMinimization.LIMITED) {
                maxTests = boundedCap(maxTests,
                        nonZeroLimitedCap(Properties.LLM_POSTPROCESSING_LIMITED_MAX_TESTS,
                                DEFAULT_LIMITED_MAX_TESTS));
                maxTotalStatements = boundedCap(maxTotalStatements,
                        nonZeroLimitedCap(Properties.LLM_POSTPROCESSING_LIMITED_MAX_TOTAL_STATEMENTS,
                                DEFAULT_LIMITED_MAX_TOTAL_STATEMENTS));
                maxCalls = nonZeroLimitedCap(Properties.LLM_POSTPROCESSING_LIMITED_MAX_CALLS,
                        DEFAULT_LIMITED_MAX_CALLS);
            }
            return new ProcessingLimits(maxTests, maxTotalStatements, maxCalls);
        }

        private static int nonZeroLimitedCap(int configured, int defaultValue) {
            return configured <= 0 ? defaultValue : configured;
        }

        private static int boundedCap(int configured, int limited) {
            if (configured <= 0) {
                return limited;
            }
            return Math.min(configured, limited);
        }
    }

    private static final class AssertionRepairResult {
        private final LlmPostProcessingResponse response;
        private final int calls;
        private final List<LlmPostProcessingParseResult.Diagnostic> diagnostics;

        private AssertionRepairResult(LlmPostProcessingResponse response, int calls) {
            this(response, calls, java.util.Collections.<LlmPostProcessingParseResult.Diagnostic>emptyList());
        }

        private AssertionRepairResult(LlmPostProcessingResponse response, int calls,
                                      List<LlmPostProcessingParseResult.Diagnostic> diagnostics) {
            this.response = response;
            this.calls = calls;
            this.diagnostics = diagnostics == null
                    ? java.util.Collections.<LlmPostProcessingParseResult.Diagnostic>emptyList()
                    : diagnostics;
        }

        private static AssertionRepairResult noCall(LlmPostProcessingResponse response) {
            return new AssertionRepairResult(response, 0);
        }
    }

    private static final class AssertionValidationResult {
        private final LlmPostProcessingResponse response;
        private final List<LlmPostProcessingParseResult.Diagnostic> diagnostics;

        private AssertionValidationResult(LlmPostProcessingResponse response,
                                          List<LlmPostProcessingParseResult.Diagnostic> diagnostics) {
            this.response = response;
            this.diagnostics = diagnostics == null
                    ? java.util.Collections.<LlmPostProcessingParseResult.Diagnostic>emptyList()
                    : diagnostics;
        }

        private static AssertionValidationResult success(LlmPostProcessingResponse response) {
            return new AssertionValidationResult(response,
                    java.util.Collections.<LlmPostProcessingParseResult.Diagnostic>emptyList());
        }

        private static AssertionValidationResult rejectedAll(
                LlmPostProcessingResponse response,
                LlmPostProcessingParseResult.DiagnosticCode code,
                String message) {
            List<LlmPostProcessingParseResult.Diagnostic> diagnostics = new ArrayList<>();
            for (LlmPostProcessingResponse.AssertionProposal proposal : response.getAssertions()) {
                diagnostics.add(new LlmPostProcessingParseResult.Diagnostic(code,
                        "assertions[" + proposal.getAssertionId() + "]", message));
            }
            return new AssertionValidationResult(withoutAssertions(response), diagnostics);
        }
    }

    static final class FinalAssertionReconciliation {
        private final int shipped;
        private final int removedUnstable;

        private FinalAssertionReconciliation(int shipped, int removedUnstable) {
            this.shipped = shipped;
            this.removedUnstable = removedUnstable;
        }

        int getShipped() {
            return shipped;
        }

        int getRemovedUnstable() {
            return removedUnstable;
        }
    }

    static final class EvaluationOutcome {
        private final LlmPostProcessingParseResult.DiagnosticCode diagnosticCode;
        private final String message;

        private EvaluationOutcome(LlmPostProcessingParseResult.DiagnosticCode diagnosticCode, String message) {
            this.diagnosticCode = diagnosticCode;
            this.message = message;
        }

        static EvaluationOutcome accepted() {
            return new EvaluationOutcome(null, null);
        }

        static EvaluationOutcome compileFailure(String message) {
            return new EvaluationOutcome(LlmPostProcessingParseResult.DiagnosticCode.COMPILE, message);
        }

        static EvaluationOutcome observedFailure(String message) {
            return new EvaluationOutcome(LlmPostProcessingParseResult.DiagnosticCode.OBSERVED_EXECUTION, message);
        }

        static EvaluationOutcome stabilityFailure(String message) {
            return new EvaluationOutcome(LlmPostProcessingParseResult.DiagnosticCode.STABILITY_EXECUTION, message);
        }

        boolean isAccepted() {
            return diagnosticCode == null;
        }

        EvaluationOutcome asStabilityFailure() {
            if (isAccepted() || diagnosticCode == LlmPostProcessingParseResult.DiagnosticCode.COMPILE) {
                return this;
            }
            return stabilityFailure(message);
        }
    }

}
