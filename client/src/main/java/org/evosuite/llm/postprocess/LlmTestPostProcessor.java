/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 */
package org.evosuite.llm.postprocess;

import org.evosuite.Properties;
import org.evosuite.assertion.CompleteAssertionGenerator;
import org.evosuite.assertion.TemplateCodeAssertion;
import org.evosuite.llm.LlmBudgetExceededException;
import org.evosuite.llm.LlmCallFailedException;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmRequest;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.LlmTraceRecorder;
import org.evosuite.llm.postprocess.LlmPostProcessingResponse.AssertionProposal;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testsuite.MinimizationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns the request and validation workflow for one scheduled test.  The
 * enclosing processor supplies shared safety helpers and the phase snapshot;
 * this class returns only a per-test delta for suite aggregation.
 */
final class LlmTestPostProcessor {

    private static final Logger logger = LoggerFactory.getLogger(LlmTestPostProcessor.class);

    private final LlmService llmService;
    private final LlmPostProcessor.AssertionCandidateRunner assertionCandidateRunner;
    private final LlmPostProcessor.AssertionFallbackRunner assertionFallbackRunner;
    private final PostProcessingAssertionValidator assertionValidator;
    private final LlmPostProcessingPhaseContext phaseContext;

    LlmTestPostProcessor(LlmService llmService,
                         LlmPostProcessor.AssertionCandidateRunner assertionCandidateRunner,
                         LlmPostProcessor.AssertionFallbackRunner assertionFallbackRunner,
                         PostProcessingAssertionValidator assertionValidator,
                         LlmPostProcessingPhaseContext phaseContext) {
        this.llmService = llmService;
        this.assertionCandidateRunner = assertionCandidateRunner;
        this.assertionFallbackRunner = assertionFallbackRunner;
        this.assertionValidator = assertionValidator;
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
                    collectAssertionContext ? assertionCandidateRunner.collectCandidates(test) : null;
            detachCandidateAssertions(candidateCollection);
            TestCase validationTest = candidateCollection == null ? test : candidateCollection.getTest();
            ExecutionResult contextExecutionResult = candidateCollection == null
                    ? chromosome.getLastExecutionResult()
                    : candidateCollection.getExecutionResult();
            OracleContext oracleContext = OracleContext.from(
                    validationTest, contextExecutionResult,
                    candidateCollection == null ? Collections.emptyList()
                            : candidateCollection.getAssertions(), options);
            assertionEligible = assertionEligible && hasAssertionOpportunity(oracleContext);
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
            String rawResponse = queryWithPostProcessingTraceContext(prompt,
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
                result.fallbackCounters.add(runAssertionFallback(test, assertionEligible,
                        LlmPostProcessor.FallbackTrigger.INFRASTRUCTURE_FAILURE));
                return result;
            }
            result.diagnosticCounters.add(parseResult);
            LlmPostProcessingResponse response = parseResult.getResponse();
            recordAssertionDiagnostics(parseResult.getDiagnostics(), response,
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
                if (phaseContext.isLowMemory()) {
                    response = response.withoutAssertions();
                    stopReason = LlmPostProcessingPhase.StopReason.LOW_MEMORY;
                } else if (!response.getAssertions().isEmpty()) {
                    ValidatedEditPlan validationResult =
                            validateAssertionsAgainstScopes(response, validationTest,
                                    contextExecutionResult, null, null);
                    recordAssertionDiagnostics(validationResult.getDiagnostics(), response,
                            parseResult.getRawAssertions(), testIndex, minimizationResult,
                            "initial", "validation");
                    response = validationResult.getResponse();
                    initialValidationDiagnostics = validationResult.getDiagnostics();
                    result.diagnosticCounters.add(validationResult.getDiagnostics());
                }
                if (stopReason == LlmPostProcessingPhase.StopReason.NONE
                        && phaseContext.isLowMemory()) {
                    stopReason = LlmPostProcessingPhase.StopReason.LOW_MEMORY;
                }
                if (stopReason == LlmPostProcessingPhase.StopReason.NONE
                        && phaseContext.isPhaseTimedOut()) {
                    stopReason = LlmPostProcessingPhase.StopReason.TIMEOUT;
                }
                if (stopReason == LlmPostProcessingPhase.StopReason.NONE) {
                    result.set(PostProcessingCounter.ASSERTIONS_ACCEPTED_INITIAL, response.getAssertions().size());
                    AssertionRepairResult repairResult =
                            repairRejectedAssertionsIfPossible(parseResult,
                                    response, initialValidationDiagnostics, oracleContext, validationTest,
                                    contextExecutionResult, limits,
                                    requestedCallsBeforeTest
                                            + result.get(PostProcessingCounter.REQUESTED_CALLS), testIndex,
                                    minimizationResult);
                    recordAssertionDiagnostics(repairResult.diagnostics, repairResult.response,
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
            recordAssertionLifecycle(response.getAssertions(), testIndex,
                    minimizationResult, "accepted_final", "final");
            LlmPostProcessingEditApplier.ApplyResult applied = LlmPostProcessingEditApplier.apply(
                    test, oracleContext.getReferences(), response, assertionEligible, contextExecutionResult,
                    options);
            result.set(PostProcessingCounter.TEST_NAMES_APPLIED, applied.getTestNamesApplied());
            result.set(PostProcessingCounter.VARIABLE_NAMES_APPLIED, applied.getVariableNamesApplied());
            result.set(PostProcessingCounter.COMMENTS_APPLIED, applied.getCommentsApplied());
            result.set(PostProcessingCounter.SECTION_BREAKS_APPLIED, applied.getSectionBreaksApplied());
            result.set(PostProcessingCounter.ASSERTIONS_APPLIED, applied.getAssertionsApplied());
            recordAppliedAssertionLifecycle(response, applied.getAppliedAssertions(), testIndex,
                    minimizationResult);
            if (result.stopReason == LlmPostProcessingPhase.StopReason.LOW_MEMORY
                    || phaseContext.isLowMemory()) {
                result.stopReason = LlmPostProcessingPhase.StopReason.LOW_MEMORY;
                if (options.features().assertions() && result.get(PostProcessingCounter.ASSERTIONS_APPLIED) == 0) {
                    phaseContext.recordFailedAssertionTest(test);
                }
                result.set(PostProcessingCounter.ACCEPTED_RESPONSES, 1);
                result.set(PostProcessingCounter.PARTIALLY_PROCESSED_TESTS, 1);
                return result;
            }
            if (applied.getAssertionsApplied() == 0) {
                result.fallbackCounters.add(runAssertionFallback(test, assertionEligible,
                        LlmPostProcessor.FallbackTrigger.NO_ACCEPTED_ASSERTIONS));
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
            result.fallbackCounters.add(runAssertionFallback(test, assertionEligible,
                        LlmPostProcessor.FallbackTrigger.INFRASTRUCTURE_FAILURE));
            return result;
        }
    }

    String queryWithPostProcessingTraceContext(PromptResult prompt, int testIndex,
                                               MinimizationResult minimizationResult,
                                               int repairAttempt) {
        return queryAttributed(testIndex, minimizationResult, repairAttempt <= 1
                ? LlmRequest.of(prompt, LlmFeature.POST_PROCESSING)
                : LlmRequest.of(prompt.getMessages(), LlmFeature.POST_PROCESSING)
                        .withRepairAttempt(repairAttempt));
    }

    String queryWithPostProcessingTraceContext(List<LlmMessage> messages, int testIndex,
                                               MinimizationResult minimizationResult,
                                               int repairAttempt) {
        return queryAttributed(testIndex, minimizationResult,
                LlmRequest.of(messages, LlmFeature.POST_PROCESSING).withRepairAttempt(repairAttempt));
    }

    private String queryAttributed(int testIndex, MinimizationResult minimizationResult,
                                   LlmRequest request) {
        ensureNotInterrupted();
        return llmService.query(request
                .withPostProcessingAttribution(minimizationResult.getStatus().name(),
                        minimizationResult.getUnderlyingStopCause().name(), testIndex)
                .withDeadlineNanos(phaseContext.phaseDeadlineNanos())).getText();
    }

    /**
     * Fails before the service takes a budget permit: with the flag already
     * set, the provider call would be cancelled as soon as it started.
     */
    private static void ensureNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new LlmCallFailedException("LLM query interrupted",
                    new InterruptedException("post-processing thread interrupted"), false);
        }
    }

    /**
     * Whether assertion lifecycle and diagnostic records are written. They only feed the trace,
     * so without one there is nothing to build or to keep for the final reconciliation.
     */
    private boolean isTracing() {
        return llmService != null && llmService.isTraceEnabled();
    }

    void recordAssertionLifecycle(
            List<AssertionProposal> proposals,
            int testIndex,
            MinimizationResult minimizationResult,
            String lifecycleState,
            String callKind) {
        if (proposals == null || !isTracing()) {
            return;
        }
        for (AssertionProposal proposal : proposals) {
            recordAssertionLifecycle(llmService, proposal, testIndex, minimizationResult,
                    lifecycleState, callKind);
        }
    }

    static void recordAssertionLifecycle(
            LlmService service,
            AssertionProposal proposal,
            int testIndex,
            MinimizationResult minimizationResult,
            String lifecycleState,
            String callKind) {
        if (service == null || proposal == null) {
            return;
        }
        service.recordPostProcessingAssertionLifecycle(
                new LlmTraceRecorder.PostProcessingAssertionLifecycleRecord(
                        testIndex,
                        minimizationResult == null ? "" : minimizationResult.getStatus().name(),
                        minimizationResult == null ? "" : minimizationResult.getUnderlyingStopCause().name(),
                        lifecycleState,
                        callKind,
                        proposal.getAssertionId(),
                        AssertionProposal.kindOrEmpty(proposal),
                        proposal.getActual(),
                        proposal.getExpected(),
                        proposal.getDelta(),
                        proposal.getIntent(),
                        "",
                        proposal.getPurpose(),
                        proposal.getSource(),
                        proposal.getCandidateId(),
                        "",
                        ""));
    }

    void recordAppliedAssertionLifecycle(
            LlmPostProcessingResponse response,
            List<TemplateCodeAssertion> appliedAssertions,
            int testIndex,
            MinimizationResult minimizationResult) {
        if (response == null || appliedAssertions == null || appliedAssertions.isEmpty() || !isTracing()) {
            return;
        }
        Map<String, AssertionProposal> proposals = LlmPostProcessingResponse.proposalsById(response);
        List<LlmPostProcessor.AppliedAssertionTrace> traces = phaseContext.session().appliedAssertions;
        for (TemplateCodeAssertion assertion : appliedAssertions) {
            AssertionProposal proposal = proposals.get(assertion.getAssertionId());
            if (proposal == null) {
                continue;
            }
            recordAssertionLifecycle(llmService, proposal, testIndex, minimizationResult,
                    "applied", "final");
            traces.add(new LlmPostProcessor.AppliedAssertionTrace(llmService, proposal, LlmPostProcessor.assertionSignatureForSession(assertion),
                    testIndex, minimizationResult));
        }
    }

    static boolean hasAssertionOpportunity(OracleContext context) {
        if (context == null) {
            return false;
        }
        boolean hasObservedResult = hasRepresentableCandidateFact(context);
        for (OracleContext.Observation observation : context.getObservations()) {
            if (observation != null && observation.isComplete()
                    && !"INPUT".equals(observation.getProvenance())) {
                hasObservedResult = true;
                break;
            }
        }
        boolean hasRepresentableOperand = !context.getReferences().getVariableIds().isEmpty();
        return hasObservedResult && hasRepresentableOperand;
    }

    private static boolean hasRepresentableCandidateFact(OracleContext context) {
        for (OracleContext.CandidateFact fact : context.getCandidateFacts()) {
            if (fact == null) {
                continue;
            }
            if (fact.getSourceId() != null
                    && context.getReferences().hasVariableId(fact.getSourceId())) {
                return true;
            }
            for (String referencedId : fact.getReferencedIds()) {
                if (context.getReferences().hasVariableId(referencedId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Candidate generation attaches its findings to the executed clone. Keep the
     * detached candidate objects for prompt facts and duplicate detection, but do
     * not let their rendered JUnit calls leak into the generated-statement prompt
     * or participate in later validation of LLM proposals.
     */
    static void detachCandidateAssertions(
            CompleteAssertionGenerator.CandidateCollection candidateCollection) {
        if (candidateCollection != null && candidateCollection.getTest() != null) {
            candidateCollection.getTest().removeAssertions();
        }
    }

    ValidatedEditPlan validateAssertionsAgainstScopes(
            LlmPostProcessingResponse response, TestCase validationTest, ExecutionResult executionResult,
            TestCase reusableStabilityTest, ExecutionResult reusableStabilityExecutionResult) {
        return assertionValidator.validate(response, validationTest, executionResult,
                reusableStabilityTest, reusableStabilityExecutionResult, phaseContext.options());
    }

    void recordAssertionDiagnostics(List<LlmPostProcessingParseResult.Diagnostic> diagnostics,
                                   LlmPostProcessingResponse response,
                                   List<LlmPostProcessingParseResult.RawAssertion> rawAssertions,
                                   int testIndex,
                                   MinimizationResult minimizationResult,
                                   String callKind,
                                   String diagnosticSource) {
        if (diagnostics == null || diagnostics.isEmpty() || !isTracing()) {
            return;
        }
        Map<String, AssertionProposal> proposalsById =
                LlmPostProcessingResponse.proposalsById(response);
        for (LlmPostProcessingParseResult.Diagnostic diagnostic : diagnostics) {
            if (diagnostic == null || !isAssertionDiagnostic(diagnostic)) {
                continue;
            }
            String pathToken = diagnostic.getAssertionId();
            if (pathToken == null && diagnostic.getAssertionIndex() >= 0) {
                pathToken = String.valueOf(diagnostic.getAssertionIndex());
            }
            AssertionProposal proposal =
                    proposalsById.get(pathToken);
            LlmPostProcessingParseResult.RawAssertion rawAssertion =
                    rawAssertion(rawAssertions, diagnostic);
            Map<String, Object> assertionJson = rawAssertion == null
                    ? AssertionProposal.toJson(proposal)
                    : rawAssertion.getFields();
            llmService.recordPostProcessingAssertionDiagnostic(
                    new LlmTraceRecorder.PostProcessingAssertionDiagnosticRecord(
                            testIndex,
                            minimizationResult == null ? "" : minimizationResult.getStatus().name(),
                            minimizationResult == null ? "" : minimizationResult.getUnderlyingStopCause().name(),
                            callKind,
                            diagnosticSource,
                            diagnostic.getCode() == null ? "" : diagnostic.getCode().name(),
                            diagnostic.getPath(),
                            diagnostic.getMessage(),
                            firstNonEmpty(AssertionProposal.idOrEmpty(proposal), rawField(rawAssertion, "assertionId"), pathToken),
                            firstNonEmpty(AssertionProposal.kindOrEmpty(proposal), rawField(rawAssertion, "kind")),
                            firstNonEmpty(AssertionProposal.actualOrEmpty(proposal), rawField(rawAssertion, "actual")),
                            firstNonEmpty(AssertionProposal.expectedOrEmpty(proposal), rawField(rawAssertion, "expected")),
                            firstNonEmpty(AssertionProposal.deltaOrEmpty(proposal), rawField(rawAssertion, "delta")),
                            firstNonEmpty(AssertionProposal.intentOrEmpty(proposal), rawField(rawAssertion, "intent")),
                            "",
                            firstNonEmpty(AssertionProposal.purposeOrEmpty(proposal), rawField(rawAssertion, "purpose")),
                            proposal == null
                                    ? (rawField(rawAssertion, "candidateId").isEmpty()
                                    ? "SYNTHESIZED" : "SELECTED_CANDIDATE")
                                    : proposal.getSource(),
                            firstNonEmpty(proposal == null ? "" : proposal.getCandidateId(),
                                    rawField(rawAssertion, "candidateId")),
                            assertionJson));
        }
    }

    private static boolean isAssertionDiagnostic(LlmPostProcessingParseResult.Diagnostic diagnostic) {
        return diagnostic.getAssertionId() != null || diagnostic.getAssertionIndex() >= 0;
    }

    private static LlmPostProcessingParseResult.RawAssertion rawAssertion(
            List<LlmPostProcessingParseResult.RawAssertion> rawAssertions,
            LlmPostProcessingParseResult.Diagnostic diagnostic) {
        if (rawAssertions == null || diagnostic == null) {
            return null;
        }
        for (LlmPostProcessingParseResult.RawAssertion assertion : rawAssertions) {
            if (assertion == null) {
                continue;
            }
            if (diagnostic.getAssertionId() != null
                    && diagnostic.getAssertionId().equals(assertion.getAssertionId())) {
                return assertion;
            }
            if (diagnostic.getAssertionIndex() >= 0
                    && diagnostic.getAssertionIndex() == assertion.getIndex()) {
                return assertion;
            }
        }
        return null;
    }

    private static String rawField(LlmPostProcessingParseResult.RawAssertion assertion, String fieldName) {
        if (assertion == null || fieldName == null) {
            return "";
        }
        Object value = assertion.getFields().get(fieldName);
        return value == null ? "" : String.valueOf(value);
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }

    AssertionRepairResult repairRejectedAssertionsIfPossible(
            LlmPostProcessingParseResult parseResult,
            LlmPostProcessingResponse acceptedResponse,
            List<LlmPostProcessingParseResult.Diagnostic> validationDiagnostics,
            OracleContext context,
            TestCase validationTest,
            ExecutionResult executionResult,
            LlmPostProcessor.ProcessingLimits limits,
            int requestedCalls,
            int testIndex,
            MinimizationResult minimizationResult) {
        PostProcessingOptions options = phaseContext.options();
        if (!options.repairFallbackPolicy().assertionRepairEnabled()) {
            return AssertionRepairResult.noCall(acceptedResponse);
        }
        if (options.repairFallbackPolicy().repairPolicy()
                == Properties.LlmPostProcessingRepairPolicy.NONE) {
            return AssertionRepairResult.noCall(acceptedResponse);
        }
        if (acceptedResponse.getAssertions().size() >= options.assertionPolicy().maxAssertions()) {
            return AssertionRepairResult.noCall(acceptedResponse);
        }

        List<LlmAssertionRepairer.RejectedAssertion> rejected =
                LlmAssertionRepairer.collectRepairableRejectedAssertions(
                        parseResult, acceptedResponse, validationDiagnostics);
        if (rejected.isEmpty()) {
            return AssertionRepairResult.noCall(acceptedResponse);
        }
        if (options.repairFallbackPolicy().repairPolicy()
                == Properties.LlmPostProcessingRepairPolicy.TARGETED_ONE
                && rejected.size() > 1) {
            rejected = new ArrayList<>(rejected.subList(0, 1));
        }
        if (!llmService.hasBudget(LlmFeature.POST_PROCESSING)
                || (limits.maxCalls > 0 && requestedCalls >= limits.maxCalls)) {
            return AssertionRepairResult.skippedBudget(acceptedResponse);
        }
        if (!phaseContext.canStartAnotherLlmCall()) {
            return AssertionRepairResult.noCall(acceptedResponse);
        }

        Set<String> repairableIds = new HashSet<>();
        for (LlmAssertionRepairer.RejectedAssertion candidate : rejected) {
            repairableIds.add(candidate.getAssertionId());
        }

        List<LlmMessage> repairMessages = LlmAssertionRepairer.buildRepairMessages(
                context, rejected, options);
        try {
            String rawRepairResponse = queryWithPostProcessingTraceContext(
                    repairMessages,
                    testIndex,
                    minimizationResult,
                    2);
            LlmPostProcessingParseResult repairParseResult = LlmAssertionRepairer.parseRepairResponse(
                    rawRepairResponse, context.toParseContext(), repairableIds);
            if (repairParseResult.isInfrastructureFailure()) {
                logger.debug("Unified LLM post-processing repair response could not be parsed: {}",
                        repairParseResult.getInfrastructureFailureReason());
                return AssertionRepairResult.called(acceptedResponse, rejected.size(),
                        0, 0, java.util.Collections.<LlmPostProcessingParseResult.Diagnostic>emptyList(),
                        java.util.Collections.<LlmPostProcessingParseResult.RawAssertion>emptyList());
            }
            ValidatedEditPlan validationResult = validateAssertionsAgainstScopes(
                    repairParseResult.getResponse(), validationTest, executionResult, null, null);
            List<LlmPostProcessingParseResult.Diagnostic> repairDiagnostics = new ArrayList<>();
            repairDiagnostics.addAll(repairParseResult.getDiagnostics());
            repairDiagnostics.addAll(validationResult.getDiagnostics());
            LlmPostProcessingResponse mergedResponse = mergeAcceptedAndRepairedAssertions(acceptedResponse,
                    validationResult.getResponse(), options.assertionPolicy().maxAssertions());
            int repairedAccepted = Math.max(0,
                    mergedResponse.getAssertions().size() - acceptedResponse.getAssertions().size());
            return AssertionRepairResult.called(mergedResponse, rejected.size(),
                    repairParseResult.getProposedCounts().getAssertions(), repairedAccepted,
                    repairDiagnostics, repairParseResult.getRawAssertions());
        } catch (LlmBudgetExceededException e) {
            throw e;
        } catch (RuntimeException e) {
            if (LlmPostProcessor.isInterruption(e)) {
                // Let the per-test processor stop the phase instead of
                // applying and falling back on an interrupted thread.
                throw e;
            }
            logger.debug("Unified LLM post-processing assertion repair skipped after failure: {}",
                    e.getMessage());
            return AssertionRepairResult.called(acceptedResponse, rejected.size(),
                    0, 0, java.util.Collections.<LlmPostProcessingParseResult.Diagnostic>emptyList(),
                    java.util.Collections.<LlmPostProcessingParseResult.RawAssertion>emptyList());
        }
    }

    private static LlmPostProcessingResponse mergeAcceptedAndRepairedAssertions(
            LlmPostProcessingResponse acceptedResponse,
            LlmPostProcessingResponse repairedResponse,
            int maxAssertions) {
        LlmPostProcessingResponse merged = acceptedResponse.withoutAssertions();
        Set<String> seenIds = new HashSet<>();
        int accepted = 0;
        for (AssertionProposal proposal : acceptedResponse.getAssertions()) {
            if (accepted >= maxAssertions) {
                return merged;
            }
            merged.addAssertion(proposal);
            seenIds.add(proposal.getAssertionId());
            accepted++;
        }
        for (AssertionProposal proposal : repairedResponse.getAssertions()) {
            if (accepted >= maxAssertions) {
                break;
            }
            if (seenIds.add(proposal.getAssertionId())) {
                merged.addAssertion(proposal);
                accepted++;
            }
        }
        return merged;
    }

    LlmPostProcessor.FallbackCounters runAssertionFallback(TestCase test, boolean assertionEligible,
                                                           LlmPostProcessor.FallbackTrigger trigger) {
        PostProcessingOptions options = phaseContext.options();
        if (!shouldRunAssertionFallback(assertionEligible, trigger, options)) {
            return LlmPostProcessor.FallbackCounters.none();
        }
        try {
            int applied = assertionFallbackRunner.applyFallbackAssertions(test);
            return LlmPostProcessor.FallbackCounters.applied(trigger, applied);
        } catch (RuntimeException | AssertionError e) {
            logger.warn("Unified LLM post-processing assertion fallback failed: {}", e.getMessage());
            return LlmPostProcessor.FallbackCounters.applied(trigger, 0);
        }
    }

    private static boolean shouldRunAssertionFallback(boolean assertionEligible, LlmPostProcessor.FallbackTrigger trigger,
                                                      PostProcessingOptions options) {
        if (!options.features().assertions() || !assertionEligible) {
            return false;
        }
        if (options.repairFallbackPolicy().fallback()
                == Properties.LlmPostProcessingAssertionFallback.NONE) {
            return false;
        }
        if (trigger == LlmPostProcessor.FallbackTrigger.INFRASTRUCTURE_FAILURE) {
            return options.repairFallbackPolicy().fallback()
                    == Properties.LlmPostProcessingAssertionFallback.ON_INFRASTRUCTURE_FAILURE
                    || options.repairFallbackPolicy().fallback()
                    == Properties.LlmPostProcessingAssertionFallback.ON_NO_ACCEPTED_ASSERTIONS;
        }
        return options.repairFallbackPolicy().fallback()
                == Properties.LlmPostProcessingAssertionFallback.ON_NO_ACCEPTED_ASSERTIONS;
    }

    static final class AssertionRepairResult {
        final LlmPostProcessingResponse response;
        final int calls;
        final int callsSkippedBudget;
        final int assertionsRequested;
        final int assertionsProposed;
        final int assertionsAccepted;
        final List<LlmPostProcessingParseResult.Diagnostic> diagnostics;
        final List<LlmPostProcessingParseResult.RawAssertion> rawAssertions;

        private AssertionRepairResult(LlmPostProcessingResponse response, int calls, int callsSkippedBudget,
                                      int assertionsRequested, int assertionsProposed, int assertionsAccepted,
                                      List<LlmPostProcessingParseResult.Diagnostic> diagnostics,
                                      List<LlmPostProcessingParseResult.RawAssertion> rawAssertions) {
            this.response = response;
            this.calls = calls;
            this.callsSkippedBudget = callsSkippedBudget;
            this.assertionsRequested = assertionsRequested;
            this.assertionsProposed = assertionsProposed;
            this.assertionsAccepted = assertionsAccepted;
            this.diagnostics = diagnostics == null
                    ? java.util.Collections.<LlmPostProcessingParseResult.Diagnostic>emptyList()
                    : diagnostics;
            this.rawAssertions = rawAssertions == null
                    ? java.util.Collections.<LlmPostProcessingParseResult.RawAssertion>emptyList()
                    : rawAssertions;
        }

        private static AssertionRepairResult noCall(LlmPostProcessingResponse response) {
            return new AssertionRepairResult(response, 0, 0, 0, 0, 0,
                    java.util.Collections.<LlmPostProcessingParseResult.Diagnostic>emptyList(), null);
        }

        private static AssertionRepairResult skippedBudget(LlmPostProcessingResponse response) {
            return new AssertionRepairResult(response, 0, 1, 0, 0, 0,
                    java.util.Collections.<LlmPostProcessingParseResult.Diagnostic>emptyList(), null);
        }

        private static AssertionRepairResult called(LlmPostProcessingResponse response,
                                                    int assertionsRequested,
                                                    int assertionsProposed,
                                                    int assertionsAccepted,
                                                    List<LlmPostProcessingParseResult.Diagnostic> diagnostics,
                                                    List<LlmPostProcessingParseResult.RawAssertion> rawAssertions) {
            return new AssertionRepairResult(response, 1, 0, assertionsRequested,
                    assertionsProposed, assertionsAccepted, diagnostics, rawAssertions);
        }
    }
}
