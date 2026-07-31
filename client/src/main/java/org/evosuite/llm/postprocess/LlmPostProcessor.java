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
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testsuite.MinimizationResult;
import org.evosuite.testsuite.MinimizationStatus;
import org.evosuite.testsuite.MinimizationStopCause;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrator for unified LLM post-processing. The unified phase runs after
 * structural post-processing and before test writing.
 *
 * <p>All features degrade gracefully on LLM failure—no feature failure blocks
 * test output generation.
 */
public class LlmPostProcessor {

    private static final Logger logger = LoggerFactory.getLogger(LlmPostProcessor.class);
    private static final PostProcessingSession LEGACY_SESSION = new PostProcessingSession();

    private final LlmService llmService;
    private final AssertionFallbackRunner assertionFallbackRunner;
    final AssertionCandidateRunner assertionCandidateRunner;
    final StabilityExecutionRunner stabilityExecutionRunner;
    private final AssertionEvaluationRunner assertionEvaluationRunner;
    private final PostProcessingAssertionValidator assertionValidator;
    final ResourceGuard resourceGuard;
    private final PhaseClock phaseClock;
    PostProcessingOptions options;
    private final PostProcessingSession session = new PostProcessingSession();

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
                PostProcessingAssertionValidator.defaultEvaluationRunner());
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
        this.assertionValidator = new PostProcessingAssertionValidator(
                stabilityExecutionRunner, assertionEvaluationRunner);
        this.resourceGuard = resourceGuard == null ? new DefaultResourceGuard() : resourceGuard;
        this.phaseClock = phaseClock == null ? new SystemPhaseClock() : phaseClock;
        this.options = PostProcessingOptions.fromProperties();
    }

    /**
     * Check if unified LLM post-processing is enabled, has at least one selected
     * response feature, and the LLM provider is configured.
     */
    public static boolean isAnyFeatureEnabled() {
        return isAnyFeatureEnabled(PostProcessingOptions.fromProperties());
    }

    static boolean isAnyFeatureEnabled(PostProcessingOptions options) {
        if (options == null || !options.enabled()) {
            return false;
        }
        if (options.provider() == Properties.LlmProvider.NONE) {
            return false;
        }
        return isAnyResponseFeatureEnabled(options);
    }

    /**
     * Whether the unified response schema has any enabled edit category.
     */
    public static boolean isAnyResponseFeatureEnabled() {
        return isAnyResponseFeatureEnabled(PostProcessingOptions.fromProperties());
    }

    static boolean isAnyResponseFeatureEnabled(PostProcessingOptions options) {
        return options != null && options.features().any();
    }

    /**
     * Run unified LLM post-processing.
     *
     * @param suite the final structurally post-processed suite
     * @return number of unified assertions successfully applied during the phase
     */
    public int runUnifiedPostProcessing(TestSuiteChromosome suite) {
        return runUnifiedPostProcessing(suite, MinimizationResult.disabled(suite));
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
    public int runUnifiedPostProcessing(TestSuiteChromosome suite, boolean minimizationIncomplete) {
        MinimizationResult result;
        if (minimizationIncomplete) {
            int tests = suite == null ? 0 : suite.size();
            int length = suite == null ? 0 : suite.totalLengthOfTestCases();
            result = new MinimizationResult(MinimizationStatus.TIMED_OUT, MinimizationStopCause.TIMEOUT,
                    tests, length, tests, length, 0L);
        } else {
            result = MinimizationResult.disabled(suite);
        }
        return runUnifiedPostProcessing(suite, result);
    }

    /**
     * Run unified LLM post-processing.
     *
     * @param suite the final structurally post-processed suite
     * @param minimizationResult explicit result of the preceding minimization phase
     * @return number of unified assertions successfully applied during the phase
     */
    public int runUnifiedPostProcessing(TestSuiteChromosome suite, MinimizationResult minimizationResult) {
        // Take exactly one configuration snapshot for this phase.  Subsequent
        // collaborators receive this snapshot instead of observing mutable
        // Properties at different points in the request lifecycle.
        options = PostProcessingOptions.fromProperties();
        PostProcessingAssertionValidator.setOptions(assertionEvaluationRunner, options);
        session.clear();
        MinimizationResult effectiveMinimizationResult = minimizationResult == null
                ? MinimizationResult.disabled(suite)
                : minimizationResult;
        publishMinimizationContext(effectiveMinimizationResult);
        if (!options.enabled()) {
            publishZeroMetrics("disabled");
            return 0;
        }
        if (effectiveMinimizationResult.isIncomplete()
                && options.phaseBudget().incompletePolicy()
                == Properties.LlmPostProcessingOnIncompleteMinimization.SKIP) {
            logger.info("Unified LLM post-processing skipped: minimization incomplete ({})",
                    effectiveMinimizationResult.getStatus());
            publishZeroMetrics("incomplete_minimization_" + effectiveMinimizationResult.getStatus().name());
            return 0;
        }
        if (options.provider() == Properties.LlmProvider.NONE) {
            logger.info("Unified LLM post-processing skipped: no LLM provider configured");
            publishZeroMetrics("no_provider");
            return 0;
        }
        if (!options.features().any()) {
            logger.info("Unified LLM post-processing skipped: no response features enabled");
            publishZeroMetrics("no_features_enabled");
            return 0;
        }
        if (suite == null || suite.size() == 0) {
            publishZeroMetrics("empty_suite");
            return 0;
        }
        if (llmService == null || !llmService.isAvailable() || !llmService.hasBudget()) {
            logger.info("Unified LLM post-processing skipped: LLM service unavailable or budget exhausted");
            publishZeroMetrics("service_unavailable_or_no_budget");
            return 0;
        }

        LlmPostProcessingPhase.Result phaseResult = new LlmPostProcessingPhase(this).run(
                suite, effectiveMinimizationResult, options);
        publishMetrics(phaseResult.metrics);
        // Keep only the deprecated static bridge usable for legacy callers;
        // production reconciliation receives this.session explicitly.
        LEGACY_SESSION.clear();
        LEGACY_SESSION.appliedAssertions.addAll(session.appliedAssertions);
        LEGACY_SESSION.compileRemovedAssertions.putAll(session.compileRemovedAssertions);
        return phaseResult.assertionsApplied;
    }

    boolean isPhaseTimedOut(long phaseStartMillis) {
        int timeoutSeconds = options.phaseBudget().timeoutSeconds();
        if (timeoutSeconds <= 0) {
            return false;
        }
        long elapsedMillis = phaseClock.currentTimeMillis() - phaseStartMillis;
        return elapsedMillis >= timeoutSeconds * 1000L;
    }

    boolean canStartAnotherLlmCall(long phaseStartMillis) {
        int timeoutSeconds = options.phaseBudget().timeoutSeconds();
        if (timeoutSeconds <= 0) {
            return true;
        }
        long elapsedMillis = phaseClock.currentTimeMillis() - phaseStartMillis;
        long remainingMillis = timeoutSeconds * 1000L - elapsedMillis;
        long callWindowMillis = Math.max(1000L, options.callTimeoutSeconds() * 1000L);
        return remainingMillis > callWindowMillis;
    }

    long phaseStartMillis() {
        return phaseClock.currentTimeMillis();
    }

    boolean isLowMemory() {
        return resourceGuard.isLowMemory(options.minimumFreeMemoryBytes());
    }

    boolean hasLlmBudget() {
        return llmService != null && llmService.hasBudget();
    }

    String queryWithPostProcessingTraceContext(PromptResult prompt, int testIndex,
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

    String queryWithPostProcessingTraceContext(List<LlmMessage> messages, int testIndex,
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

    /** Publish a skipped phase using this processor's explicit session. */
    public void publishSkippedPostProcessingMetricsForPhase(String skipReason,
                                                            MinimizationResult minimizationResult) {
        publishMinimizationContext(minimizationResult == null
                ? MinimizationResult.disabled(null)
                : minimizationResult);
        publishZeroMetrics(skipReason);
    }

    private static void publishZeroMetrics(String skipReason) {
        publishMetrics(new PostProcessingMetrics(skipReason));
    }

    private static void publishMinimizationContext(MinimizationResult minimizationResult) {
        PostProcessingTelemetryPublisher.publishMinimizationContext(minimizationResult);
    }

    private static void publishMetrics(PostProcessingMetrics metrics) {
        PostProcessingTelemetryPublisher.publish(metrics);
    }

    public static int countUnifiedTemplateAssertions(TestSuiteChromosome suite) {
        return PostProcessingAssertionReconciler.countTemplateAssertions(suite);
    }

    public static void publishFinalAssertionReconciliation(TestSuiteChromosome suite,
                                                           int initiallyAppliedAssertions) {
        PostProcessingAssertionReconciler.Reconciliation reconciliation =
                PostProcessingAssertionReconciler.reconcile(suite,
                initiallyAppliedAssertions);
        int removedCompile = compileRemovedAssertionCount();
        PostProcessingTelemetryPublisher.publishAssertionReconciliation(
                Math.max(0, reconciliation.removedUnstable() - removedCompile),
                removedCompile, reconciliation.shipped());
        publishFinalAssertionLifecycle(LEGACY_SESSION, suite);
    }

    /** Publish final reconciliation for this phase's explicit session. */
    public void publishFinalAssertionReconciliationForPhase(TestSuiteChromosome suite,
                                                            int initiallyAppliedAssertions) {
        PostProcessingAssertionReconciler.Reconciliation reconciliation =
                PostProcessingAssertionReconciler.reconcile(suite,
                initiallyAppliedAssertions);
        int removedCompile = compileRemovedAssertionCount(session);
        PostProcessingTelemetryPublisher.publishAssertionReconciliation(
                Math.max(0, reconciliation.removedUnstable() - removedCompile),
                removedCompile, reconciliation.shipped());
        publishFinalAssertionLifecycle(session, suite);
    }

    /**
     * Preserve the reason assertions disappeared when the final JUnit compiler
     * removes their containing test. The final reconciliation otherwise only
     * sees that the assertion is absent and would misclassify it as unstable.
     */
    public static void recordAssertionsRemovedByCompileFilter(Collection<TestCase> removedTests) {
        if (removedTests == null || removedTests.isEmpty()) {
            return;
        }
        Map<String, Integer> removed = LEGACY_SESSION.compileRemovedAssertions;
        for (TestCase test : removedTests) {
            if (test == null) {
                continue;
            }
            for (Assertion assertion : test.getAssertions()) {
                if (assertion instanceof TemplateCodeAssertion) {
                    String signature = assertionSignatureForSession((TemplateCodeAssertion) assertion);
                    removed.put(signature, removed.getOrDefault(signature, 0) + 1);
                }
            }
        }
    }

    public void recordAssertionsRemovedByCompileFilterForPhase(Collection<TestCase> removedTests) {
        session.recordCompileRemoved(removedTests);
    }

    private static int compileRemovedAssertionCount() {
        Map<String, Integer> removed = LEGACY_SESSION.compileRemovedAssertions;
        return compileRemovedAssertionCount(removed);
    }

    private static int compileRemovedAssertionCount(PostProcessingSession session) {
        return compileRemovedAssertionCount(session.compileRemovedAssertions);
    }

    private static int compileRemovedAssertionCount(Map<String, Integer> removed) {
        int count = 0;
        for (Integer occurrences : removed.values()) {
            if (occurrences != null && occurrences > 0) {
                count += occurrences;
            }
        }
        return count;
    }

    void recordAssertionLifecycle(
            List<LlmPostProcessingResponse.AssertionProposal> proposals,
            int testIndex,
            MinimizationResult minimizationResult,
            String lifecycleState,
            String callKind) {
        if (proposals == null || llmService == null) {
            return;
        }
        for (LlmPostProcessingResponse.AssertionProposal proposal : proposals) {
            recordAssertionLifecycle(llmService, proposal, testIndex, minimizationResult,
                    lifecycleState, callKind);
        }
    }

    private static void recordAssertionLifecycle(
            LlmService service,
            LlmPostProcessingResponse.AssertionProposal proposal,
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
                        assertionKind(proposal),
                        proposal.getActual(),
                        proposal.getExpected(),
                        proposal.getDelta(),
                        proposal.getIntent(),
                        proposal.getAfterStatementId(),
                        proposal.getPurpose(),
                        proposal.getSource(),
                        proposal.getCandidateId(),
                        proposal.getSite() == null ? "" : proposal.getSite().name(),
                        proposal.getExceptionId()));
    }

    void recordAppliedAssertionLifecycle(
            LlmPostProcessingResponse response,
            List<TemplateCodeAssertion> appliedAssertions,
            int testIndex,
            MinimizationResult minimizationResult) {
        if (response == null || appliedAssertions == null || appliedAssertions.isEmpty()) {
            return;
        }
        Map<String, LlmPostProcessingResponse.AssertionProposal> proposals = proposalsById(response);
        List<AppliedAssertionTrace> traces = session.appliedAssertions;
        for (TemplateCodeAssertion assertion : appliedAssertions) {
            LlmPostProcessingResponse.AssertionProposal proposal = proposals.get(assertion.getAssertionId());
            if (proposal == null) {
                continue;
            }
            recordAssertionLifecycle(llmService, proposal, testIndex, minimizationResult,
                    "applied", "final");
            traces.add(new AppliedAssertionTrace(llmService, proposal, assertionSignatureForSession(assertion),
                    testIndex, minimizationResult));
        }
    }

    private static void publishFinalAssertionLifecycle(PostProcessingSession session,
                                                       TestSuiteChromosome suite) {
        List<AppliedAssertionTrace> applied = session.appliedAssertions;
        Map<String, Integer> removedCompile = session.compileRemovedAssertions;
        if (applied.isEmpty()) {
            return;
        }
        Map<String, Integer> shipped = new HashMap<>();
        if (suite != null) {
            for (TestChromosome chromosome : suite.getTestChromosomes()) {
                if (chromosome == null || chromosome.getTestCase() == null
                        || chromosome.getTestCase().isUnstable()) {
                    continue;
                }
                for (Assertion assertion : chromosome.getTestCase().getAssertions()) {
                    if (assertion instanceof TemplateCodeAssertion) {
                        String signature = assertionSignatureForSession((TemplateCodeAssertion) assertion);
                        shipped.put(signature, shipped.getOrDefault(signature, 0) + 1);
                    }
                }
            }
        }
        for (AppliedAssertionTrace trace : applied) {
            int remaining = shipped.getOrDefault(trace.signature, 0);
            int compileRemoved = removedCompile == null
                    ? 0 : removedCompile.getOrDefault(trace.signature, 0);
            String state;
            if (remaining > 0) {
                state = "shipped";
                shipped.put(trace.signature, remaining - 1);
            } else if (compileRemoved > 0) {
                state = "removed_compile";
                removedCompile.put(trace.signature, compileRemoved - 1);
            } else {
                state = "removed_unstable";
            }
            recordAssertionLifecycle(trace.service, trace.proposal, trace.testIndex,
                    trace.minimizationResult, state, "final_validation");
        }
    }

    static String assertionSignatureForSession(TemplateCodeAssertion assertion) {
        return String.valueOf(assertion.getAssertionId()) + '\u001f'
                + String.valueOf(assertion.getKind()) + '\u001f'
                + assertion.render(null) + '\u001f'
                + String.valueOf(assertion.getPurpose());
    }

    /** Compatibility view retained for package-level reconciliation tests. */
    static FinalAssertionReconciliation finalAssertionReconciliation(
            TestSuiteChromosome suite, int initiallyAppliedAssertions) {
        PostProcessingAssertionReconciler.Reconciliation result =
                PostProcessingAssertionReconciler.reconcile(suite, initiallyAppliedAssertions);
        return new FinalAssertionReconciliation(result.shipped(), result.removedUnstable());
    }

    static int remainingItems(List<WorkItem> workItems, int startIndex) {
        if (workItems == null || startIndex >= workItems.size()) {
            return 0;
        }
        return Math.max(0, workItems.size() - Math.max(0, startIndex));
    }

    boolean isAssertionEligibleForVersion1(TestChromosome chromosome) {
        if (chromosome == null || chromosome.getTestCase() == null || chromosome.getTestCase().size() == 0) {
            return false;
        }
        ExecutionResult result = executionResultForEligibility(chromosome);
        if (result == null) {
            return false;
        }
        if (result.hasTimeout() || result.hasTestException()) {
            return false;
        }
        return result.noThrownExceptions();
    }

    static boolean hasAssertionOpportunity(LlmPostProcessingPromptContext context) {
        if (context == null) {
            return false;
        }
        boolean hasObservedResult = hasRepresentableCandidateFact(context);
        for (LlmPostProcessingPromptContext.Observation observation : context.getObservations()) {
            if (observation != null && observation.isComplete()
                    && !"INPUT".equals(observation.getProvenance())) {
                hasObservedResult = true;
                break;
            }
        }
        if (!hasObservedResult) {
            for (LlmPostProcessingPromptContext.CallableMember callable : context.getCallableMembers()) {
                if (callable != null && callable.getObservedResult() != null) {
                    hasObservedResult = true;
                    break;
                }
            }
        }
        boolean hasRepresentableOperand = !context.getReferences().getVariableIds().isEmpty();
        return hasObservedResult && hasRepresentableOperand;
    }

    private static boolean hasRepresentableCandidateFact(LlmPostProcessingPromptContext context) {
        for (LlmPostProcessingPromptContext.CandidateFact fact : context.getCandidateFacts()) {
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

    AssertionValidationResult validateAssertionsAgainstScopes(
            LlmPostProcessingResponse response, TestCase validationTest, ExecutionResult executionResult) {
        return validateAssertionsAgainstScopes(response, validationTest, executionResult, null, null);
    }

    AssertionValidationResult validateAssertionsAgainstScopes(
            LlmPostProcessingResponse response, TestCase validationTest, ExecutionResult executionResult,
            TestCase reusableStabilityTest, ExecutionResult reusableStabilityExecutionResult) {
        return assertionValidator.validate(response, validationTest, executionResult,
                reusableStabilityTest, reusableStabilityExecutionResult, options);
    }

    void recordAssertionDiagnostics(List<LlmPostProcessingParseResult.Diagnostic> diagnostics,
                                   LlmPostProcessingResponse response,
                                   List<LlmPostProcessingParseResult.RawAssertion> rawAssertions,
                                   int testIndex,
                                   MinimizationResult minimizationResult,
                                   String callKind,
                                   String diagnosticSource) {
        if (diagnostics == null || diagnostics.isEmpty() || llmService == null) {
            return;
        }
        Map<String, LlmPostProcessingResponse.AssertionProposal> proposalsById =
                proposalsById(response);
        for (LlmPostProcessingParseResult.Diagnostic diagnostic : diagnostics) {
            if (diagnostic == null || !isAssertionDiagnostic(diagnostic)) {
                continue;
            }
            String pathToken = diagnostic.getAssertionId();
            if (pathToken == null && diagnostic.getAssertionIndex() >= 0) {
                pathToken = String.valueOf(diagnostic.getAssertionIndex());
            }
            LlmPostProcessingResponse.AssertionProposal proposal =
                    proposalsById.get(pathToken);
            LlmPostProcessingParseResult.RawAssertion rawAssertion =
                    rawAssertion(rawAssertions, diagnostic);
            Map<String, Object> assertionJson = rawAssertion == null
                    ? assertionJson(proposal)
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
                            firstNonEmpty(assertionId(proposal), rawField(rawAssertion, "assertionId"), pathToken),
                            firstNonEmpty(assertionKind(proposal), rawField(rawAssertion, "kind")),
                            firstNonEmpty(assertionActual(proposal), rawField(rawAssertion, "actual")),
                            firstNonEmpty(assertionExpected(proposal), rawField(rawAssertion, "expected")),
                            firstNonEmpty(assertionDelta(proposal), rawField(rawAssertion, "delta")),
                            firstNonEmpty(assertionIntent(proposal), rawField(rawAssertion, "intent")),
                            firstNonEmpty(assertionPlacement(proposal), rawPlacement(rawAssertion)),
                            firstNonEmpty(assertionPurpose(proposal), rawField(rawAssertion, "purpose")),
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

    private static Map<String, LlmPostProcessingResponse.AssertionProposal> proposalsById(
            LlmPostProcessingResponse response) {
        Map<String, LlmPostProcessingResponse.AssertionProposal> proposals = new LinkedHashMap<>();
        if (response == null) {
            return proposals;
        }
        for (LlmPostProcessingResponse.AssertionProposal proposal : response.getAssertions()) {
            if (proposal != null && proposal.getAssertionId() != null) {
                proposals.put(proposal.getAssertionId(), proposal);
            }
        }
        return proposals;
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

    private static Map<String, Object> assertionJson(LlmPostProcessingResponse.AssertionProposal proposal) {
        if (proposal == null) {
            return java.util.Collections.emptyMap();
        }
        Map<String, Object> assertion = new LinkedHashMap<>();
        assertion.put("assertionId", proposal.getAssertionId());
        assertion.put("kind", assertionKind(proposal));
        assertion.put("actual", proposal.getActual());
        assertion.put("expected", proposal.getExpected());
        assertion.put("delta", proposal.getDelta());
        assertion.put("intent", proposal.getIntent());
        assertion.put("placementAfterStatementId", proposal.getAfterStatementId());
        assertion.put("purpose", proposal.getPurpose());
        return assertion;
    }

    private static String rawField(LlmPostProcessingParseResult.RawAssertion assertion, String fieldName) {
        if (assertion == null || fieldName == null) {
            return "";
        }
        Object value = assertion.getFields().get(fieldName);
        return value == null ? "" : String.valueOf(value);
    }

    private static String rawPlacement(LlmPostProcessingParseResult.RawAssertion assertion) {
        if (assertion == null) {
            return "";
        }
        Object placement = assertion.getFields().get("placement");
        if (placement instanceof Map) {
            Object after = ((Map<?, ?>) placement).get("afterStatementId");
            return after == null ? "" : String.valueOf(after);
        }
        return rawField(assertion, "afterStatementId");
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

    private static String assertionId(LlmPostProcessingResponse.AssertionProposal proposal) {
        return proposal == null ? "" : proposal.getAssertionId();
    }

    private static String assertionKind(LlmPostProcessingResponse.AssertionProposal proposal) {
        return proposal == null || proposal.getKind() == null ? "" : proposal.getKind().name();
    }

    private static String assertionActual(LlmPostProcessingResponse.AssertionProposal proposal) {
        return proposal == null ? "" : proposal.getActual();
    }

    private static String assertionExpected(LlmPostProcessingResponse.AssertionProposal proposal) {
        return proposal == null ? "" : proposal.getExpected();
    }

    private static String assertionDelta(LlmPostProcessingResponse.AssertionProposal proposal) {
        return proposal == null ? "" : proposal.getDelta();
    }

    private static String assertionIntent(LlmPostProcessingResponse.AssertionProposal proposal) {
        return proposal == null ? "" : proposal.getIntent();
    }

    private static String assertionPlacement(LlmPostProcessingResponse.AssertionProposal proposal) {
        return proposal == null ? "" : proposal.getAfterStatementId();
    }

    private static String assertionPurpose(LlmPostProcessingResponse.AssertionProposal proposal) {
        return proposal == null ? "" : proposal.getPurpose();
    }

    static LlmPostProcessingResponse withoutAssertions(LlmPostProcessingResponse response) {
        return response.withoutAssertions();
    }

    AssertionRepairResult repairRejectedAssertionsIfPossible(
            LlmPostProcessingParseResult parseResult,
            LlmPostProcessingResponse parsedResponse,
            LlmPostProcessingResponse acceptedResponse,
            List<LlmPostProcessingParseResult.Diagnostic> validationDiagnostics,
            OracleContext context,
            TestCase validationTest,
            ExecutionResult executionResult,
            ProcessingLimits limits,
            int requestedCalls,
            int testIndex,
            MinimizationResult minimizationResult,
            long phaseStartMillis,
            PostProcessingOptions options) {
        if (options.repairFallbackPolicy().assertionRepairAttempts() <= 0) {
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
                        parseResult, parsedResponse, acceptedResponse, validationDiagnostics);
        if (rejected.isEmpty()) {
            return AssertionRepairResult.noCall(acceptedResponse);
        }
        if (options.repairFallbackPolicy().repairPolicy()
                == Properties.LlmPostProcessingRepairPolicy.TARGETED_ONE
                && rejected.size() > 1) {
            rejected = new ArrayList<>(rejected.subList(0, 1));
        }
        if (!llmService.hasBudget() || (limits.maxCalls > 0 && requestedCalls >= limits.maxCalls)) {
            return AssertionRepairResult.skippedBudget(acceptedResponse);
        }
        if (!canStartAnotherLlmCall(phaseStartMillis)) {
            return AssertionRepairResult.noCall(acceptedResponse);
        }

        Set<String> repairableIds = new HashSet<>();
        for (LlmAssertionRepairer.RejectedAssertion candidate : rejected) {
            repairableIds.add(candidate.getAssertionId());
        }

        boolean callAttempted = false;
        String rawRepairResponse = null;
        try {
            callAttempted = true;
            rawRepairResponse = queryWithPostProcessingTraceContext(
                    LlmAssertionRepairer.buildRepairMessages(context, rejected, options),
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
            AssertionValidationResult validationResult = validateAssertionsAgainstScopes(
                    repairParseResult.getResponse(), validationTest,
                    executionResult);
            List<LlmPostProcessingParseResult.Diagnostic> repairDiagnostics = new ArrayList<>();
            repairDiagnostics.addAll(repairParseResult.getDiagnostics());
            repairDiagnostics.addAll(validationResult.plan.getDiagnostics());
            LlmPostProcessingResponse mergedResponse = mergeAcceptedAndRepairedAssertions(acceptedResponse,
                    validationResult.plan.getResponse(), options.assertionPolicy().maxAssertions());
            int repairedAccepted = Math.max(0,
                    mergedResponse.getAssertions().size() - acceptedResponse.getAssertions().size());
            return AssertionRepairResult.called(mergedResponse, rejected.size(),
                    repairParseResult.getProposedCounts().getAssertions(), repairedAccepted,
                    repairDiagnostics, repairParseResult.getRawAssertions());
        } catch (LlmBudgetExceededException e) {
            throw e;
        } catch (RuntimeException e) {
            logger.debug("Unified LLM post-processing assertion repair skipped after failure: {}",
                    e.getMessage());
            return callAttempted
                    ? AssertionRepairResult.called(acceptedResponse, rejected.size(),
                    0, 0, java.util.Collections.<LlmPostProcessingParseResult.Diagnostic>emptyList(),
                    java.util.Collections.<LlmPostProcessingParseResult.RawAssertion>emptyList())
                    : AssertionRepairResult.noCall(acceptedResponse);
        }
    }

    private static LlmPostProcessingResponse mergeAcceptedAndRepairedAssertions(
            LlmPostProcessingResponse acceptedResponse,
            LlmPostProcessingResponse repairedResponse,
            int maxAssertions) {
        LlmPostProcessingResponse merged = acceptedResponse.withoutAssertions();
        Set<String> seenIds = new HashSet<>();
        int accepted = 0;
        for (LlmPostProcessingResponse.AssertionProposal proposal : acceptedResponse.getAssertions()) {
            if (accepted >= maxAssertions) {
                return merged;
            }
            merged.addAssertion(proposal);
            seenIds.add(proposal.getAssertionId());
            accepted++;
        }
        for (LlmPostProcessingResponse.AssertionProposal proposal : repairedResponse.getAssertions()) {
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

    FallbackCounters runAssertionFallback(TestCase test, boolean assertionEligible,
                                          FallbackTrigger trigger,
                                          PostProcessingOptions options) {
        if (!shouldRunAssertionFallback(assertionEligible, trigger, options)) {
            return FallbackCounters.none();
        }
        try {
            int applied = assertionFallbackRunner.applyFallbackAssertions(test,
                    options.repairFallbackPolicy().fallbackStrategy());
            return FallbackCounters.applied(trigger, applied,
                    options.repairFallbackPolicy().fallbackStrategy());
        } catch (RuntimeException | AssertionError e) {
            logger.warn("Unified LLM post-processing assertion fallback failed: {}", e.getMessage());
            return FallbackCounters.applied(trigger, 0,
                    options.repairFallbackPolicy().fallbackStrategy());
        }
    }

    private static boolean shouldRunAssertionFallback(boolean assertionEligible, FallbackTrigger trigger,
                                                      PostProcessingOptions options) {
        if (!options.features().assertions() || !assertionEligible) {
            return false;
        }
        if (options.repairFallbackPolicy().fallback()
                == Properties.LlmPostProcessingAssertionFallback.NONE) {
            return false;
        }
        if (trigger == FallbackTrigger.INFRASTRUCTURE_FAILURE) {
            return options.repairFallbackPolicy().fallback()
                    == Properties.LlmPostProcessingAssertionFallback.ON_INFRASTRUCTURE_FAILURE
                    || options.repairFallbackPolicy().fallback()
                    == Properties.LlmPostProcessingAssertionFallback.ON_NO_ACCEPTED_ASSERTIONS;
        }
        return options.repairFallbackPolicy().fallback()
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

        default boolean isLowMemory(long minimumFreeMemoryBytes) {
            return isLowMemory();
        }
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
            return isLowMemory(0L);
        }

        @Override
        public boolean isLowMemory(long minimumFreeMemoryBytes) {
            Runtime runtime = Runtime.getRuntime();
            long threshold = Math.max(0L, minimumFreeMemoryBytes) * 2L;
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

    static final class PostProcessingMetrics {
        final String skipReason;
        int requestedTests;
        int requestedStatements;
        int initialCalls;
        int repairCalls;
        int repairCallsSkippedBudget;
        int acceptedResponses;
        int skippedTests;
        int capSkippedTests;
        int infrastructureFailures;
        DiagnosticCounters diagnosticCounters = new DiagnosticCounters();
        FallbackCounters fallbackCounters = new FallbackCounters();
        int processedTests;
        int partiallyProcessedTests;
        int testNamesProposed;
        int testNamesApplied;
        int variableNamesProposed;
        int variableNamesApplied;
        int commentsProposed;
        int commentsApplied;
        int sectionBreaksProposed;
        int sectionBreaksApplied;
        int assertionsProposed;
        int assertionsAcceptedInitial;
        int assertionsRepairRequested;
        int assertionsProposedAfterRepair;
        int assertionsAcceptedAfterRepair;
        int assertionsApplied;

        PostProcessingMetrics(String skipReason) {
            this.skipReason = skipReason;
        }
    }

    /** Per-test delta consumed by the suite-level aggregator. */
    static final class TestProcessingResult {
        int requestedTests;
        int calls;
        int requestedStatements;
        int initialCalls;
        int repairCalls;
        int repairCallsSkippedBudget;
        int acceptedTests;
        int skippedTests;
        int infrastructureFailures;
        final DiagnosticCounters diagnosticCounters = new DiagnosticCounters();
        final FallbackCounters fallbackCounters = new FallbackCounters();
        int processedTests;
        int partiallyProcessedTests;
        int testNamesProposed;
        int testNamesApplied;
        int variableNamesProposed;
        int variableNamesApplied;
        int commentsProposed;
        int commentsApplied;
        int sectionBreaksProposed;
        int sectionBreaksApplied;
        int assertionsProposed;
        int assertionsAcceptedInitial;
        int assertionsRepairRequested;
        int assertionsProposedAfterRepair;
        int assertionsAcceptedAfterRepair;
        int assertionsApplied;
        LlmPostProcessingPhase.StopReason stopReason = LlmPostProcessingPhase.StopReason.NONE;
    }

    enum FallbackTrigger {
        INFRASTRUCTURE_FAILURE,
        NO_ACCEPTED_ASSERTIONS
    }

    static final class WorkItem {
        final int originalIndex;
        final TestChromosome chromosome;
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

        static List<WorkItem> from(List<TestChromosome> tests, boolean limitedOrdering) {
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

    static final class DiagnosticCounters {
        private final Map<LlmPostProcessingParseResult.DiagnosticCode, Integer> byCode =
                new EnumMap<>(LlmPostProcessingParseResult.DiagnosticCode.class);
        int total;

        void add(LlmPostProcessingParseResult parseResult) {
            for (LlmPostProcessingParseResult.Diagnostic diagnostic : parseResult.getDiagnostics()) {
                add(diagnostic);
            }
        }

        void add(List<LlmPostProcessingParseResult.Diagnostic> diagnostics) {
            if (diagnostics == null) {
                return;
            }
            for (LlmPostProcessingParseResult.Diagnostic diagnostic : diagnostics) {
                add(diagnostic);
            }
        }

        void add(LlmPostProcessingParseResult.Diagnostic diagnostic) {
            if (diagnostic == null) {
                return;
            }
            total++;
            LlmPostProcessingParseResult.DiagnosticCode code = diagnostic.getCode();
            byCode.put(code, get(code) + 1);
        }

        void add(DiagnosticCounters other) {
            if (other == null) {
                return;
            }
            total += other.total;
            for (Map.Entry<LlmPostProcessingParseResult.DiagnosticCode, Integer> entry
                    : other.byCode.entrySet()) {
                byCode.put(entry.getKey(), get(entry.getKey()) + entry.getValue());
            }
        }

        int total() {
            return total;
        }

        int get(LlmPostProcessingParseResult.DiagnosticCode code) {
            Integer count = byCode.get(code);
            return count == null ? 0 : count;
        }
    }

    static final class FallbackCounters {
        int total;
        int infrastructure;
        int noAccepted;
        int all;
        int mutation;
        int assertionsApplied;

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

        void add(FallbackCounters other) {
            total += other.total;
            infrastructure += other.infrastructure;
            noAccepted += other.noAccepted;
            all += other.all;
            mutation += other.mutation;
            assertionsApplied += other.assertionsApplied;
        }
    }

    static final class ProcessingLimits {
        private static final int DEFAULT_LIMITED_MAX_TESTS = 20;
        private static final int DEFAULT_LIMITED_MAX_TOTAL_STATEMENTS = 400;
        private static final int DEFAULT_LIMITED_MAX_CALLS = 40;

        final int maxTests;
        final int maxTotalStatements;
        final int maxCalls;

        private ProcessingLimits(int maxTests, int maxTotalStatements, int maxCalls) {
            this.maxTests = maxTests;
            this.maxTotalStatements = maxTotalStatements;
            this.maxCalls = maxCalls;
        }

        static ProcessingLimits from(PostProcessingOptions.PhaseBudget budget,
                                     boolean minimizationIncomplete) {
            int maxTests = budget.maxTests();
            int maxTotalStatements = budget.maxTotalStatements();
            int maxCalls = budget.maxCalls();
            if (minimizationIncomplete
                    && budget.incompletePolicy()
                    == Properties.LlmPostProcessingOnIncompleteMinimization.LIMITED) {
                maxTests = boundedCap(maxTests,
                        nonZeroLimitedCap(budget.limitedMaxTests(),
                                DEFAULT_LIMITED_MAX_TESTS));
                maxTotalStatements = boundedCap(maxTotalStatements,
                        nonZeroLimitedCap(budget.limitedMaxTotalStatements(),
                                DEFAULT_LIMITED_MAX_TOTAL_STATEMENTS));
                maxCalls = nonZeroLimitedCap(budget.limitedMaxCalls(),
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

    static final class AssertionValidationResult {
        final ValidatedEditPlan plan;
        final LlmPostProcessingResponse response;
        final List<LlmPostProcessingParseResult.Diagnostic> diagnostics;

        AssertionValidationResult(LlmPostProcessingResponse response,
                                  List<LlmPostProcessingParseResult.Diagnostic> diagnostics) {
            this.response = response;
            this.diagnostics = diagnostics == null
                    ? java.util.Collections.<LlmPostProcessingParseResult.Diagnostic>emptyList()
                    : diagnostics;
            this.plan = ValidatedEditPlan.create(this.response, this.diagnostics);
        }

        static AssertionValidationResult success(LlmPostProcessingResponse response) {
            return new AssertionValidationResult(response,
                    java.util.Collections.<LlmPostProcessingParseResult.Diagnostic>emptyList());
        }

        static AssertionValidationResult rejectedAll(
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

    static final class AppliedAssertionTrace {
        private final LlmService service;
        private final LlmPostProcessingResponse.AssertionProposal proposal;
        private final String signature;
        private final int testIndex;
        private final MinimizationResult minimizationResult;

        private AppliedAssertionTrace(LlmService service,
                                      LlmPostProcessingResponse.AssertionProposal proposal,
                                      String signature,
                                      int testIndex,
                                      MinimizationResult minimizationResult) {
            this.service = service;
            this.proposal = proposal;
            this.signature = signature;
            this.testIndex = testIndex;
            this.minimizationResult = minimizationResult;
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

        LlmPostProcessingParseResult.DiagnosticCode diagnosticCode() {
            return diagnosticCode;
        }

        String message() {
            return message;
        }

        EvaluationOutcome asStabilityFailure() {
            if (isAccepted() || diagnosticCode == LlmPostProcessingParseResult.DiagnosticCode.COMPILE) {
                return this;
            }
            return stabilityFailure(message);
        }
    }

}
