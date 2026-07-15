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
import org.evosuite.classpath.ClassPathHacker;
import org.evosuite.contracts.ContractChecker;
import org.evosuite.contracts.FailingTestSet;
import org.evosuite.coverage.CoverageCriteriaAnalyzer;
import org.evosuite.coverage.FitnessFunctions;
import org.evosuite.coverage.TestFitnessFactory;
import org.evosuite.coverage.dataflow.DefUseCoverageSuiteFitness;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.ga.stoppingconditions.StoppingCondition;
import org.evosuite.junit.JUnitAnalyzer;
import org.evosuite.junit.writer.TestSuiteWriter;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.LlmStatistics;
import org.evosuite.llm.postprocess.LlmPostProcessor;
import org.evosuite.llm.seeding.LlmPoolEnrichmentOrchestrator;
import org.evosuite.result.TestGenerationResult;
import org.evosuite.result.TestGenerationResultBuilder;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientState;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.runtime.sandbox.PermissionStatistics;
import org.evosuite.seeding.ObjectPool;
import org.evosuite.seeding.ObjectPoolManager;
import org.evosuite.setup.ExceptionMapGenerator;
import org.evosuite.setup.TargetClassInitializer;
import org.evosuite.setup.TestCluster;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.statistics.StatisticsSender;
import org.evosuite.strategy.TestGenerationStrategy;
import org.evosuite.symbolic.dse.DSEStatistics;
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
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.evosuite.testsuite.TestSuiteMinimizer;
import org.evosuite.testsuite.TestSuiteSerialization;
import org.evosuite.utils.ArrayUtil;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.text.NumberFormat;
import java.util.*;

/**
 * Main entry point. Does all the static analysis, invokes a test generation
 * strategy, and then applies postprocessing.
 *
 * @author Gordon Fraser
 */
public class TestSuiteGenerator {

    private static final String FOR_NAME = "forName";
    private static final Logger logger = LoggerFactory.getLogger(TestSuiteGenerator.class);
    private Criterion[] requestedCriteria = null;

    private LlmPoolEnrichmentOrchestrator llmOrchestrator;


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

        LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Analyzing classpath: ");

        ClientServices.getInstance().getClientNode().changeState(ClientState.INITIALIZATION);

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
        TestSuiteGeneratorHelper.printTestCriterion();
        org.evosuite.testcase.AllocationSensitiveSuitePruner.install();

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

        // Search is over — stop any background LLM enrichment so it can't keep
        // mutating shared pools (ConstantPoolManager, ObjectPoolManager,
        // CastClassManager) during post-processing or after RuntimeVariable
        // snapshots are taken.
        if (llmOrchestrator != null) {
            try {
                LlmStatistics.flushSeedingMetrics();
            } catch (Throwable t) {
                logger.warn("LLM seeding metric flush failed before cancelAll (non-fatal): {}", t.getMessage());
            }
            try {
                llmOrchestrator.cancelAll();
            } catch (Throwable t) {
                logger.warn("LLM enrichment cancelAll failed (non-fatal): {}", t.getMessage());
            }
            try {
                LlmStatistics.flushSeedingMetrics();
            } catch (Throwable t) {
                logger.warn("LLM seeding metric flush failed after cancelAll (non-fatal): {}", t.getMessage());
            }
        }

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
                Runtime rt = Runtime.getRuntime();
                long freeMb = (rt.maxMemory() - rt.totalMemory() + rt.freeMemory()) / 1024 / 1024;
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

