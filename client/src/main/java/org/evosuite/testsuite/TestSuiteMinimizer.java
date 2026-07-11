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
package org.evosuite.testsuite;

import org.evosuite.Properties;
import org.evosuite.Properties.SecondaryObjective;
import org.evosuite.TestGenerationContext;
import org.evosuite.TimeController;
import org.evosuite.coverage.TestFitnessFactory;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.junit.CoverageAnalysis;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientState;
import org.evosuite.rmi.service.ClientStateInformation;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.execution.reset.ClassReInitializer;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static java.util.Comparator.comparingInt;

/**
 * <p>
 * TestSuiteMinimizer class.
 * </p>
 *
 * @author Gordon Fraser
 */
public class TestSuiteMinimizer {

    /**
     * Logger.
     */
    private static final Logger logger = LoggerFactory.getLogger(TestSuiteMinimizer.class);
    private final List<TestFitnessFactory<?>> testFitnessFactories = new ArrayList<>();

    /**
     * Assume the search has not started until startTime != 0.
     */
    protected static long startTime = 0L;

    interface StopCauseProvider {
        MinimizationStopCause getStopCause();
    }

    interface PreflightExecutor {
        ExecutionResult run(TestCase test);
    }

    private MinimizationStopCause firstStopCause = MinimizationStopCause.NONE;
    private final StopCauseProvider stopCauseProvider;
    private final PreflightExecutor preflightExecutor;

    /**
     * <p>
     * Constructor for TestSuiteMinimizer.
     * </p>
     *
     * @param factory a {@link org.evosuite.coverage.TestFitnessFactory} object.
     */
    public TestSuiteMinimizer(TestFitnessFactory<?> factory) {
        this(Collections.singletonList(factory), null, null);
    }

    public TestSuiteMinimizer(List<TestFitnessFactory<? extends TestFitnessFunction>> factories) {
        this(factories, null, null);
    }

    TestSuiteMinimizer(List<? extends TestFitnessFactory<?>> factories, StopCauseProvider stopCauseProvider) {
        this(factories, stopCauseProvider, null);
    }

    TestSuiteMinimizer(List<? extends TestFitnessFactory<?>> factories,
                       StopCauseProvider stopCauseProvider,
                       PreflightExecutor preflightExecutor) {
        this.testFitnessFactories.addAll(factories);
        this.stopCauseProvider = stopCauseProvider;
        this.preflightExecutor = preflightExecutor;
    }

