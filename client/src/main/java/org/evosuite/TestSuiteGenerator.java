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
package org.evosuite;

import org.evosuite.Properties.AssertionStrategy;
import org.evosuite.Properties.Criterion;
import org.evosuite.Properties.TestFactory;
import org.evosuite.contracts.ContractChecker;
import org.evosuite.contracts.FailingTestSet;
import org.evosuite.coverage.CoverageCriteriaAnalyzer;
import org.evosuite.coverage.FitnessFunctions;
import org.evosuite.coverage.TestFitnessFactory;
import org.evosuite.coverage.dataflow.DefUseCoverageSuiteFitness;
import org.evosuite.junit.writer.TestSuiteWriter;
import org.evosuite.llm.LlmRunFeatures;
import org.evosuite.llm.LlmRunLifecycle;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.LlmStatistics;
import org.evosuite.llm.seeding.LlmPoolEnrichmentOrchestrator;
import org.evosuite.result.TestGenerationResult;
import org.evosuite.result.TestGenerationResultBuilder;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientState;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.runtime.sandbox.PermissionStatistics;
import org.evosuite.seeding.ObjectPool;
import org.evosuite.seeding.ObjectPoolManager;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.ExceptionMapGenerator;
import org.evosuite.setup.TargetClassInitializer;
import org.evosuite.setup.TestCluster;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.statistics.StatisticsSender;
import org.evosuite.strategy.TestGenerationStrategy;
import org.evosuite.symbolic.dse.DSEStatistics;
import org.evosuite.testcase.TestReferenceValidator;
import org.evosuite.testcase.ConstantInliner;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.EvosuiteError;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.execution.reset.ClassReInitializer;
import org.evosuite.testsuite.MinimizationResult;
import org.evosuite.testsuite.MinimizationStopCause;
import org.evosuite.testsuite.OracleReplayConfiguration;
import org.evosuite.testsuite.StructuralSuiteArtifact;
import org.evosuite.testsuite.StructuralSuiteMetadata;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteMinimizer;
import org.evosuite.testsuite.TestSuiteSerialization;
import org.evosuite.utils.ArrayUtil;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.text.NumberFormat;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Main entry point. Does all the static analysis, invokes a test generation
 * strategy, and then applies postprocessing.
 *
 * @author Gordon Fraser
 */
public class TestSuiteGenerator {

    private static final Logger logger = LoggerFactory.getLogger(TestSuiteGenerator.class);
    private Criterion[] requestedCriteria = null;
    private boolean structuralSuiteExportCompleted;

    private LlmPoolEnrichmentOrchestrator llmOrchestrator;

    private void stopLlmPoolEnrichment() {
        LlmPoolEnrichmentOrchestrator active = llmOrchestrator;
        llmOrchestrator = null;
        if (active == null) {
            return;
        }
        try {
            LlmStatistics.flushSeedingMetrics();
        } catch (Throwable t) {
            logger.warn("LLM seeding metric flush failed before cancellation (non-fatal): {}", t.getMessage());
        }
        try {
            active.cancelAll();
        } catch (Throwable t) {
            logger.warn("LLM enrichment cancellation failed (non-fatal): {}", t.getMessage());
        }
        try {
            LlmStatistics.flushSeedingMetrics();
        } catch (Throwable t) {
            logger.warn("LLM seeding metric flush failed after cancellation (non-fatal): {}", t.getMessage());
        }
    }


    private void initializeTargetClass() throws Throwable {
        TargetClassInitializer initializer = new TargetClassInitializer();
        initializer.initializeTargetClass(Properties.TARGET_CLASS, () -> {
            try {
                writeJUnitTestSuiteForFailedInitialization();
            } catch (EvosuiteError e) {
                logger.error("Failed to write JUnit test suite for failed initialization", e);
            }
        });
    }

    /**
     * Generates a test suite for the target class configured in {@link Properties#TARGET_CLASS}.
     * <p>
     * This method orchestrates the entire test generation process:
     * <ul>
     *     <li>Initializes the target class and analyzes dependencies.</li>
     *     <li>Handles potential instrumentation issues (e.g., methods too large).</li>
     *     <li>Invokes the configured test generation strategy (e.g., GA).</li>
     *     <li>Post-processes the generated tests (minimization, assertions, etc.).</li>
     *     <li>Writes the resulting test suite to disk (if configured).</li>
     * </ul>
     * </p>
     *
     * @return The result of the test generation process.
     */
    public TestGenerationResult generateTestSuite() {
        try {
            return generateTestSuiteInternal();
        } finally {
            stopLlmPoolEnrichment();
            LlmRunLifecycle.completeRun();
        }
    }

