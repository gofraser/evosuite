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

import org.evosuite.PhaseMemoryGuard;
import org.evosuite.Properties;
import org.evosuite.assertion.Assertion;
import org.evosuite.assertion.AssertionGenerator;
import org.evosuite.assertion.CompleteAssertionGenerator;
import org.evosuite.assertion.TemplateCodeAssertion;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmRunFeatures;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.postprocess.LlmPostProcessingResponse.AssertionProposal;
import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testsuite.MinimizationResult;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
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
    private final LlmService llmService;
    private final AssertionFallbackRunner assertionFallbackRunner;
    private final AssertionCandidateRunner assertionCandidateRunner;
    private final StabilityExecutionRunner stabilityExecutionRunner;
    private final PostProcessingAssertionValidator assertionValidator;
    private final ResourceGuard resourceGuard;
    private final PhaseClock phaseClock;

    /**
     * Creates a post-processor that queries the shared {@link LlmService} and uses the default runners, guards and
     * telemetry. When post-processing cannot run, no service is created and the post-processor has none.
     */
    public LlmPostProcessor() {
        this(LlmRunFeatures.postProcessing() ? LlmService.getInstance() : null,
                new TraceAssertionFallbackRunner(), new TraceAssertionCandidateRunner(),
                new DefaultStabilityExecutionRunner(),
                PostProcessingAssertionValidator.defaultEvaluationRunner(),
                new DefaultResourceGuard(), new SystemPhaseClock());
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
        this.assertionValidator = new PostProcessingAssertionValidator(
                stabilityExecutionRunner, assertionEvaluationRunner);
        this.resourceGuard = resourceGuard == null ? new DefaultResourceGuard() : resourceGuard;
        this.phaseClock = phaseClock == null ? new SystemPhaseClock() : phaseClock;
    }

    /** Create an isolated phase context from one configuration snapshot. */
    public LlmPostProcessingPhaseContext createPhaseContext() {
        return new LlmPostProcessingPhaseContext(
                PostProcessingOptions.fromProperties(),
                new PostProcessingSession(), phaseClock, resourceGuard);
    }

    /**
     * Cheap preflight used before suppressing EvoSuite's normal assertion
     * generation. This deliberately performs no provider request.
     */
    public boolean canStartUnifiedPostProcessing(LlmPostProcessingPhaseContext phaseContext) {
        if (phaseContext == null) {
            return false;
        }
        return isAnyFeatureEnabled(phaseContext.options())
                && llmService != null
                && llmService.canCall(LlmFeature.POST_PROCESSING);
    }

    /** Tracks every post-processing runtime variable at its empty default. */
    public static void initializeRuntimeVariables() {
        PostProcessingTelemetryPublisher.publish(new PostProcessingMetrics(""));
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Minimization_Status, "");
        ClientServices.track(RuntimeVariable.LLM_PostProcessing_Minimization_Stop_Cause, "");
    }

    /**
     * Check if unified LLM post-processing is enabled, has at least one selected
     * response feature, and the LLM provider is configured.
     */
    public static boolean isAnyFeatureEnabled() {
        return LlmRunFeatures.postProcessing();
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

    static boolean isAnyResponseFeatureEnabled(PostProcessingOptions options) {
        return options != null && options.features().any();
    }

    /**
     * Run unified post-processing with an explicit phase context. The context
     * is also used by the suite generator for final assertion reconciliation.
     */
    public int runUnifiedPostProcessing(TestSuiteChromosome suite,
                                        MinimizationResult minimizationResult,
                                        LlmPostProcessingPhaseContext phaseContext) {
        if (phaseContext == null) {
            throw new IllegalArgumentException("Post-processing phase context must not be null");
        }
        PostProcessingOptions options = phaseContext.options();
        // Take exactly one configuration snapshot for this phase.  Subsequent
        // collaborators receive this snapshot instead of observing mutable
        // Properties at different points in the request lifecycle.
        phaseContext.session().clear();
        MinimizationResult effectiveMinimizationResult = minimizationResult == null
                ? MinimizationResult.disabled(suite)
                : minimizationResult;
        PostProcessingTelemetryPublisher.publishMinimizationContext(effectiveMinimizationResult);
        if (!options.enabled()) {
            publishMetrics(phaseContext, new PostProcessingMetrics("disabled"));
            return 0;
        }
        if (effectiveMinimizationResult.isIncomplete()
                && options.phaseBudget().incompletePolicy()
                == Properties.LlmPostProcessingOnIncompleteMinimization.SKIP) {
            logger.info("Unified LLM post-processing skipped: minimization incomplete ({})",
                    effectiveMinimizationResult.getStatus());
            publishMetrics(phaseContext, new PostProcessingMetrics(
                    "incomplete_minimization_" + effectiveMinimizationResult.getStatus().name()));
            return 0;
        }
        if (options.provider() == Properties.LlmProvider.NONE) {
            logger.info("Unified LLM post-processing skipped: no LLM provider configured");
            publishMetrics(phaseContext, new PostProcessingMetrics("no_provider"));
            return 0;
        }
        if (!options.features().any()) {
            logger.info("Unified LLM post-processing skipped: no response features enabled");
            publishMetrics(phaseContext, new PostProcessingMetrics("no_features_enabled"));
            return 0;
        }
        if (suite == null || suite.size() == 0) {
            publishMetrics(phaseContext, new PostProcessingMetrics("empty_suite"));
            return 0;
        }
        if (llmService == null || !llmService.canCall(LlmFeature.POST_PROCESSING)) {
            logger.info("Unified LLM post-processing skipped: LLM service unavailable or budget exhausted");
            publishMetrics(phaseContext, new PostProcessingMetrics("service_unavailable_or_no_budget"));
            return 0;
        }

        PostProcessingMetrics phaseMetrics = new LlmPostProcessingPhase(this, phaseContext,
                new LlmTestPostProcessor(llmService, assertionCandidateRunner, assertionFallbackRunner,
                        assertionValidator, phaseContext)).run(
                suite, effectiveMinimizationResult);
        publishMetrics(phaseContext, phaseMetrics);
        return phaseMetrics.get(PostProcessingCounter.ASSERTIONS_APPLIED);
    }

    private static void publishMetrics(LlmPostProcessingPhaseContext phaseContext,
                                       PostProcessingMetrics metrics) {
        phaseContext.recordTerminalReason(metrics == null ? "" : metrics.skipReason);
        PostProcessingTelemetryPublisher.publish(metrics);
    }

    /**
     * Whether a failure means this thread was asked to stop. {@link LlmService}
     * restores the interrupt flag before rethrowing, but a wrapped
     * {@link InterruptedException} is also accepted and the flag is restored so
     * callers still observe the interrupt.
     */
    static boolean isInterruption(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return false;
    }

    boolean hasLlmBudget() {
        return llmService != null && llmService.hasBudget(LlmFeature.POST_PROCESSING);
    }

    /** Publish a skipped phase using its explicit phase context. */
    public void publishSkippedPostProcessingMetrics(LlmPostProcessingPhaseContext phaseContext,
                                                    String skipReason,
                                                    MinimizationResult minimizationResult) {
        PostProcessingTelemetryPublisher.publishMinimizationContext(minimizationResult == null
                ? MinimizationResult.disabled(null)
                : minimizationResult);
        publishMetrics(phaseContext, new PostProcessingMetrics(skipReason));
    }

    /** Publish final reconciliation for this phase's explicit context. */
    public void publishFinalAssertionReconciliation(LlmPostProcessingPhaseContext phaseContext,
                                                    TestSuiteChromosome suite,
                                                    int initiallyAppliedAssertions) {
        PostProcessingSession session = phaseContext.session();
        PostProcessingAssertionReconciler.Reconciliation reconciliation =
                PostProcessingAssertionReconciler.reconcile(suite, initiallyAppliedAssertions,
                        !session.appliedAssertions.isEmpty());
        int removedCompile = compileRemovedAssertionCount(session);
        PostProcessingTelemetryPublisher.publishAssertionReconciliation(
                Math.max(0, reconciliation.removedUnstable() - removedCompile),
                removedCompile, reconciliation.shipped());
        if (!session.appliedAssertions.isEmpty()) {
            publishFinalAssertionLifecycle(session.appliedAssertions, session.compileRemovedAssertions,
                    reconciliation.shippedBySignature());
        }
    }

    /**
     * Preserve the reason assertions disappeared when the final JUnit compiler
     * removes their containing test. The final reconciliation otherwise only
     * sees that the assertion is absent and would misclassify it as unstable.
     */
    public void recordAssertionsRemovedByCompileFilter(LlmPostProcessingPhaseContext phaseContext,
                                                       Collection<TestCase> removedTests) {
        phaseContext.session().recordCompileRemoved(removedTests);
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

    /**
     * Traces what became of each applied assertion. Works on copies of the
     * counts it consumes, so publishing twice gives the same answer.
     */
    static void publishFinalAssertionLifecycle(List<AppliedAssertionTrace> applied,
                                               Map<String, Integer> removedCompileBySignature,
                                               Map<String, Integer> shippedBySignature) {
        Map<String, Integer> shipped = new HashMap<>(shippedBySignature);
        Map<String, Integer> removedCompile = new HashMap<>(removedCompileBySignature);
        for (AppliedAssertionTrace trace : applied) {
            int remaining = shipped.getOrDefault(trace.signature, 0);
            int compileRemoved = removedCompile.getOrDefault(trace.signature, 0);
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
            LlmTestPostProcessor.recordAssertionLifecycle(trace.service, trace.proposal, trace.testIndex,
                    trace.minimizationResult, state, "final_validation");
        }
    }

    static String assertionSignatureForSession(TemplateCodeAssertion assertion) {
        return String.valueOf(assertion.getAssertionId()) + '\u001f'
                + String.valueOf(assertion.getKind()) + '\u001f'
                + assertion.render(null) + '\u001f'
                + String.valueOf(assertion.getPurpose());
    }

    static int remainingItems(List<WorkItem> workItems, int startIndex) {
        if (workItems == null || startIndex >= workItems.size()) {
            return 0;
        }
        return workItems.size() - startIndex;
    }

    boolean isAssertionEligible(TestChromosome chromosome) {
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

    interface AssertionFallbackRunner {
        int applyFallbackAssertions(TestCase test);
    }

    interface AssertionCandidateRunner {
        CompleteAssertionGenerator.CandidateCollection collectCandidates(TestCase test);
    }

    interface StabilityExecutionRunner {
        ExecutionResult execute(TestCase test);
    }

    interface AssertionEvaluationRunner {
        EvaluationOutcome evaluate(AssertionProposal proposal,
                                   TestCase validationTest,
                                   LlmPostProcessingReferences references,
                                   Scope finalScope,
                                   PostProcessingOptions options);
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
            return PhaseMemoryGuard.oneShot(Math.max(0L, minimumFreeMemoryBytes) * 2L).isExhausted();
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
        public int applyFallbackAssertions(TestCase test) {
            TestCase fallbackTest = test.clone();
            fallbackTest.removeAssertions();
            AssertionGenerator generator = new CompleteAssertionGenerator();
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

    static class PostProcessingMetrics {
        final String skipReason;
        private final EnumMap<PostProcessingCounter, Integer> counts =
                new EnumMap<>(PostProcessingCounter.class);
        DiagnosticCounters diagnosticCounters = new DiagnosticCounters();
        FallbackCounters fallbackCounters = new FallbackCounters();

        PostProcessingMetrics(String skipReason) {
            this.skipReason = skipReason;
        }

        int get(PostProcessingCounter counter) {
            Integer count = counts.get(counter);
            return count == null ? 0 : count;
        }

        void set(PostProcessingCounter counter, int value) {
            counts.put(counter, value);
        }

        void add(PostProcessingCounter counter, int delta) {
            counts.put(counter, get(counter) + delta);
        }

        void add(PostProcessingMetrics other) {
            if (other == null) {
                return;
            }
            for (PostProcessingCounter counter : PostProcessingCounter.values()) {
                add(counter, other.get(counter));
            }
            diagnosticCounters.add(other.diagnosticCounters);
            fallbackCounters.add(other.fallbackCounters);
        }

        PostProcessingMetrics snapshot(String reason) {
            PostProcessingMetrics snapshot = new PostProcessingMetrics(reason);
            snapshot.add(this);
            return snapshot;
        }
    }

    /** Per-test delta consumed by the suite-level aggregator. */
    static final class TestProcessingResult extends PostProcessingMetrics {
        LlmPostProcessingPhase.StopReason stopReason = LlmPostProcessingPhase.StopReason.NONE;

        TestProcessingResult() {
            super(null);
        }
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
        int assertionsApplied;

        static FallbackCounters none() {
            return new FallbackCounters();
        }

        static FallbackCounters applied(FallbackTrigger trigger, int assertionsApplied) {
            FallbackCounters counters = new FallbackCounters();
            counters.total = 1;
            counters.assertionsApplied = assertionsApplied;
            if (trigger == FallbackTrigger.INFRASTRUCTURE_FAILURE) {
                counters.infrastructure = 1;
            } else {
                counters.noAccepted = 1;
            }
            counters.all = 1;
            return counters;
        }

        void add(FallbackCounters other) {
            total += other.total;
            infrastructure += other.infrastructure;
            noAccepted += other.noAccepted;
            all += other.all;
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

    static final class AppliedAssertionTrace {
        private final LlmService service;
        private final AssertionProposal proposal;
        private final String signature;
        private final int testIndex;
        private final MinimizationResult minimizationResult;

        AppliedAssertionTrace(LlmService service,
                                      AssertionProposal proposal,
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

    static final class EvaluationOutcome {
        private final LlmPostProcessingParseResult.DiagnosticCode diagnosticCode;
        private final LlmPostProcessingParseResult.DiagnosticReason diagnosticReason;
        private final String message;

        private EvaluationOutcome(LlmPostProcessingParseResult.DiagnosticCode diagnosticCode, String message) {
            this(diagnosticCode, null, message);
        }

        private EvaluationOutcome(LlmPostProcessingParseResult.DiagnosticCode diagnosticCode,
                                  LlmPostProcessingParseResult.DiagnosticReason diagnosticReason,
                                  String message) {
            this.diagnosticCode = diagnosticCode;
            this.diagnosticReason = diagnosticReason;
            this.message = message;
        }

        static EvaluationOutcome accepted() {
            return new EvaluationOutcome(null, null);
        }

        static EvaluationOutcome compileFailure(String message) {
            return new EvaluationOutcome(LlmPostProcessingParseResult.DiagnosticCode.COMPILE, message);
        }

        static EvaluationOutcome safetyFailure(
                LlmPostProcessingParseResult.DiagnosticCode code, String message) {
            return new EvaluationOutcome(code,
                    LlmPostProcessingParseResult.DiagnosticReason.SAFETY_POLICY, message);
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

        LlmPostProcessingParseResult.DiagnosticReason diagnosticReason() {
            return diagnosticReason;
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