    /**
     * <p>
     * minimize.
     * </p>
     *
     * @param suite           a {@link org.evosuite.testsuite.TestSuiteChromosome} object.
     * @param minimizePerTest true is to minimize tests, false is to minimize suites.
     */
    public MinimizationResult minimize(TestSuiteChromosome suite, boolean minimizePerTest) {
        startTime = System.currentTimeMillis();
        firstStopCause = MinimizationStopCause.NONE;
        final int originalTests = suite.size();
        final int originalLength = suite.totalLengthOfTestCases();

        SecondaryObjective strategy = Properties.SECONDARY_OBJECTIVE[0];

        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Result_Size,
                suite.size());
        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Result_Length,
                suite.totalLengthOfTestCases());

        logger.info("Minimization Strategy: " + strategy + ", " + suite.size() + " tests");

        // Snapshot the input suite so that, if the inner work OOMs or otherwise throws,
        // we can revert to the pre-minimization suite. The un-minimized suite covers a
        // strict superset of the minimized one, so this preserves coverage at the cost
        // of giving up on minimization for this run. (We intentionally do NOT revert
        // when minimization legitimately produces an empty suite — e.g., when no
        // statements covered any goal on re-execution — only when it throws.)
        final List<TestChromosome> snapshot = deepCloneTests(suite.tests);
        MinimizationStatus status = MinimizationStatus.COMPLETED;

        try {
            if (minimizePerTest) {
                status = minimizeTests(suite);
            } else {
                status = minimizeSuite(suite);
            }
        } catch (Throwable t) {
            status = MinimizationStatus.FAILED;
            logger.error("Minimization failed ({}); reverting to pre-minimization snapshot of {} tests.",
                    t.getClass().getSimpleName(), snapshot.size(), t);
            suite.tests.clear();
            suite.tests.addAll(deepCloneTests(snapshot));
            System.gc();
        }

        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Minimized_Size,
                suite.size());
        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Minimized_Length,
                suite.totalLengthOfTestCases());

        MinimizationResult result = new MinimizationResult(status, firstStopCause,
                originalTests, originalLength, suite.size(), suite.totalLengthOfTestCases(),
                System.currentTimeMillis() - startTime);
        publishResult(result);
        return result;
    }

    private List<TestChromosome> deepCloneTests(List<TestChromosome> tests) {
        List<TestChromosome> clones = new ArrayList<>(tests.size());
        for (TestChromosome test : tests) {
            clones.add(test.clone());
        }
        return clones;
    }

    private void publishResult(MinimizationResult result) {
        ClientServices.track(RuntimeVariable.Minimization_Status, result.getStatus().name());
        ClientServices.track(RuntimeVariable.Minimization_Stop_Cause, result.getUnderlyingStopCause().name());
        ClientServices.track(RuntimeVariable.Minimization_Original_Tests, result.getOriginalTests());
        ClientServices.track(RuntimeVariable.Minimization_Original_Length, result.getOriginalLength());
        ClientServices.track(RuntimeVariable.Minimization_Final_Tests, result.getFinalTests());
        ClientServices.track(RuntimeVariable.Minimization_Final_Length, result.getFinalLength());
        ClientServices.track(RuntimeVariable.Minimization_Elapsed_Millis, result.getElapsedMillis());
    }

    private void updateClientStatus(int progress) {
        ClientState state = ClientState.MINIMIZATION;
        ClientStateInformation information = new ClientStateInformation(state);
        information.setProgress(progress);
        ClientServices.getInstance().getClientNode().changeState(state, information);
    }

    private void filterJUnitCoveredGoals(List<TestFitnessFunction> goals) {
        if (Properties.JUNIT.isEmpty()) {
            return;
        }

        LoggingUtils.getEvoLogger().info("* Determining coverage of existing tests");
        String[] testClasses = Properties.JUNIT.split(":");
        for (String testClass : testClasses) {
            try {
                Class<?> junitClass = Class.forName(testClass, true,
                        TestGenerationContext.getInstance().getClassLoaderForSUT());
                Set<TestFitnessFunction> coveredGoals = CoverageAnalysis.getCoveredGoals(junitClass, goals);
                LoggingUtils.getEvoLogger().info("* Removing " + coveredGoals.size()
                        + " goals already covered by JUnit (total: " + goals.size() + ")");
                //logger.warn("Removing " + coveredGoals + " goals already covered by JUnit (total: " + goals + ")");
                goals.removeAll(coveredGoals);
                logger.info("Remaining goals: " + goals.size() + ": " + goals);
            } catch (ClassNotFoundException e) {
                LoggingUtils.getEvoLogger().warn("* Failed to find JUnit test suite: " + Properties.JUNIT);
            }
        }
    }

    /**
     * Minimize test suite with respect to the isCovered Method of the goals
     * defined by the supplied TestFitnessFactory.
     *
     * @param suite a {@link org.evosuite.testsuite.TestSuiteChromosome} object.
     */
    private MinimizationStatus minimizeTests(TestSuiteChromosome suite) {

        logger.info("Minimizing per test");
        final int originalSuiteSize = suite.size();
        final int originalSuiteLength = suite.totalLengthOfTestCases();

        ExecutionTracer.enableTraceCalls();

        // Ensure ExecutionTracer is in a clean state before re-executing tests.
        // After the search phase (especially if the last execution timed out),
        // the tracer may be disabled or the kill switch may be active.
        ExecutionTracer.enable();
        ExecutionTracer.setKillSwitch(false);

        // Reset ALL SUT classes to their <clinit> state before re-executing.
        // During the search and inlining phases, __STATIC_RESET() may have
        // corrupted static state due to cross-class dependencies (class A's
        // reset depends on class B's fields, but B was reset first, leaving
        // B's fields null when A tries to use them).  Two passes handle the
        // common case: the first restores leaf classes, the second restores
        // classes whose <clinit> depends on those leaf classes.
        ClassReInitializer.getInstance().resetAllInitializedClasses(2);

        // Probe: re-execute all tests to verify they can still reproduce
        // coverage.  If ALL tests produce empty traces (e.g., due to broken
        // static state after ClassReInitializer's __STATIC_RESET()), skip
        // minimization entirely and preserve the suite as-is — including
        // the original covered goals from the search phase, so that
        // downstream coverage analysis can still report them.
        boolean allTracesEmpty = true;
        int testsWithExceptions = 0;
        String firstExceptionType = null;
        for (int i = 0; i < suite.getTestChromosomes().size(); i++) {
            MinimizationStopCause stopCause = getStopCause();
            if (stopCause != MinimizationStopCause.NONE) {
                logger.warn("Minimization preflight aborted at test {}/{} due to time/memory exhaustion. "
                                + "Skipping minimization to preserve original archive tests.",
                        i, suite.getTestChromosomes().size());
                return MinimizationStatus.PREFLIGHT_ABORTED;
            }
            TestChromosome test = suite.getTestChromosomes().get(i);
            ExecutionResult result = runPreflight(test.getTestCase());
            if (!result.getTrace().getCoveredTrueBranches().isEmpty()
                    || !result.getTrace().getCoveredFalseBranches().isEmpty()
                    || !result.getTrace().getCoveredBranchlessMethods().isEmpty()
                    || !result.getTrace().getCoveredMethods().isEmpty()
                    || !result.getTrace().getCoveredLines().isEmpty()) {
                allTracesEmpty = false;
                break; // At least one test has coverage — proceed with minimization
            }
            if (!result.getAllThrownExceptions().isEmpty()) {
                testsWithExceptions++;
                if (firstExceptionType == null) {
                    Integer firstPos = result.getFirstPositionOfThrownException();
                    if (firstPos != null) {
                        Throwable ex = result.getExceptionThrownAtPosition(firstPos);
                        if (ex != null) {
                            firstExceptionType = ex.getClass().getSimpleName();
                        }
                    }
                }
            }
        }
        if (allTracesEmpty && !suite.getTestChromosomes().isEmpty()) {
            logger.warn("ALL {} tests produced empty traces on re-execution "
                    + "({} threw exceptions, first: {}). "
                    + "Skipping minimization to preserve original archive tests.",
                    suite.getTestChromosomes().size(), testsWithExceptions,
                    firstExceptionType != null ? firstExceptionType : "none");
            return MinimizationStatus.REEXECUTION_FAILED;
        }

        // Tests can reproduce coverage — now clear stale state and proceed.
        for (TestChromosome test : suite.getTestChromosomes()) {
            test.setChanged(true); // implies test.clearCachedResults();
            // Clear stale covered goals from the search phase.  The archive
            // stores test references (not clones), so goals accumulated during
            // the search may no longer be reproducible on re-execution.
            // Without this, isCovered() short-circuits via isGoalCovered()
            // and returns true without re-executing, masking tests that no
            // longer actually cover anything.
            test.getTestCase().clearCoveredGoals();
        }

        List<TestFitnessFunction> goals = new ArrayList<>();
        for (TestFitnessFactory<?> ff : testFitnessFactories) {
            goals.addAll(ff.getCoverageGoals());
        }
        filterJUnitCoveredGoals(goals);

        int currentGoal = 0;
        int numGoals = goals.size();

        if (Properties.MINIMIZE_SORT) {
            Collections.sort(goals);
        }

        Set<TestFitnessFunction> covered = new LinkedHashSet<>();
        List<TestChromosome> minimizedTests = new ArrayList<>();
        int goalsWithoutCoveringTests = 0;
        int discardedMinimizedTestsWithoutCoverage = 0;
        int keptMinimizedTests = 0;

        MinimizationStopCause incompleteStopCause = MinimizationStopCause.NONE;

        for (TestFitnessFunction goal : goals) {
            updateClientStatus(numGoals > 0 ? 100 * currentGoal / numGoals : 100);
            currentGoal++;
            MinimizationStopCause stopCause = getStopCause();
            if (stopCause != MinimizationStopCause.NONE) {
                logger.warn("Minimization timeout/low-memory. Using partial results.");
                incompleteStopCause = stopCause;
                break;
            }
            logger.info("Considering goal: " + goal);
            if (Properties.MINIMIZE_SKIP_COINCIDENTAL) {
                boolean innerTimeout = false;
                for (TestChromosome test : minimizedTests) {
                    stopCause = getStopCause();
                    if (stopCause != MinimizationStopCause.NONE) {
                        logger.warn("Minimization timeout/low-memory. Using partial results.");
                        incompleteStopCause = stopCause;
                        innerTimeout = true;
                        break;
                    }
                    if (goal.isCovered(test)) {
                        logger.info("Covered by minimized test: " + goal);
                        covered.add(goal);
                        break;
                    }
                }
                if (innerTimeout) {
                    break;
                }
            }
            if (covered.contains(goal)) {
                logger.info("Already covered: " + goal);
                logger.info("Now the suite covers " + covered.size() + "/"
                        + goals.size() + " goals");
                continue;
            }

            List<TestChromosome> coveringTests = new ArrayList<>();
            boolean innerTimeout = false;
            for (TestChromosome test : suite.getTestChromosomes()) {
                // Each isCovered() may re-execute the test; under memory
                // pressure these can take seconds each, so re-check between
                // iterations rather than only at the goal boundary.
                stopCause = getStopCause();
                if (stopCause != MinimizationStopCause.NONE) {
                    logger.warn("Minimization timeout/low-memory while scanning covering tests. "
                            + "Using partial results.");
                    incompleteStopCause = stopCause;
                    innerTimeout = true;
                    break;
                }
                if (goal.isCovered(test)) {
                    coveringTests.add(test);
                }
            }
            if (innerTimeout) {
                break;
            }
            Collections.sort(coveringTests);
            if (!coveringTests.isEmpty()) {
                TestChromosome test = coveringTests.get(0);
                org.evosuite.testcase.TestCaseMinimizer minimizer = new org.evosuite.testcase.TestCaseMinimizer(
                        goal);
                TestChromosome copy = test.clone();
                minimizer.minimize(copy);
                stopCause = getStopCause();
                if (stopCause != MinimizationStopCause.NONE) {
                    logger.warn("Minimization timeout/low-memory. Using partial results.");
                    incompleteStopCause = stopCause;
                    break;
                }

                // TODO: Need proper list of covered goals
                copy.getTestCase().clearCoveredGoals();

                // Add ALL goals covered by the minimized test
                int coveredBefore = covered.size();
                for (TestFitnessFunction g : goals) {
                    // With thousands of goals, this scan dominates the
                    // per-goal cost; break out if we ran out of budget.
                    stopCause = getStopCause();
                    if (stopCause != MinimizationStopCause.NONE) {
                        logger.warn("Minimization timeout/low-memory while scanning goals "
                                + "for coverage of minimized test. Using partial results.");
                        incompleteStopCause = stopCause;
                        innerTimeout = true;
                        break;
                    }
                    if (g.isCovered(copy)) { // isCovered(copy) adds the goal
                        covered.add(g);
                        logger.info("Goal covered by minimized test: " + g);
                    }
                }
                // Only keep the minimized test if it actually covers goals
                // on re-execution.  Stale archive entries may no longer
                // reproduce their coverage, and an empty/failing test would
                // just bloat the output suite.
                if (covered.size() > coveredBefore) {
                    insertMinimizedTest(minimizedTests, copy);
                    keptMinimizedTests++;
                } else {
                    discardedMinimizedTestsWithoutCoverage++;
                    logger.warn("Discarding minimized test that covers no goals on re-execution. "
                                    + "lastExecutionResult={}",
                            summarizeExecutionResult(copy.getLastExecutionResult()));
                }

                logger.info("After new test the suite covers " + covered.size() + "/"
                        + goals.size() + " goals");

                if (innerTimeout) {
                    // Aborted mid-goal-scan — kept whatever this iteration
                    // contributed, now exit the outer loop too.
                    break;
                }
            } else {
                goalsWithoutCoveringTests++;
                logger.info("Goal is not covered: " + goal);
            }
        }

        logger.info("Minimized suite covers " + covered.size() + "/" + goals.size()
                + " goals");

        List<TestChromosome> originalTestChromosomes = null;
        if (incompleteStopCause != MinimizationStopCause.NONE) {
            originalTestChromosomes = new ArrayList<>(suite.getTestChromosomes());
        }

        List<TestChromosome> finalTests = new ArrayList<>();

        if (incompleteStopCause != MinimizationStopCause.NONE && originalTestChromosomes != null) {
            finalTests.addAll(originalTestChromosomes);
        }

        finalTests.addAll(minimizedTests);
        suite.replaceWithTestChromosomes(finalTests);

        logger.info("Minimization diagnostics: originalTests={}, originalLength={}, "
                        + "keptMinimizedTests={}, discardedNoCoverage={}, goalsWithoutCoveringTests={}, "
                        + "resultTests={}, resultLength={}, timeout={}",
                originalSuiteSize, originalSuiteLength,
                keptMinimizedTests, discardedMinimizedTestsWithoutCoverage, goalsWithoutCoveringTests,
                suite.size(), suite.totalLengthOfTestCases(),
                incompleteStopCause != MinimizationStopCause.NONE);
        if (originalSuiteSize > 0 && suite.size() == 0) {
            logger.warn("Minimization resulted in empty suite ({} -> 0 tests). "
                            + "discardedNoCoverage={}, goalsWithoutCoveringTests={}, timeout={}",
                    originalSuiteSize, discardedMinimizedTestsWithoutCoverage, goalsWithoutCoveringTests,
                    incompleteStopCause != MinimizationStopCause.NONE);
        }

        if (Properties.MINIMIZE_SECOND_PASS && incompleteStopCause == MinimizationStopCause.NONE) {
            removeRedundantTestCases(suite, goals);
        }

        double suiteCoverage = suite.getCoverage();
        logger.info("Setting coverage to: " + suiteCoverage);

        ClientState state = ClientState.MINIMIZATION;
        ClientStateInformation information = new ClientStateInformation(state);
        information.setProgress(100);
        information.setCoverage((int) (Math.round(suiteCoverage * 100)));
        ClientServices.getInstance().getClientNode().changeState(state, information);

        for (TestFitnessFunction goal : goals) {
            if (!covered.contains(goal)) {
                logger.info("Failed to cover: " + goal);
            }
        }
        return statusForStopCause(incompleteStopCause);
    }

    private static void insertMinimizedTest(List<TestChromosome> minimizedTests, TestChromosome candidate) {
        if (Properties.CALL_PROBABILITY <= 0) {
            TestCase candidateTest = candidate.getTestCase();
            for (int i = 0; i < minimizedTests.size(); i++) {
                TestChromosome existing = minimizedTests.get(i);
                TestCase existingTest = existing.getTestCase();
                if (candidateTest.isPrefix(existingTest)) {
                    logger.info("This is a prefix of an existing test");
                    existingTest.addAssertions(candidateTest);
                    return;
                }
                if (existingTest.isPrefix(candidateTest)) {
                    candidateTest.addAssertions(existingTest);
                    minimizedTests.set(i, candidate);
                    logger.info("We have a prefix of this one");
                    return;
                }
            }
        }
        logger.info("Adding new test case:");
        if (logger.isDebugEnabled()) {
            logger.debug(candidate.getTestCase().toCode());
        }
        minimizedTests.add(candidate);
    }

    private boolean isTimeoutReached() {
        return !TimeController.getInstance().isThereStillTimeInThisPhase();
    }

    /**
     * Check whether free heap has dropped below the safety threshold used for
     * post-processing phases.  Mirrors {@code TestSuiteGenerator#isMemoryTooLowForPhase}
     * so the minimizer can self-abort instead of waiting for an OOM to be
     * caught by the caller — by which point the phase budget is typically
     * blown by minutes (GC thrashing makes the cooperative time checks fire
     * far too late).
     *
     * Returns true if memory remains critically low even after a hint GC.
     * Callers should treat this like a timeout and break out of long loops,
     * preserving partial results.
     */
    private boolean isMemoryCritical() {
        Runtime runtime = Runtime.getRuntime();
        long freeMem = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
        long threshold = Properties.MIN_FREE_MEM * 2L;
        if (freeMem >= threshold) {
            return false;
        }
        System.gc();
        freeMem = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
        if (freeMem >= threshold) {
            return false;
        }
        logger.warn("Minimization aborting: free memory {} MB below threshold {} MB",
                freeMem / 1024 / 1024, threshold / 1024 / 1024);
        return true;
    }

    /**
     * Report why minimization should stop, if it should stop. Timeout is
     * checked before memory pressure so an expired phase budget does not spend
     * more time on a hint GC and is reported deterministically as timeout.
     */
    private MinimizationStopCause getStopCause() {
        MinimizationStopCause cause = stopCauseProvider == null
                ? detectStopCause()
                : stopCauseProvider.getStopCause();
        if (cause == null) {
            cause = MinimizationStopCause.NONE;
        }
        if (firstStopCause == MinimizationStopCause.NONE && cause != MinimizationStopCause.NONE) {
            firstStopCause = cause;
        }
        return cause;
    }

    private MinimizationStopCause detectStopCause() {
        if (isTimeoutReached()) {
            return MinimizationStopCause.TIMEOUT;
        }
        if (isMemoryCritical()) {
            return MinimizationStopCause.LOW_MEMORY;
        }
        return MinimizationStopCause.NONE;
    }

    private MinimizationStatus statusForStopCause(MinimizationStopCause stopCause) {
        if (stopCause == MinimizationStopCause.TIMEOUT) {
            return MinimizationStatus.TIMED_OUT;
        }
        if (stopCause == MinimizationStopCause.LOW_MEMORY) {
            return MinimizationStatus.LOW_MEMORY;
        }
        return MinimizationStatus.COMPLETED;
    }

    /**
     * Minimize test suite with respect to the isCovered Method of the goals
     * defined by the supplied TestFitnessFactory.
     *
     * @param suite a {@link org.evosuite.testsuite.TestSuiteChromosome} object.
     */
    private MinimizationStatus minimizeSuite(TestSuiteChromosome suite) {

        // Remove previous results as they do not contain method calls
        // in the case of whole suite generation
        for (TestChromosome test : suite.getTestChromosomes()) {
            test.setChanged(true);
            test.clearCachedResults();
        }

        SecondaryObjective strategy = Properties.SECONDARY_OBJECTIVE[0];

        boolean size = false;
        if (strategy == SecondaryObjective.SIZE) {
            size = true;
            // If we want to remove tests, start with shortest
            suite.tests.sort(comparingInt(TestChromosome::size));
        } else if (strategy == SecondaryObjective.MAX_LENGTH) {
            // If we want to remove the longest test, start with longest
            suite.tests.sort((chromosome1, chromosome2) -> chromosome2.size() - chromosome1.size());
        }

        List<TestFitnessFunction> goals = new ArrayList<>();
        List<Double> fitness = new ArrayList<>();
        for (TestFitnessFactory<?> ff : testFitnessFactories) {
            goals.addAll(ff.getCoverageGoals());
            fitness.add(ff.getFitness(suite));
        }

        boolean changed = true;
        MinimizationStopCause incompleteStopCause = MinimizationStopCause.NONE;
        while (changed && incompleteStopCause == MinimizationStopCause.NONE) {
            changed = false;

            removeEmptyTestCases(suite);

            for (TestChromosome testChromosome : suite.tests) {
                incompleteStopCause = getStopCause();
                if (incompleteStopCause != MinimizationStopCause.NONE) {
                    break;
                }

                for (int i = testChromosome.size() - 1; i >= 0; i--) {
                    incompleteStopCause = getStopCause();
                    if (incompleteStopCause != MinimizationStopCause.NONE) {
                        break;
                    }

                    logger.debug("Current size: " + suite.size() + "/"
                            + suite.totalLengthOfTestCases());
                    logger.debug("Deleting statement "
                            + testChromosome.getTestCase().getStatement(i).getCode()
                            + " from test");
                    TestChromosome originalTestChromosome = testChromosome.clone();

                    boolean modified = false;
                    try {
                        TestFactory testFactory = TestFactory.getInstance();
                        modified = testFactory.deleteStatementGracefully(testChromosome.getTestCase(), i);
                    } catch (ConstructionFailedException e) {
                        modified = false;
                    }

                    if (!modified) {
                        testChromosome.setChanged(false);
                        testChromosome.setTestCase(originalTestChromosome.getTestCase());
                        logger.debug("Deleting failed");
                        continue;
                    }

                    testChromosome.setChanged(true);
                    testChromosome.getTestCase().clearCoveredGoals();

                    List<Double> modifiedVerFitness = new ArrayList<>();
                    for (TestFitnessFactory<?> ff : testFitnessFactories) {
                        modifiedVerFitness.add(ff.getFitness(suite));
                    }

                    boolean worse = false;
                    boolean better = false;
                    for (int fitnessIndex = 0; fitnessIndex < modifiedVerFitness.size(); fitnessIndex++) {
                        TestFitnessFactory<?> ff = testFitnessFactories.get(fitnessIndex);
                        boolean isMaximization = false;
                        if (!ff.getCoverageGoals().isEmpty()) {
                            isMaximization = ff.getCoverageGoals().get(0).isMaximizationFunction();
                        }

                        int comp = Double.compare(modifiedVerFitness.get(fitnessIndex), fitness.get(fitnessIndex));
                        if (comp > 0) { // new > old
                            if (isMaximization) {
                                better = true;
                            } else {
                                worse = true;
                                break; // At least one goal got worse, must reject
                            }
                        } else if (comp < 0) { // new < old
                            if (isMaximization) {
                                worse = true;
                                break; 
                            } else {
                                better = true; 
                            }
                        }
                    }

                    if (worse) {
                        // Reject: restore test case
                        logger.debug("Can't remove statement "
                                + originalTestChromosome.getTestCase().getStatement(i).getCode());
                        logger.debug("Restoring fitness from " + modifiedVerFitness
                                + " to " + fitness);
                        testChromosome.setTestCase(originalTestChromosome.getTestCase());
                        testChromosome.setLastExecutionResult(originalTestChromosome.getLastExecutionResult());
                        testChromosome.setChanged(false);
                    } else {
                        // Accept: no goals got worse
                        if (better) {
                            fitness = modifiedVerFitness;
                        }
                        changed = true;
                    }
                }
            }
        }

        this.removeEmptyTestCases(suite);
        if (incompleteStopCause == MinimizationStopCause.NONE) {
            this.removeRedundantTestCases(suite, goals);
        }
        return statusForStopCause(incompleteStopCause);
    }

    private void removeEmptyTestCases(TestSuiteChromosome suite) {
        Iterator<TestChromosome> it = suite.tests.iterator();
        while (it.hasNext()) {
            TestChromosome test = it.next();
            if (test.size() == 0) {
                logger.debug("Removing empty test case");
                it.remove();
            }
        }
    }

    private ExecutionResult runPreflight(TestCase test) {
        if (preflightExecutor != null) {
            return preflightExecutor.run(test);
        }
        return TestCaseExecutor.runTest(test);
    }

    private void removeRedundantTestCases(TestSuiteChromosome suite, List<TestFitnessFunction> goals) {
        // Subsuming tests are inserted in the back, so we start inserting the final tests from there
        List<TestChromosome> tests = suite.getTestChromosomes();
        logger.debug("Before removing redundant tests: " + tests.size());
        int before = tests.size();

        Collections.reverse(tests);
        List<TestChromosome> finalTests = new ArrayList<>();
        Set<TestFitnessFunction> coveredGoals = new LinkedHashSet<>();

        for (TestChromosome test : tests) {
            boolean addsNewGoals = false;
            for (TestFitnessFunction goal : goals) {
                if (!coveredGoals.contains(goal)) {
                    if (goal.isCovered(test)) {
                        addsNewGoals = true;
                        coveredGoals.add(goal);
                    }
                }
            }

            if (addsNewGoals) {
                coveredGoals.addAll(test.getTestCase().getCoveredGoals());
                finalTests.add(test);
            }
        }
        Collections.reverse(finalTests);
        suite.getTestChromosomes().clear();
        suite.getTestChromosomes().addAll(finalTests);
        logger.debug("After removing redundant tests: " + tests.size());
        if (before > 0 && finalTests.isEmpty()) {
            logger.warn("Second-pass redundancy removal dropped all tests ({} -> 0). "
                            + "Covered goals retained in pass: {}",
                    before, coveredGoals.size());
        }

    }

    private String summarizeExecutionResult(ExecutionResult result) {
        if (result == null) {
            return "<null>";
        }
        Integer firstPos = result.getFirstPositionOfThrownException();
        String firstException = "<none>";
        if (firstPos != null) {
            Throwable thrown = result.getExceptionThrownAtPosition(firstPos);
            if (thrown != null) {
                firstException = thrown.getClass().getName() + ": " + thrown.getMessage();
            }
        }
        return "timeout=" + result.hasTimeout()
                + ", testException=" + result.hasTestException()
                + ", securityException=" + result.hasSecurityException()
                + ", thrownExceptions=" + result.getNumberOfThrownExceptions()
                + ", firstException=" + firstException
                + ", executedStatements=" + result.getExecutedStatements();
    }
}