    private TestGenerationResult generateTestSuiteInternal() {

        structuralSuiteExportCompleted = false;

        boolean structuralExportRequested = isConfigured(Properties.STRUCTURAL_SUITE_EXPORT);
        boolean oracleReplayRequested = isConfigured(Properties.ORACLE_REPLAY_INPUT);
        if (structuralExportRequested && oracleReplayRequested) {
            return TestGenerationResultBuilder.buildErrorResult(
                    "Structural-suite export and oracle replay cannot be enabled together");
        }
        if (oracleReplayRequested && Properties.NUM_PARALLEL_CLIENTS != 1) {
            return TestGenerationResultBuilder.buildErrorResult(
                    "Oracle replay requires num_parallel_clients=1");
        }
        if (oracleReplayRequested) {
            OracleReplayConfiguration.apply(Properties.ORACLE_REPLAY_STRATEGY);
        }

        LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Analyzing classpath: ");

        ClientServices.getInstance().getClientNode().changeState(ClientState.INITIALIZATION);

        // Publish the zero defaults of the LLM, speciation and disruption columns before anything
        // else tracks them, so a feature the run never exercises reads 0 rather than blank in
        // every mode. The master keeps the last value, so what the run tracks replaces them.
        LlmStatistics.initializeRuntimeVariables();

        // Deactivate loop counter to make sure classes initialize properly
        LoopCounter.getInstance().setActive(false);
        ExceptionMapGenerator.initializeExceptionMap(Properties.TARGET_CLASS);

        TestCaseExecutor.initExecutor();
        try {
            initializeTargetClass();
        } catch (Throwable e) {

            // If the bytecode for a method exceeds 64K, Java will complain
            // Very often this is due to mutation instrumentation, so this dirty
            // hack adds a fallback mode without mutation.
            // This currently breaks statistics and assertions, so we have to also set these properties
            boolean error = true;

            String message = e.getMessage();
            if (message != null && (message.contains("Method code too large")
                    || message.contains("Class file too large"))) {
                LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                        + "Instrumentation exceeds Java's 64K limit per method in target class");
                Properties.Criterion[] newCriteria = Arrays.stream(Properties.CRITERION)
                        .filter(t -> !t.equals(Properties.Criterion.STRONGMUTATION)
                                && !t.equals(Properties.Criterion.WEAKMUTATION)
                                && !t.equals(Properties.Criterion.MUTATION))
                        .toArray(Properties.Criterion[]::new);
                if (newCriteria.length < Properties.CRITERION.length) {
                    TestGenerationContext.getInstance().resetContext();
                    LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                            + "Attempting re-instrumentation without mutation");
                    Properties.CRITERION = newCriteria;
                    if (Properties.NEW_STATISTICS) {
                        LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                                + "Deactivating EvoSuite statistics because of instrumentation problem");
                        Properties.NEW_STATISTICS = false;
                    }

                    try {
                        initializeTargetClass();
                        error = false;
                    } catch (Throwable t) {
                        // No-op, error handled below
                    }
                    if (Properties.ASSERTIONS && Properties.ASSERTION_STRATEGY == AssertionStrategy.MUTATION) {
                        LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                                + "Deactivating assertion minimization because mutation instrumentation does not work");
                        Properties.ASSERTION_STRATEGY = AssertionStrategy.ALL;
                    }
                }
            }

            if (error) {
                LoggingUtils.getEvoLogger().error("* " + ClientProcess.getPrettyPrintIdentifier()
                        + "Error while initializing target class: "
                        + (e.getMessage() != null ? e.getMessage() : e.toString()));
                logger.error("Problem for " + Properties.TARGET_CLASS + ". Full stack:", e);
                return TestGenerationResultBuilder.buildErrorResult(
                        e.getMessage() != null ? e.getMessage() : e.toString());
            }

        } finally {
            if (Properties.RESET_STATIC_FIELDS) {
                configureClassReInitializer();

            }
            // Once class loading is complete we can start checking loops
            // without risking to interfere with class initialisation
            LoopCounter.getInstance().setActive(true);
        }

        // Resolve any LLM seeding profile bundle into the individual flags
        // BEFORE reading isEnrichmentEnabled() — otherwise FULL/STANDARD/MIN
        // wouldn't have taken effect by the time the orchestrator checks the
        // per-feature toggles.
        Properties.applyLlmSeedingProfile();

        if (oracleReplayRequested) {
            return OracleReplayRun.run();
        }

        // LLM enrichment start (async, non-blocking)
        if (LlmPoolEnrichmentOrchestrator.isEnrichmentEnabled()) {
            try {
                llmOrchestrator = LlmPoolEnrichmentOrchestrator.fromProperties(LlmService.getInstance());
                llmOrchestrator.startEnrichment(Properties.TARGET_CLASS, TestCluster.getInstance());
            } catch (Throwable t) {
                logger.warn("LLM enrichment startup failed (non-fatal): {}", t.getMessage());
            }
        }

        /*
         * Initialises the object pool with objects carved from SELECTED_JUNIT
         * classes
         */
        // TODO: Do parts of this need to be wrapped into sandbox statements?
        ObjectPoolManager.getInstance();

        LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Generating tests for class "
                + Properties.TARGET_CLASS);
        sanitizeDebugInfoDependentCriteria();
        // Both sanitizers have to run before the criteria are printed, otherwise the log advertises
        // criteria that this run is not going to use.
        dropCallGraphDependentCriteriaIfNoCallGraph();
        TestSuiteGeneratorHelper.printTestCriterion();

        if (Properties.CRITERION.length == 0) {
            return TestGenerationResultBuilder.buildErrorResult("No testable code found (no coverage goals)");
        }

        if (!Properties.hasTargetClassBeenLoaded()) {
            // initialization failed, then build error message
            return TestGenerationResultBuilder.buildErrorResult("Could not load target class");
        }

        // Check if there are ANY goals across all criteria
        int totalGoals = 0;
        for (TestFitnessFactory<? extends TestFitnessFunction> factory : getFitnessFactories()) {
            totalGoals += factory.getCoverageGoals().size();
        }
        if (totalGoals == 0 && !ArrayUtil.contains(Properties.CRITERION, Criterion.EXCEPTION)) {
            LoggingUtils.getEvoLogger().info("* No coverage goals found for the target class {}",
                    Properties.TARGET_CLASS);
            // Do not fail fast here: strategies can gracefully handle zero goals
            // by returning an empty suite while still exposing a GA in results.
        }

        // Wait for LLM pool enrichment before search starts. With
        // LLM_FAIR_BUDGET_ACCOUNTING enabled, block on ALL enrichments
        // (cast classes, constants, objects) so the four seeding strategies
        // are evaluated on equal footing, and record the elapsed wall-clock
        // time so time-based stopping conditions can deduct it from the
        // search budget. Otherwise, fall back to the original behaviour of
        // only gating on cast classes while data enrichments run in the
        // background.
        if (llmOrchestrator != null) {
            long enrichmentStartNanos = System.nanoTime();
            if (Properties.LLM_FAIR_BUDGET_ACCOUNTING) {
                // Pre-search call site: clamp the repair-aware wait by the
                // configured search budget so a slow LLM cannot consume more
                // wall-clock than the search would have run for.
                llmOrchestrator.awaitAll(() -> Properties.SEARCH_BUDGET * 1000L);
            } else {
                llmOrchestrator.finishStructuralEnrichment();
            }
            long elapsedMs = (System.nanoTime() - enrichmentStartNanos) / 1_000_000L;
            org.evosuite.llm.LlmStatistics.recordPoolEnrichmentElapsedMs(elapsedMs);
            logger.info("LLM pool enrichment blocked for {} ms (fair_budget={})",
                    elapsedMs, Properties.LLM_FAIR_BUDGET_ACCOUNTING);
        }

        TestSuiteChromosome testCases = generateTests();
        stopLlmPoolEnrichment();

        // As post process phases such as minimisation, coverage analysis, etc., may call getFitness()
        // of each fitness function, which may try to update the Archive, in here we explicitly disable
        // Archive to avoid any problem and at the same time to improve the performance of post process
        // phases (as no CPU cycles would be wasted updating the Archive).
        Properties.TEST_ARCHIVE = false;

        TestGenerationResult result = null;
        if (ClientProcess.DEFAULT_CLIENT_NAME.equals(ClientProcess.getIdentifier())) {
            // Post-processing (minimization, assertion generation, JUnit check) can blow
            // its time budget, OOM, or throw on pathological SUTs. Catch everything so the
            // write step still runs — losing minimization/assertions is far better than
            // producing no tests at all.
            boolean postProcessFailed = false;
            try {
                postProcessTests(testCases);
            } catch (Throwable t) {
                postProcessFailed = true;
                logger.error("Post-processing failed; falling back to direct write of un-processed suite", t);
                System.gc();
            }
            try {
                ClientServices.getInstance().getClientNode().publishPermissionStatistics();
                PermissionStatistics.getInstance().printStatistics(LoggingUtils.getEvoLogger());
            } catch (Throwable t) {
                logger.warn("Publishing permission statistics failed (non-fatal): {}", t.toString());
            }

            // If post-processing died (typically OOM or hard timeout) the JVM is
            // likely degraded — heap full, GC thrashing, executor threads possibly
            // still draining work from the previous phase.  Recycle the executor
            // so any residual SUT worker is gone before the writer starts running
            // tests of its own, and surface the diagnostic state to the log so we
            // can see how much budget we have left going into the write.
            if (postProcessFailed) {
                long freeMb = PhaseMemoryGuard.currentFreeHeapBytes() / 1024 / 1024;
                long phaseMsLeft = TimeController.getInstance().getRemainingTimeInPhaseMs();
                LoggingUtils.getEvoLogger().warn("* " + ClientProcess.getPrettyPrintIdentifier()
                        + "Proceeding to write with degraded state: freeMem=" + freeMb
                        + " MB, phaseTimeLeft=" + phaseMsLeft + " ms");
                try {
                    TestCaseExecutor.pullDown();
                    TestCaseExecutor.initExecutor();
                } catch (Throwable t) {
                    logger.debug("Could not recycle test executor after postProcess failure", t);
                }
            }

            if (structuralExportRequested) {
                if (postProcessFailed || !structuralSuiteExportCompleted) {
                    result = TestGenerationResultBuilder.buildErrorResult(
                            "Structural-suite export failed; no JUnit tests were written");
                } else {
                    result = TestGenerationResultBuilder.buildSuccessResult();
                }
            } else {
                // progressMonitor.setCurrentPhase("Writing JUnit test cases");
                LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                        + "Writing tests to file");
                result = writeJUnitTestsAndCreateResult(testCases);
                try {
                    writeJUnitFailingTests();
                } catch (Throwable t) {
                    logger.warn("Writing failing tests failed (non-fatal): {}", t.toString());
                }
            }
        }
        finishRun();

        return result != null ? result : TestGenerationResultBuilder.buildSuccessResult();
    }

    /** Shuts the executor down and publishes the run's statistics once the tests are written. */
    static void finishRun() {
        TestCaseExecutor.pullDown();
        /*
         * TODO: when we will have several processes running in parallel, we ll
         * need to handle the gathering of the statistics.
         */
        if (LlmRunFeatures.providerConfigured()) {
            LlmService.getInstance().getStatistics().publishRuntimeVariables();
        } else {
            LlmStatistics.publishRunTotals();
        }
        ClientServices.getInstance().getClientNode().changeState(ClientState.WRITING_STATISTICS);

        LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Done!");
        LoggingUtils.getEvoLogger().info("");
    }

    static boolean isConfigured(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private void sanitizeDebugInfoDependentCriteria() {
        Criterion[] oldCriteria = Properties.CRITERION;
        Criterion[] filteredCriteria = TestSuiteGeneratorHelper.removeDebugInfoDependentCriteriaIfMissing(oldCriteria);
        if (filteredCriteria.length == oldCriteria.length) {
            return;
        }

        String omittedCriteria = Arrays.stream(oldCriteria)
                .filter(c -> TestSuiteGeneratorHelper.isLineDebugInfoDependentCriterion(c))
                .map(TestSuiteGeneratorHelper::getCriterionDisplayName)
                .collect(java.util.stream.Collectors.joining(", "));
        LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                + "Missing line debug information in target class {}; omitting criterion during test generation: {}",
                Properties.TARGET_CLASS, omittedCriteria);

        requestedCriteria = oldCriteria;
        Properties.CRITERION = filteredCriteria;

        if (Properties.CRITERION.length == 0) {
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                    + "No test criteria left after removing debug-info-dependent criteria");
        }
    }

    static boolean isCallGraphDependentCriterion(Criterion criterion) {
        return criterion == Criterion.CBRANCH || criterion == Criterion.IBRANCH;
    }

    static Criterion[] removeCallGraphDependentCriteria(Criterion[] criteria) {
        return Arrays.stream(criteria)
                .filter(c -> !isCallGraphDependentCriterion(c))
                .toArray(Criterion[]::new);
    }

    /**
     * Context-sensitive criteria (CBRANCH/IBRANCH) expand branch goals through the call graph of the
     * target class. If dependency analysis failed for this class, the call graph was never built:
     * the failure is logged and swallowed during setup (ClientNodeImpl.doDependencyAnalysis,
     * TestGenerationContext.resetContext), so the class object loads yet getCallGraph() is null.
     * Drop the affected criteria and carry on with whatever is left - losing context sensitivity is
     * much better than producing no test suite at all.
     *
     * @return true if at least one criterion was dropped
     */
    boolean dropCallGraphDependentCriteriaIfNoCallGraph() {
        if (Arrays.stream(Properties.CRITERION).noneMatch(TestSuiteGenerator::isCallGraphDependentCriterion)) {
            return false;
        }
        if (DependencyAnalysis.getCallGraph() != null) {
            return false;
        }

        String omittedCriteria = Arrays.stream(Properties.CRITERION)
                .filter(TestSuiteGenerator::isCallGraphDependentCriterion)
                .map(TestSuiteGeneratorHelper::getCriterionDisplayName)
                .collect(java.util.stream.Collectors.joining(", "));
        LoggingUtils.getEvoLogger().warn("* " + ClientProcess.getPrettyPrintIdentifier()
                + "Dependency analysis did not produce a call graph for {}; omitting criterion during test "
                + "generation: {}", Properties.TARGET_CLASS, omittedCriteria);

        Properties.CRITERION = removeCallGraphDependentCriteria(Properties.CRITERION);
        // requestedCriteria is replayed over Properties.CRITERION for the coverage-analysis phase,
        // so it must not smuggle the dropped criteria back in: IBranchFitnessFactory would then
        // dereference exactly the call graph that is missing.
        if (requestedCriteria != null) {
            requestedCriteria = removeCallGraphDependentCriteria(requestedCriteria);
        }

        if (Properties.CRITERION.length == 0) {
            LoggingUtils.getEvoLogger().warn("* " + ClientProcess.getPrettyPrintIdentifier()
                    + "No test criteria left after removing call-graph-dependent criteria");
        }
        return true;
    }

    /**
     * Reports the initialized classes during class initialization to the
     * ClassReInitializater and configures the ClassReInitializer accordingly.
     */
    private void configureClassReInitializer() {
        // add loaded classes during building of dependency graph
        ExecutionTrace execTrace = ExecutionTracer.getExecutionTracer().getTrace();
        final List<String> initializedClasses = execTrace.getInitializedClasses();
        ClassReInitializer.getInstance().addInitializedClasses(initializedClasses);
        // set the behaviour of the ClassReInitializer
        final boolean resetAllClasses = Properties.RESET_ALL_CLASSES_DURING_TEST_GENERATION;
        ClassReInitializer.getInstance().setReInitializeAllClasses(resetAllClasses);
    }

    private static void writeJUnitTestSuiteForFailedInitialization() throws EvosuiteError {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        DefaultTestCase test = TargetClassInitializer.buildLoadTargetClassTestCase(Properties.TARGET_CLASS);
        suite.addTest(test);
        writeJUnitTestsAndCreateResult(suite);
    }

    /**
     * Tests that use a variable no earlier statement defines cannot be cloned by the
     * minimizer and cannot be written as compilable code; one of them used to abort the
     * whole post-processing, so they are dropped first, each with one warning.
     */
    void removeTestsWithUnresolvedReferences(TestSuiteChromosome testSuite) {
        Iterator<TestChromosome> iterator = testSuite.getTestChromosomes().iterator();
        int index = 0;
        while (iterator.hasNext()) {
            TestChromosome test = iterator.next();
            List<String> problems;
            try {
                // The same check the search applies to its offspring, plus assignments whose
                // value or target is only defined later, which clone() tolerates but any
                // positional copy, and the written test, do not.
                problems = TestReferenceValidator.findOrphanedVariableReferences(test.getTestCase());
                if (problems.isEmpty()) {
                    problems = TestReferenceValidator.findUnsafelyCopyableStatements(test.getTestCase());
                }
            } catch (Throwable t) {
                problems = Collections.singletonList("reference check crashed: " + t);
            }
            if (!problems.isEmpty()) {
                logger.warn("Dropping test {} of the suite (id {}) before post-processing: {}",
                        index, test.getTestCase().getID(), problems);
                iterator.remove();
            }
            index++;
        }
    }

    /**
     * Drops every test that is a prefix of another test in the suite, crediting
     * its covered goals to the test that contains it.
     *
     * <p>The archive keeps the shortest test for each goal, and a test that
     * grew out of another by appending statements covers everything its
     * origin does, so the suite the search hands back is largely made of
     * prefixes of its own tests. {@link TestSuiteWriter} merges them when the
     * tests are written, but by then inlining, minimization and the coverage
     * analysis have each re-executed all of them. Merging here, before any of
     * that, compares each candidate against the tests already kept, so it is
     * worst-case quadratic in the number of tests, with each comparison
     * walking statements. Same condition as the writer: with
     * {@code call_probability} the prefixes are kept as distinct tests.</p>
     */
    void removePrefixTests(TestSuiteChromosome testSuite) {
        if (Properties.CALL_PROBABILITY > 0 || testSuite.size() < 2) {
            return;
        }
        // Longest first, so a test only has to be compared with the tests
        // that can contain it; the sort is stable, so equal tests keep their
        // order and the first of them is the one that survives.
        List<TestChromosome> byLength = new ArrayList<>(testSuite.getTestChromosomes());
        byLength.sort(Comparator.comparingInt((TestChromosome t) -> -t.size()));
        List<TestChromosome> kept = new ArrayList<>();
        Set<TestChromosome> prefixes = Collections.newSetFromMap(new IdentityHashMap<>());
        for (TestChromosome test : byLength) {
            TestChromosome container = null;
            for (TestChromosome candidate : kept) {
                if (test.getTestCase().isPrefix(candidate.getTestCase())) {
                    container = candidate;
                    break;
                }
            }
            if (container == null) {
                kept.add(test);
                continue;
            }
            for (TestFitnessFunction goal : test.getTestCase().getCoveredGoals()) {
                container.getTestCase().addCoveredGoal(goal);
            }
            // isPrefix(candidate) only matches when test's statement i equals
            // candidate's statement i for every i < test.size(), so
            // addAssertions(TestCase) can walk that same shared range and copy
            // across assertions not already present. LLM/seed tests carry
            // oracles the assertion phase never regenerates, so without this
            // they are lost outright when the shorter test is discarded below.
            // Presentation metadata (test names, comments, section breaks) is
            // not merged here, since it is only attached later by LLM
            // post-processing.
            container.getTestCase().addAssertions(test.getTestCase());
            prefixes.add(test);
        }
        if (prefixes.isEmpty()) {
            return;
        }
        List<TestChromosome> remaining = new ArrayList<>(testSuite.getTestChromosomes());
        remaining.removeIf(prefixes::contains);
        testSuite.replaceWithTestChromosomes(remaining);
        LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                + "Removed " + prefixes.size() + " tests that were prefixes of other tests, "
                + remaining.size() + " remain");
        logSuiteDiagnostics("postProcess:afterPrefixRemoval", testSuite);
    }

    /**
     * Apply any readability optimizations and other techniques that should use
     * or modify the generated tests.
     *
     * @param testSuite the test suite to post-process
     */
    protected void postProcessTests(TestSuiteChromosome testSuite) {
        logSuiteDiagnostics("postProcess:start", testSuite);

        removeTestsWithUnresolvedReferences(testSuite);

        // If overall time is short, the search might not have had enough time
        // to come up with a suite without timeouts. However, they will slow
        // down the rest of the process, and may lead to invalid tests.
        //
        // Tests that threw internal exceptions are removed.  Timed-out tests
        // are kept: the JUnit writer wraps them in an ExecutorService with
        // future.get(timeout) so they won't hang.  This preserves coverage for
        // classes whose constructors always block (e.g., on network I/O).
        int beforeExceptionFilter = testSuite.size();
        Map<String, Integer> exceptionTypeHistogram = new LinkedHashMap<>();
        List<TestChromosome> candidatesToRemove = new ArrayList<>();
        for (TestChromosome test : testSuite.getTestChromosomes()) {
            ExecutionResult result = test.getLastExecutionResult();
            if (result != null && result.hasTestException()) {
                String exceptionType = "<unknown>";
                Integer firstPos = result.getFirstPositionOfThrownException();
                if (firstPos != null) {
                    Throwable thrown = result.getExceptionThrownAtPosition(firstPos);
                    if (thrown != null) {
                        exceptionType = thrown.getClass().getName();
                    }
                }
                exceptionTypeHistogram.merge(exceptionType, 1, Integer::sum);
                candidatesToRemove.add(test);
            }
        }
        if (!candidatesToRemove.isEmpty() && candidatesToRemove.size() == beforeExceptionFilter
                && beforeExceptionFilter > 0) {
            logger.warn("Post-process exception filter would remove all {} test(s). "
                            + "Keeping suite intact to avoid empty-output regression. "
                            + "Exception histogram: {}",
                    beforeExceptionFilter, exceptionTypeHistogram);
        } else if (!candidatesToRemove.isEmpty()) {
            testSuite.getTestChromosomes().removeAll(candidatesToRemove);
            int removedByExceptionFilter = candidatesToRemove.size();
            logger.warn("Post-process exception filter removed {} test(s) out of {}. "
                            + "Remaining: {}. Exception histogram: {}",
                    removedByExceptionFilter, beforeExceptionFilter, testSuite.size(), exceptionTypeHistogram);
        }
        logSuiteDiagnostics("postProcess:afterExceptionFilter", testSuite);

        if (Properties.CTG_SEEDS_FILE_OUT != null) {
            TestSuiteSerialization.saveTests(testSuite, new File(Properties.CTG_SEEDS_FILE_OUT));
        } else if (Properties.TEST_FACTORY == TestFactory.SERIALIZATION) {
            TestSuiteSerialization.saveTests(testSuite,
                    new File(Properties.SEED_DIR + File.separator + Properties.TARGET_CLASS));
        }

        /*
         * Remove covered goals that are not part of the minimization targets,
         * as they might screw up coverage analysis when a minimization timeout
         * occurs. This may happen e.g. when MutationSuiteFitness calls
         * BranchCoverageSuiteFitness which adds branch goals.
         */
        // TODO: This creates an inconsistency between
        // suite.getCoveredGoals().size() and suite.getNumCoveredGoals()
        // but it is not clear how to update numcoveredgoals
        List<TestFitnessFunction> goals = new ArrayList<>();
        for (TestFitnessFactory<?> ff : getFitnessFactories()) {
            goals.addAll(ff.getCoverageGoals());
        }
        for (TestFitnessFunction f : testSuite.getCoveredGoals()) {
            if (!goals.contains(f)) {
                testSuite.removeCoveredGoal(f);
            }
        }

        removePrefixTests(testSuite);

        if (Properties.INLINE) {
            ClientServices.getInstance().getClientNode().changeState(ClientState.INLINING);
            ConstantInliner inliner = new ConstantInliner();
            // progressMonitor.setCurrentPhase("Inlining constants");

            // Map<FitnessFunction<? extends TestSuite<?>>, Double> fitnesses =
            // testSuite.getFitnesses();

            inliner.inline(testSuite);
        }

        MinimizationResult minimizationResult = MinimizationResult.disabled(testSuite);
        if (Properties.MINIMIZE) {
            ClientServices.getInstance().getClientNode().changeState(ClientState.MINIMIZATION);
            // progressMonitor.setCurrentPhase("Minimizing test cases");
            PhaseSkipCause skipCause = checkPhase("minimization",
                    TimeController.getInstance()::hasTimeToExecuteATestCase);
            if (skipCause != PhaseSkipCause.NONE) {
                MinimizationStopCause stopCause = skipCause == PhaseSkipCause.LOW_MEMORY
                        ? MinimizationStopCause.LOW_MEMORY
                        : MinimizationStopCause.TIMEOUT;
                minimizationResult = MinimizationResult.preflightAborted(testSuite, stopCause);
                if (skipCause == PhaseSkipCause.TIMEOUT) {
                    LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                            + "Skipping minimization because not enough time is left");
                }
                publishUnminimizedSuite(testSuite, minimizationResult);
            } else {
                double before = testSuite.getFitness();
                TestSuiteMinimizer minimizer = new TestSuiteMinimizer(getFitnessFactories());
                int testsBeforeMinimization = testSuite.size();
                int lengthBeforeMinimization = testSuite.totalLengthOfTestCases();

                LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                        + "Minimizing test suite");
                minimizationResult = minimizer.minimize(testSuite, true);
                logger.info("Minimization changed suite size {} -> {}, length {} -> {}",
                        testsBeforeMinimization, testSuite.size(),
                        lengthBeforeMinimization, testSuite.totalLengthOfTestCases());
                if (testsBeforeMinimization > 0 && testSuite.size() == 0) {
                    logger.warn("Minimization removed all tests ({} -> 0).", testsBeforeMinimization);
                }
                logSuiteDiagnostics("postProcess:afterMinimization", testSuite);

                // Note: this guard cannot currently fire.  getFitness() only sums
                // the fitness values already cached on the chromosome, and the
                // per-test minimization used here never re-evaluates a suite
                // fitness function, so `after` is by construction equal to
                // `before` (and simply 0.0 for an archive-derived suite, whose
                // cache is empty).  What actually protects coverage is the
                // covered-goal check inside TestSuiteMinimizer.minimize(), which
                // reverts to the pre-minimization suite rather than aborting the
                // whole run.  Kept as a cheap assertion in case a future caller
                // does keep the suite fitness up to date across minimization.
                double after = testSuite.getFitness();
                if (after > before + 0.01d) { // assume minimization
                    throw new Error("EvoSuite bug: minimization lead fitness from " + before + " to " + after);
                }
            }
        } else {
            if (!TimeController.getInstance().hasTimeToExecuteATestCase()) {
                LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                        + "Skipping minimization because not enough time is left");
            }

            publishUnminimizedSuite(testSuite, minimizationResult);
        }

        runCoverageAnalysisIfEnabled(testSuite);

        double coverage = testSuite.getCoverage();
        if (coverage == 0.0 && Properties.CRITERION.length > 1) {
            double recomputedCoverage = computeAverageCoverageFromCriteria(testSuite);
            if (recomputedCoverage > 0.0) {
                coverage = recomputedCoverage;
            }
        }

        StatisticsSender.executedAndThenSendIndividualToMaster(testSuite);
        LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                + "Generated " + testSuite.size()
                + " tests with total length " + testSuite.totalLengthOfTestCases());

        // TODO: In the end we will only need one analysis technique
        if (!Properties.ANALYSIS_CRITERIA.isEmpty()) {
            // SearchStatistics.getInstance().addCoverage(Properties.CRITERION.toString(),
            // coverage);
            Criterion[] originalCriteria = Properties.CRITERION;
            if (requestedCriteria != null) {
                Properties.CRITERION = requestedCriteria;
            }
            try {
                CoverageCriteriaAnalyzer.analyzeCriteria(testSuite, Properties.ANALYSIS_CRITERIA);
            } finally {
                Properties.CRITERION = originalCriteria;
            }
            // FIXME: can we send all bestSuites?
        }
        if (Properties.CRITERION.length > 1) {
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                            + "Resulting test suite's coverage: "
                            + NumberFormat.getPercentInstance().format(coverage)
                            + " (average coverage for all fitness functions)");
        } else {
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                            + "Resulting test suite's coverage: "
                            + NumberFormat.getPercentInstance().format(coverage));
        }

        if (ArrayUtil.contains(Properties.CRITERION, Criterion.DEFUSE) && Properties.ANALYSIS_CRITERIA.isEmpty()) {
            DefUseCoverageSuiteFitness.printCoverage();
        }

        DSEStatistics.getInstance().trackStatistics();

        if (Properties.isDSEEnabledInLocalSearch() || Properties.isDSEStrategySelected()) {
            DSEStatistics.getInstance().logStatistics();
        }

        if (Properties.FILTER_SANDBOX_TESTS) {
            PhaseSkipCause skipCause = checkPhase("sandbox test filtering",
                    TimeController.getInstance()::hasTimeToExecuteATestCase);
            if (skipCause == PhaseSkipCause.TIMEOUT) {
                LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                        + "Skipping sandbox test filtering because not enough time is left");
            } else if (skipCause == PhaseSkipCause.NONE) {
                for (TestChromosome test : testSuite.getTestChromosomes()) {
                    // delete all statements leading to security exceptions
                    ExecutionResult result = test.getLastExecutionResult();
                    if (result == null) {
                        result = TestCaseExecutor.runTest(test.getTestCase());
                    }
                    if (result.hasSecurityException()) {
                        int position = result.getFirstPositionOfThrownException();
                        if (position > 0) {
                            test.getTestCase().chop(position);
                            result = TestCaseExecutor.runTest(test.getTestCase());
                            test.setLastExecutionResult(result);
                        }
                    }
                }
            }
        }

        if (isConfigured(Properties.STRUCTURAL_SUITE_EXPORT)) {
            try {
                StructuralSuiteMetadata metadata = StructuralSuiteArtifact.write(
                        new File(Properties.STRUCTURAL_SUITE_EXPORT),
                        Properties.TARGET_CLASS,
                        testSuite,
                        minimizationResult);
                structuralSuiteExportCompleted = true;
                ClientServices.track(RuntimeVariable.Structural_Suite_Exported, true);
                ClientServices.track(RuntimeVariable.Structural_Suite_Export_Fingerprint,
                        metadata.getStructuralFingerprint());
                ClientServices.track(RuntimeVariable.Structural_Suite_Export_Tests,
                        metadata.getTestCount());
                ClientServices.track(RuntimeVariable.Structural_Suite_Export_Statements,
                        metadata.getStatementCount());
                LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                        + "Exported structural suite to "
                        + new File(Properties.STRUCTURAL_SUITE_EXPORT).getAbsolutePath()
                        + " (tests=" + metadata.getTestCount()
                        + ", statements=" + metadata.getStatementCount()
                        + ", fingerprint=" + metadata.getStructuralFingerprint() + ")");
                return;
            } catch (java.io.IOException e) {
                ClientServices.track(RuntimeVariable.Structural_Suite_Exported, false);
                throw new IllegalStateException("Could not export structural suite", e);
            }
        }

        OraclePhase.run(testSuite, minimizationResult, null);
    }

    /**
     * Records the size and length of a suite that was not minimized as both the result and the
     * minimized figures, and publishes the minimization outcome.
     */
    private static void publishUnminimizedSuite(TestSuiteChromosome testSuite,
                                                MinimizationResult minimizationResult) {
        ClientServices.track(RuntimeVariable.Result_Size, testSuite.size());
        ClientServices.track(RuntimeVariable.Minimized_Size, testSuite.size());
        ClientServices.track(RuntimeVariable.Result_Length, testSuite.totalLengthOfTestCases());
        ClientServices.track(RuntimeVariable.Minimized_Length, testSuite.totalLengthOfTestCases());
        minimizationResult.publish();
    }

    static void logSuiteDiagnostics(String phase, TestSuiteChromosome suite) {
        if (suite == null) {
            logger.warn("Suite diagnostics [{}]: suite is null", phase);
            return;
        }

        int total = suite.size();
        int totalLength = suite.totalLengthOfTestCases();
        int withoutExecutionResult = 0;
        int withTimeout = 0;
        int withTestException = 0;
        int withSecurityException = 0;
        int emptyTests = 0;
        Map<String, Integer> firstExceptionHistogram = new LinkedHashMap<>();

        for (TestChromosome test : suite.getTestChromosomes()) {
            if (test == null || test.getTestCase() == null || test.getTestCase().size() == 0) {
                emptyTests++;
            }
            ExecutionResult result = test == null ? null : test.getLastExecutionResult();
            if (result == null) {
                withoutExecutionResult++;
                continue;
            }
            if (result.hasTimeout()) {
                withTimeout++;
            }
            if (result.hasTestException()) {
                withTestException++;
            }
            if (result.hasSecurityException()) {
                withSecurityException++;
            }

            Integer firstPos = result.getFirstPositionOfThrownException();
            if (firstPos != null) {
                Throwable thrown = result.getExceptionThrownAtPosition(firstPos);
                if (thrown != null) {
                    firstExceptionHistogram.merge(thrown.getClass().getName(), 1, Integer::sum);
                }
            }
        }

        logger.info("Suite diagnostics [{}]: tests={}, length={}, emptyTests={}, noResult={}, "
                        + "timeouts={}, testExceptions={}, securityExceptions={}, firstExceptions={}",
                phase, total, totalLength, emptyTests, withoutExecutionResult,
                withTimeout, withTestException, withSecurityException, firstExceptionHistogram);
    }

    /**
     * Runs coverage analysis if {@link Properties#COVERAGE} requests it, recording the sweep as
     * incomplete whenever this method itself gives up before, or partway through, calling
     * {@link CoverageCriteriaAnalyzer#analyzeCoverage(TestSuiteChromosome)} -- that analyzer only
     * tracks completeness for budget exits it hits internally, not for an outer skip or an
     * uncaught {@link OutOfMemoryError} here.
     *
     * @param testSuite the suite to analyze
     */
    private void runCoverageAnalysisIfEnabled(TestSuiteChromosome testSuite) {
        if (!Properties.COVERAGE) {
            return;
        }
        ClientServices.getInstance().getClientNode().changeState(ClientState.COVERAGE_ANALYSIS);
        if (!TimeController.getInstance().hasTimeToExecuteATestCase()) {
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                    + "Skipping coverage analysis because not enough time is left");
            CoverageCriteriaAnalyzer.markCoverageAnalysisIncomplete();
            return;
        }
        // Deliberately not gated on isMemoryTooLowForPhase() like the heavier phases
        // below: with the criteria unchanged since search (the common case here),
        // analyzeCoverage() does not re-instrument or re-execute a single test, only
        // clones the suite and reads already-cached results, so it does not need the
        // same headroom minimization or JUnit checking do. Attempt it and only fall
        // back if it actually runs out of memory -- each criterion's output variable
        // is tracked as soon as that criterion's goals are checked, so a suite this
        // large that OOMs partway through still leaves whatever was tracked before
        // the failure, rather than reporting nothing at all.
        Criterion[] originalCriteria = Properties.CRITERION;
        if (requestedCriteria != null) {
            Properties.CRITERION = requestedCriteria;
        }
        try {
            CoverageCriteriaAnalyzer.analyzeCoverage(testSuite);
        } catch (OutOfMemoryError e) {
            LoggingUtils.getEvoLogger().warn("* " + ClientProcess.getPrettyPrintIdentifier()
                    + "Coverage analysis ran out of memory partway through; "
                    + "keeping whatever coverage numbers were already tracked");
            CoverageCriteriaAnalyzer.markCoverageAnalysisIncomplete();
        } finally {
            Properties.CRITERION = originalCriteria;
        }
    }

    /**
     * Overrides the free-memory measurement used by {@link #isMemoryTooLowForPhase} below;
     * {@code null} in production. Setting {@code Properties.MIN_FREE_MEM} alone cannot force this
     * branch deterministically in a test, because the doubled threshold still has to exceed
     * actual free heap, and that is not guaranteed on a JVM with a large enough maximum heap.
     * Tests in this package (see {@code TestSuiteGeneratorJUnitCheckCompletenessTest}) set this
     * instead, and must reset it to {@code null} afterwards.
     */
    static LongSupplier freeMemoryBytesForTesting = null;

    /** Why a post-search phase is skipped, if it is. */
    enum PhaseSkipCause {
        NONE,
        TIMEOUT,
        LOW_MEMORY
    }

    /**
     * Decides whether a post-search phase that re-executes tests can start: it needs time left,
     * as the given predicate measures it, and then enough free memory. A low-memory skip is
     * logged here; reporting a timeout is left to the caller.
     *
     * @param phaseName human-readable name for log messages
     * @param hasTime   whether the phase has time left
     * @return why the phase must be skipped, or {@link PhaseSkipCause#NONE}
     */
    static PhaseSkipCause checkPhase(String phaseName, BooleanSupplier hasTime) {
        if (!hasTime.getAsBoolean()) {
            return PhaseSkipCause.TIMEOUT;
        }
        if (isMemoryTooLowForPhase(phaseName)) {
            return PhaseSkipCause.LOW_MEMORY;
        }
        return PhaseSkipCause.NONE;
    }

    /**
     * Check whether free memory is too low to safely enter a post-search phase
     * that re-executes tests (minimization, assertion generation, JUnit check).
     * Uses {@code MIN_FREE_MEM * 2} as the threshold to leave headroom.
     *
     * @param phaseName human-readable name for log messages
     * @return true if memory is too low (caller should skip the phase)
     */
    static boolean isMemoryTooLowForPhase(String phaseName) {
        LongSupplier freeMemoryBytes = freeMemoryBytesForTesting != null
                ? freeMemoryBytesForTesting : PhaseMemoryGuard::currentFreeHeapBytes;
        PhaseMemoryGuard guard =
                new PhaseMemoryGuard(freeMemoryBytes, System::gc, Properties.MIN_FREE_MEM * 2L);
        if (!guard.isExhausted()) {
            return false;
        }
        LoggingUtils.getEvoLogger().warn("* " + ClientProcess.getPrettyPrintIdentifier()
                + "Skipping " + phaseName + " due to low memory: "
                + (guard.getLastFreeBytes() / 1024 / 1024) + " MB free, need "
                + (guard.getThresholdBytes() / 1024 / 1024) + " MB");
        return true;
    }

    /**
     * Recompute average coverage from configured criteria by counting covered goals directly.
     * This is used as a fallback when chromosome-level aggregate coverage is stale.
     */
    private static double computeAverageCoverageFromCriteria(TestSuiteChromosome testSuite) {
        double sum = 0.0;
        int count = 0;
        for (Criterion criterion : Properties.CRITERION) {
            TestFitnessFactory<? extends TestFitnessFunction> factory = FitnessFunctions.getFitnessFactory(criterion);
            if (factory == null) {
                continue;
            }
            List<? extends TestFitnessFunction> goals = factory.getCoverageGoals();
            if (goals.isEmpty()) {
                sum += 1.0;
                count++;
                continue;
            }
            int covered = 0;
            for (TestFitnessFunction goal : goals) {
                if (goal.isCoveredBy(testSuite)) {
                    covered++;
                }
            }
            sum += (double) covered / (double) goals.size();
            count++;
        }
        return count == 0 ? 0.0 : sum / (double) count;
    }

    private TestSuiteChromosome generateTests() {
        // Make sure target class is loaded at this point
        TestCluster.getInstance();

        ContractChecker checker = null;
        if (Properties.CHECK_CONTRACTS) {
            checker = new ContractChecker();
            TestCaseExecutor.getInstance().addObserver(checker);
        }

        TestGenerationStrategy strategy = TestSuiteGeneratorHelper.getTestGenerationStrategy();
        TestSuiteChromosome testSuite = strategy.generateTests();

        if (Properties.CHECK_CONTRACTS) {
            TestCaseExecutor.getInstance().removeObserver(checker);
        }

        StatisticsSender.executedAndThenSendIndividualToMaster(testSuite);
        TestSuiteGeneratorHelper.getBytecodeStatistics();

        ClientServices.getInstance().getClientNode().publishPermissionStatistics();

        writeObjectPool(testSuite);

        /*
         * PUTGeneralizer generalizer = new PUTGeneralizer(); for (TestCase test
         * : tests) { generalizer.generalize(test); // ParameterizedTestCase put
         * = new ParameterizedTestCase(test); }
         */

        return testSuite;
    }

    /**
     * If {@link Properties#JUNIT_TESTS} is set, this method writes the given test cases
     * to the default directory {@link Properties#TEST_DIR}.
     * <p>
     * The name of the test will be equal to the SUT followed by the given suffix.
     * </p>
     *
     * @param testSuite a test suite.
     * @param suffix the suffix to add to the test suite name
     * @return the result of the test generation process
     */
    public static TestGenerationResult writeJUnitTestsAndCreateResult(TestSuiteChromosome testSuite, String suffix) {
        if (Properties.JUNIT_TESTS) {
            ClientServices.getInstance().getClientNode().changeState(ClientState.WRITING_TESTS);

            TestSuiteWriter suiteWriter = new TestSuiteWriter();
            suiteWriter.insertTests(testSuite.getTests());
            writeSuite(suiteWriter, testSuite, testSuite, suffix);
        }
        return TestGenerationResultBuilder.buildSuccessResult();
    }

    /**
     * Renders {@code suiteToWrite} through a writer that already holds its tests.
     *
     * @param source the suite whose extension initialisation order the writer uses
     */
    static void writeSuite(TestSuiteWriter suiteWriter, TestSuiteChromosome source,
                           TestSuiteChromosome suiteToWrite, String suffix) {
        suiteWriter.setExtensionInitializationOrder(source.getExtensionInitializationOrder());

        String name = Properties.TARGET_CLASS.substring(Properties.TARGET_CLASS.lastIndexOf(".") + 1);
        String testDir = Properties.TEST_DIR;

        LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                + "Writing JUnit test case '" + (name + suffix) + "' to " + testDir);
        suiteWriter.writeTestSuite(
                name + suffix, testDir, suiteToWrite.getLastExecutionResults());
    }

    /**
     * Write JUnit tests and create result.
     *
     * @param testSuite the test cases which should be written to file
     * @return the result of the test generation process
     */
    public static TestGenerationResult writeJUnitTestsAndCreateResult(TestSuiteChromosome testSuite) {
        return writeJUnitTestsAndCreateResult(testSuite, Properties.JUNIT_SUFFIX);
    }

    /**
     * Write failing JUnit tests.
     */
    public void writeJUnitFailingTests() {
        if (!Properties.CHECK_CONTRACTS) {
            return;
        }

        FailingTestSet.sendStatistics();

        if (Properties.JUNIT_TESTS) {

            TestSuiteWriter suiteWriter = new TestSuiteWriter();
            //suiteWriter.insertTests(FailingTestSet.getFailingTests());

            TestSuiteChromosome suite = new TestSuiteChromosome();
            for (TestCase test : FailingTestSet.getFailingTests()) {
                test.setFailing();
                suite.addTest(test);
            }

            String name = Properties.TARGET_CLASS.substring(Properties.TARGET_CLASS.lastIndexOf(".") + 1);
            String testDir = Properties.TEST_DIR;
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                    + "Writing failing test cases '"
                    + (name + Properties.JUNIT_SUFFIX) + "' to " + testDir);
            suiteWriter.insertAllTests(suite.getTests());
            FailingTestSet.writeJUnitTestSuite(suiteWriter);
            suiteWriter.setExtensionInitializationOrder(suite.getExtensionInitializationOrder());

            suiteWriter.writeTestSuite(name + Properties.JUNIT_FAILED_SUFFIX,
                    testDir, suite.getLastExecutionResults());
        }
    }

    private void writeObjectPool(TestSuiteChromosome suite) {
        if (!Properties.WRITE_POOL.isEmpty()) {
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                    + "Writing sequences to pool");
            ObjectPool pool = ObjectPool.getPoolFromTestSuite(suite);
            pool.writePool(Properties.WRITE_POOL);
        }
    }

    /**
     * Get the fitness factories for the configured criteria.
     *
     * @return a list of {@link org.evosuite.coverage.TestFitnessFactory}
     *         objects.
     */
    public static List<TestFitnessFactory<? extends TestFitnessFunction>> getFitnessFactories() {
        List<TestFitnessFactory<? extends TestFitnessFunction>> goalsFactory = new ArrayList<>();
        for (int i = 0; i < Properties.CRITERION.length; i++) {
            goalsFactory.add(FitnessFunctions.getFitnessFactory(Properties.CRITERION[i]));
        }

        return goalsFactory;
    }

}