            // progressMonitor.setCurrentPhase("Writing JUnit test cases");
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Writing tests to file");
            result = writeJUnitTestsAndCreateResult(testCases);
            try {
                writeJUnitFailingTests();
            } catch (Throwable t) {
                logger.warn("Writing failing tests failed (non-fatal): {}", t.toString());
            }
        }
        TestCaseExecutor.pullDown();
        /*
         * TODO: when we will have several processes running in parallel, we ll
         * need to handle the gathering of the statistics.
         */
        if (Properties.LLM_PROVIDER != Properties.LlmProvider.NONE) {
            org.evosuite.llm.LlmService.getInstance().getStatistics().publishRuntimeVariables();
        } else {
            org.evosuite.llm.LlmStatistics.initializeRuntimeVariables();
        }
        ClientServices.getInstance().getClientNode().changeState(ClientState.WRITING_STATISTICS);

        LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Done!");
        LoggingUtils.getEvoLogger().info("");

        return result != null ? result : TestGenerationResultBuilder.buildSuccessResult();
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
     * Apply any readability optimizations and other techniques that should use
     * or modify the generated tests.
     *
     * @param testSuite the test suite to post-process
     */
    protected void postProcessTests(TestSuiteChromosome testSuite) {
        logSuiteDiagnostics("postProcess:start", testSuite);

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
            if (!TimeController.getInstance().hasTimeToExecuteATestCase()
                    || isMemoryTooLowForPhase("minimization")) {
                MinimizationStopCause stopCause = TimeController.getInstance().hasTimeToExecuteATestCase()
                        ? MinimizationStopCause.LOW_MEMORY
                        : MinimizationStopCause.TIMEOUT;
                minimizationResult = MinimizationResult.preflightAborted(testSuite, stopCause);
                if (TimeController.getInstance().hasTimeToExecuteATestCase()) {
                    // Time is available but memory is low — already logged by the check
                } else {
                    LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                            + "Skipping minimization because not enough time is left");
                }
                ClientServices.track(RuntimeVariable.Result_Size, testSuite.size());
                ClientServices.track(RuntimeVariable.Minimized_Size, testSuite.size());
                ClientServices.track(RuntimeVariable.Result_Length, testSuite.totalLengthOfTestCases());
                ClientServices.track(RuntimeVariable.Minimized_Length, testSuite.totalLengthOfTestCases());
                publishMinimizationResult(minimizationResult);
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

            ClientServices.track(RuntimeVariable.Result_Size, testSuite.size());
            ClientServices.track(RuntimeVariable.Minimized_Size, testSuite.size());
            ClientServices.track(RuntimeVariable.Result_Length, testSuite.totalLengthOfTestCases());
            ClientServices.track(RuntimeVariable.Minimized_Length, testSuite.totalLengthOfTestCases());
            publishMinimizationResult(minimizationResult);
        }

        if (Properties.COVERAGE) {
            ClientServices.getInstance().getClientNode().changeState(ClientState.COVERAGE_ANALYSIS);
            if (!TimeController.getInstance().hasTimeToExecuteATestCase()) {
                LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                        + "Skipping coverage analysis because not enough time is left");
            } else if (isMemoryTooLowForPhase("coverage analysis")) {
                // skip — message already logged by the check
            } else {
                Criterion[] originalCriteria = Properties.CRITERION;
                if (requestedCriteria != null) {
                    Properties.CRITERION = requestedCriteria;
                }
                try {
                    CoverageCriteriaAnalyzer.analyzeCoverage(testSuite);
                } finally {
                    Properties.CRITERION = originalCriteria;
                }
            }
        }

        double coverage = testSuite.getCoverage();
        if (coverage == 0.0 && Properties.CRITERION.length > 1) {
            double recomputedCoverage = computeAverageCoverageFromCriteria(testSuite);
            if (recomputedCoverage > 0.0) {
                coverage = recomputedCoverage;
            }
        }

        if (ArrayUtil.contains(Properties.CRITERION, Criterion.MUTATION)
                || ArrayUtil.contains(Properties.CRITERION, Criterion.STRONGMUTATION)) {
            // SearchStatistics.getInstance().mutationScore(coverage);
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

        // printBudget(ga); // TODO - need to move this somewhere else
        if (ArrayUtil.contains(Properties.CRITERION, Criterion.DEFUSE) && Properties.ANALYSIS_CRITERIA.isEmpty()) {
            DefUseCoverageSuiteFitness.printCoverage();
        }

        DSEStatistics.getInstance().trackStatistics();

        if (Properties.isDSEEnabledInLocalSearch() || Properties.isDSEStrategySelected()) {
            DSEStatistics.getInstance().logStatistics();
        }

        if (Properties.FILTER_SANDBOX_TESTS) {
            if (!TimeController.getInstance().hasTimeToExecuteATestCase()) {
                LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                        + "Skipping sandbox test filtering because not enough time is left");
            } else if (isMemoryTooLowForPhase("sandbox test filtering")) {
                // skip — message already logged by the check
            } else {
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

        boolean generateStandardAssertions = shouldGenerateStandardAssertions();
        if (generateStandardAssertions) {
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Generating assertions");
            // progressMonitor.setCurrentPhase("Generating assertions");
            ClientServices.getInstance().getClientNode().changeState(ClientState.ASSERTION_GENERATION);
            if (!TimeController.getInstance().isThereStillTimeInThisPhase()) {
                LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                        + "Skipping assertion generation because not enough time is left");
            } else if (isMemoryTooLowForPhase("assertion generation")) {
                // skip — message already logged by the check
            } else {
                TestSuiteGeneratorHelper.addAssertions(testSuite);
            }
            StatisticsSender.sendIndividualToMaster(testSuite); // FIXME: can we
            // pass the list
            // of
            // testsuitechromosomes?
        }

        int llmPostProcessingAssertionsApplied = 0;
        if (Properties.LLM_POSTPROCESSING_ENABLED) {
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                    + "Running unified LLM post-processing");
            ClientServices.getInstance().getClientNode().changeState(ClientState.LLM_POSTPROCESSING);
            if (!TimeController.getInstance().isThereStillTimeInThisPhase()) {
                LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                        + "Skipping unified LLM post-processing because not enough time is left");
                LlmPostProcessor.publishSkippedPostProcessingMetrics("timeout", minimizationResult);
            } else if (isMemoryTooLowForPhase("unified LLM post-processing")) {
                LlmPostProcessor.publishSkippedPostProcessingMetrics("low_memory", minimizationResult);
            } else {
                try {
                    llmPostProcessingAssertionsApplied =
                            new LlmPostProcessor().runUnifiedPostProcessing(testSuite, minimizationResult);
                } catch (RuntimeException e) {
                    LoggingUtils.getEvoLogger().warn(
                            "Unified LLM post-processing failed; continuing without changes", e);
                    LlmPostProcessor.publishSkippedPostProcessingMetrics("phase_failure", minimizationResult);
                }
            }
        } else {
            LlmPostProcessor.publishSkippedPostProcessingMetrics("disabled", minimizationResult);
        }

        if (Properties.NO_RUNTIME_DEPENDENCY) {
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                    + "Property NO_RUNTIME_DEPENDENCY is set to true - skipping JUnit compile check");
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                    + "WARNING: Not including the runtime dependencies is likely to lead to flaky tests!");
        } else if (Properties.JUNIT_TESTS && (Properties.JUNIT_CHECK == Properties.JUnitCheckValues.TRUE
                || Properties.JUNIT_CHECK == Properties.JUnitCheckValues.OPTIONAL)) {
            if (ClassPathHacker.isJunitCheckAvailable()) {
                int beforeJUnitCheck = testSuite.size();
                compileAndCheckTests(testSuite);
                logger.info("JUnit compile-check changed suite size {} -> {}",
                        beforeJUnitCheck, testSuite.size());
                if (beforeJUnitCheck > 0 && testSuite.size() == 0) {
                    logger.warn("JUnit compile-check removed all tests ({} -> 0).", beforeJUnitCheck);
                }
                logSuiteDiagnostics("postProcess:afterJUnitCheck", testSuite);
            } else {
                logger.warn("Cannot run Junit test. Cause {}", ClassPathHacker.getCause());
            }
        }
        LlmPostProcessor.publishFinalAssertionReconciliation(testSuite,
                llmPostProcessingAssertionsApplied);
    }

    static boolean shouldGenerateStandardAssertions() {
        return Properties.ASSERTIONS
                && !(LlmPostProcessor.isAnyFeatureEnabled() && Properties.LLM_POSTPROCESSING_ASSERTIONS);
    }

    private void logSuiteDiagnostics(String phase, TestSuiteChromosome suite) {
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

    private void publishMinimizationResult(MinimizationResult result) {
        ClientServices.track(RuntimeVariable.Minimization_Status, result.getStatus().name());
        ClientServices.track(RuntimeVariable.Minimization_Stop_Cause,
                result.getUnderlyingStopCause().name());
        ClientServices.track(RuntimeVariable.Minimization_Original_Tests,
                result.getOriginalTests());
        ClientServices.track(RuntimeVariable.Minimization_Original_Length,
                result.getOriginalLength());
        ClientServices.track(RuntimeVariable.Minimization_Final_Tests,
                result.getFinalTests());
        ClientServices.track(RuntimeVariable.Minimization_Final_Length,
                result.getFinalLength());
        ClientServices.track(RuntimeVariable.Minimization_Elapsed_Millis,
                result.getElapsedMillis());
    }

    /**
     * Check whether free memory is too low to safely enter a post-search phase
     * that re-executes tests (minimization, assertion generation, JUnit check).
     * Uses {@code MIN_FREE_MEM * 2} as the threshold to leave headroom.
     *
     * @param phaseName human-readable name for log messages
     * @return true if memory is too low (caller should skip the phase)
     */
    private boolean isMemoryTooLowForPhase(String phaseName) {
        Runtime runtime = Runtime.getRuntime();
        long freeMem = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
        long threshold = Properties.MIN_FREE_MEM * 2L;
        if (freeMem < threshold) {
            System.gc();
            freeMem = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
            if (freeMem < threshold) {
                LoggingUtils.getEvoLogger().warn("* " + ClientProcess.getPrettyPrintIdentifier()
                        + "Skipping " + phaseName + " due to low memory: "
                        + (freeMem / 1024 / 1024) + " MB free, need "
                        + (threshold / 1024 / 1024) + " MB");
                return true;
            }
        }
        return false;
    }

    /**
     * Compile and run the given tests. Remove from input list all tests that do
     * not compile, and handle the cases of instability (either remove tests or
     * comment out failing assertions).
     *
     * @param chromosome the test suite to compile and check
     */
    private void compileAndCheckTests(TestSuiteChromosome chromosome) {
        LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                + "Compiling and checking tests");
        final int originalSuiteSize = chromosome.size();

        if (!JUnitAnalyzer.isJavaCompilerAvailable()) {
            String msg = "No Java compiler is available. Make sure to run EvoSuite with the JDK and not the JRE. "
                    + "You can try to setup the JAVA_HOME system variable to point to it, "
                    + "as well as to make sure that the PATH variable points to the JDK before any JRE.";
            logger.error(msg);
            throw new RuntimeException(msg);
        }

        if (isMemoryTooLowForPhase("JUnit check")) {
            return;
        }

        ClientServices.getInstance().getClientNode().changeState(ClientState.JUNIT_CHECK);

        // Store this value; if this option is true then the JUnit check
        // would not succeed, as the JUnit classloader wouldn't find the class
        boolean junitSeparateClassLoader = Properties.USE_SEPARATE_CLASSLOADER;
        Properties.USE_SEPARATE_CLASSLOADER = false;

        int numUnstable = 0;

        // note: compiling and running JUnit tests can be very time consuming
        if (!TimeController.getInstance().isThereStillTimeInThisPhase()) {
            Properties.USE_SEPARATE_CLASSLOADER = junitSeparateClassLoader;
            return;
        }

        List<TestCase> testCases = chromosome.getTests(); // make copy of
        // current tests
        int beforeCompileFilter = testCases.size();

        // first, let's just get rid of all the tests that do not compile
        JUnitAnalyzer.removeTestsThatDoNotCompile(testCases);
        int removedByCompileFilter = beforeCompileFilter - testCases.size();
        if (removedByCompileFilter > 0) {
            logger.warn("JUnit compile filter removed {} test(s) out of {} before runtime checks",
                    removedByCompileFilter, beforeCompileFilter);
        }

        // compile and run each test one at a time. and keep track of total time
        long start = java.lang.System.currentTimeMillis();
        Iterator<TestCase> iter = testCases.iterator();
        int removedInSingleTestChecks = 0;
        while (iter.hasNext()) {
            if (JUnitAnalyzer.isCompileCheckDisabledDueToInfrastructure()) {
                logger.warn("Aborting remaining per-test JUnit checks due to prior javac infrastructure failure");
                break;
            }
            if (!TimeController.getInstance().hasTimeToExecuteATestCase()) {
                break;
            }
            TestCase tc = iter.next();
            List<TestCase> list = new ArrayList<>();
            list.add(tc);
            numUnstable += JUnitAnalyzer.handleTestsThatAreUnstable(list);
            if (list.isEmpty()) {
                // if the test was unstable and deleted, need to remove it from
                // final testSuite
                iter.remove();
                removedInSingleTestChecks++;
            }
        }
        if (removedInSingleTestChecks > 0) {
            logger.warn("JUnit per-test instability checks removed {} test(s)", removedInSingleTestChecks);
        }
        /*
         * compiling and running each single test individually will take more
         * than compiling/running everything in on single suite. so it can be
         * used as an upper bound
         */
        long delta = java.lang.System.currentTimeMillis() - start;

        if (!JUnitAnalyzer.isCompileCheckDisabledDueToInfrastructure()) {
            numUnstable += checkAllTestsIfTime(testCases, delta);
        }

        // second passage on reverse order, this is to spot dependencies among
        // tests
        if (!JUnitAnalyzer.isCompileCheckDisabledDueToInfrastructure() && testCases.size() > 1) {
            Collections.reverse(testCases);
            numUnstable += checkAllTestsIfTime(testCases, delta);
        }

        chromosome.setExtensionInitializationOrder(resolveExtensionInitializationOrderIfNeeded(testCases, delta));

        chromosome.clearTests(); // remove all tests
        for (TestCase testCase : testCases) {
            chromosome.addTest(testCase); // add back the filtered tests
        }
        logger.info("JUnit check suite reduction: {} -> {}", originalSuiteSize, chromosome.size());
        if (originalSuiteSize > 0 && chromosome.size() == 0) {
            logger.warn("JUnit check removed all tests from suite");
        }

        boolean unstable = (numUnstable > 0);

        if (!TimeController.getInstance().isThereStillTimeInThisPhase()) {
            logger.warn("JUnit checking timed out");
        }

        ClientServices.track(RuntimeVariable.HadUnstableTests, unstable);
        ClientServices.track(RuntimeVariable.NumUnstableTests, numUnstable);
        Properties.USE_SEPARATE_CLASSLOADER = junitSeparateClassLoader;

    }

    private static int checkAllTestsIfTime(List<TestCase> testCases, long delta) {
        if (TimeController.getInstance().hasTimeToExecuteATestCase()
                && TimeController.getInstance().isThereStillTimeInThisPhase(delta)) {
            return JUnitAnalyzer.handleTestsThatAreUnstable(testCases);
        }
        return 0;
    }

    private static List<String> resolveExtensionInitializationOrderIfNeeded(List<TestCase> testCases, long delta) {
        if (Properties.TEST_FORMAT != Properties.OutputFormat.JUNIT5
                || !Properties.TEST_EXTENSION_MODE
                || !Properties.RESET_STATIC_FIELDS
                || testCases == null
                || testCases.size() < 2) {
            return Collections.emptyList();
        }

        if (!TimeController.getInstance().hasTimeToExecuteATestCase()
                || !TimeController.getInstance().isThereStillTimeInThisPhase(delta)) {
            return Collections.emptyList();
        }

        List<String> observedInitializationOrder = ClassReInitializer.getInstance().getInitializedClasses();
        if (Properties.TEST_EXTENSION_PRELOAD_INITIALIZED_CLASSES) {
            List<String> preloadOrder = buildExtensionInitializationOrder(observedInitializationOrder);
            if (!preloadOrder.isEmpty()) {
                logger.info("Extension preloading enabled. Emitting explicit initialization order with {} classes.",
                        preloadOrder.size());
            }
            return preloadOrder;
        }

        JUnitAnalyzer.OrderSensitivityAnalysis analysis = JUnitAnalyzer.analyzeOrderSensitivity(testCases);
        if (!analysis.isOrderSensitive()) {
            return Collections.emptyList();
        }

        if (observedInitializationOrder.size() < 2) {
            return Collections.emptyList();
        }

        List<String> explicitOrder = buildExtensionInitializationOrder(observedInitializationOrder);
        if (explicitOrder.size() < 2) {
            return Collections.emptyList();
        }

        logger.info("Detected order-sensitive suite under extension mode. "
                        + "Forward failures: {}, reverse failures: {}. "
                        + "Emitting explicit initialization order with {} classes.",
                analysis.getForwardFailures(),
                analysis.getReverseFailures(),
                explicitOrder.size());
        return explicitOrder;
    }

    private static List<String> buildExtensionInitializationOrder(List<String> observedInitializationOrder) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (Properties.TARGET_CLASS != null && !Properties.TARGET_CLASS.trim().isEmpty()) {
            ordered.add(Properties.TARGET_CLASS.trim());
        }
        if (observedInitializationOrder != null) {
            for (String className : observedInitializationOrder) {
                if (className == null) {
                    continue;
                }
                String trimmed = className.trim();
                if (!trimmed.isEmpty()) {
                    ordered.add(trimmed);
                }
            }
        }

        int maxClasses = Math.max(1, Properties.TEST_EXTENSION_PRELOAD_MAX_CLASSES);
        List<String> limited = new ArrayList<>(Math.min(maxClasses, ordered.size()));
        for (String className : ordered) {
            limited.add(className);
            if (limited.size() >= maxClasses) {
                break;
            }
        }
        return limited;
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
        List<TestCase> tests = testSuite.getTests();
        if (Properties.JUNIT_TESTS) {
            ClientServices.getInstance().getClientNode().changeState(ClientState.WRITING_TESTS);

            TestSuiteWriter suiteWriter = new TestSuiteWriter();
            suiteWriter.insertTests(tests);
            suiteWriter.setExtensionInitializationOrder(testSuite.getExtensionInitializationOrder());

            String name = Properties.TARGET_CLASS.substring(Properties.TARGET_CLASS.lastIndexOf(".") + 1);
            String testDir = Properties.TEST_DIR;

            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                    + "Writing JUnit test case '" + (name + suffix) + "' to " + testDir);
            suiteWriter.writeTestSuite(name + suffix, testDir, testSuite.getLastExecutionResults());
        }
        return TestGenerationResultBuilder.buildSuccessResult();
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
     * Get the fitness functions for the configured criteria.
     *
     * @return a list of {@link org.evosuite.testsuite.TestSuiteFitnessFunction}
     *         objects.
     */
    public static List<TestSuiteFitnessFunction> getFitnessFunctions() {
        List<TestSuiteFitnessFunction> ffs = new ArrayList<>();
        for (int i = 0; i < Properties.CRITERION.length; i++) {
            ffs.add(FitnessFunctions.getFitnessFunction(Properties.CRITERION[i]));
        }

        return ffs;
    }

    /**
     * Prints out all information regarding this GAs stopping conditions.
     * <p>
     * So far only used for testing purposes in TestSuiteGenerator.
     * </p>
     *
     * @param algorithm the genetic algorithm to print the budget for
     */
    public void printBudget(GeneticAlgorithm<?> algorithm) {
        LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier()
                + "Search Budget:");
        for (StoppingCondition<?> sc : algorithm.getStoppingConditions()) {
            LoggingUtils.getEvoLogger().info("\t- " + sc.toString());
        }
    }

    /**
     * Get the budget string for the genetic algorithm.
     *
     * @param algorithm the genetic algorithm to get the budget string for
     * @return a {@link java.lang.String} object.
     */
    public String getBudgetString(GeneticAlgorithm<?> algorithm) {
        StringBuilder r = new StringBuilder();
        for (StoppingCondition<?> sc : algorithm.getStoppingConditions()) {
            r.append(sc.toString()).append(" ");
        }

        return r.toString();
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

    /**
     * Main method.
     *
     * @param args an array of {@link java.lang.String} objects.
     */
    public static void main(String[] args) {
        TestSuiteGenerator generator = new TestSuiteGenerator();
        generator.generateTestSuite();
        System.exit(0);
    }

}
