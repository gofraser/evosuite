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
package org.evosuite.ga.archive;

import org.evosuite.Properties;
import org.evosuite.coverage.exception.ExceptionCoverageTestFitness;
import org.evosuite.coverage.exception.TryCatchCoverageTestFitness;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.rmi.ClientServices;
import org.evosuite.runtime.util.AtMostOnceLogger;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Coverage Archive.
 *
 * @author José Campos
 */
public class CoverageArchive extends Archive {

    private static final long serialVersionUID = -4046845573050661961L;

    private static final Logger logger = LoggerFactory.getLogger(CoverageArchive.class);

    /**
     * Map used to store all covered targets (keys of the map) and the corresponding covering
     * solutions (values of the map).
     */
    private final Map<TestFitnessFunction, TestChromosome> covered = new LinkedHashMap<>();

    /**
     * Set used to store all targets that have not been covered yet.
     */
    private final Set<TestFitnessFunction> uncovered = new LinkedHashSet<>();

    /**
     * Running total of archive add operations aborted because cloning the
     * candidate solution threw. Exported as
     * {@link RuntimeVariable#Archive_Clone_Failures}.
     */
    private transient long cloneFailureTotal;

    public static final CoverageArchive instance = new CoverageArchive();


    /**
     * {@inheritDoc}
     */
    @Override
    public void addTarget(TestFitnessFunction target) {
        super.addTarget(target);

        if (!this.uncovered.contains(target)) {
            logger.debug("Registering new target '" + target + "'");
            this.uncovered.add(target);
        }

        this.registerNonCoveredTargetOfAMethod(target);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateArchive(TestFitnessFunction target, TestChromosome solution, double fitnessValue) {
        super.updateArchive(target, solution, fitnessValue);
        assert this.covered.containsKey(target) || this.uncovered.contains(target) : "Unknown goal: " + target;

        if (fitnessValue > 0.0) {
            // as this type of archive only cares about covered targets, it ignores all
            // targets with a fitness value greater than 0.0
            return;
        }

        // Do not allow non-exception targets to be considered covered by tests
        // that terminate with code-under-test exceptions. Those tests are filtered
        // later in post-processing and would otherwise create empty final suites
        // with misleading archive coverage.
        if (shouldRejectExceptionThrowingCandidate(target, solution)) {
            logger.debug("Ignoring candidate for target {} due to CUT exception in execution result", target);
            return;
        }

        boolean isNewCoveredTarget = false;
        boolean isNewSolutionBetterThanCurrent = false;

        TestChromosome currentSolution = this.covered.get(target);

        if (currentSolution == null) {
            logger.debug("Solution for non-covered target '" + target + "'");
            isNewCoveredTarget = true;
        } else {
            isNewSolutionBetterThanCurrent = Archive.isBetterThanCurrent(currentSolution, solution);
        }

        if (isNewCoveredTarget || isNewSolutionBetterThanCurrent) {
            // update the archive if a new target has been covered, or if solution covers already existing
            // covered targets but it has been considered a better solution
            this.addToArchive(target, solution);
        }
    }

    private boolean shouldRejectExceptionThrowingCandidate(TestFitnessFunction target, TestChromosome solution) {
        if (target instanceof ExceptionCoverageTestFitness || target instanceof TryCatchCoverageTestFitness) {
            return false;
        }
        ExecutionResult result = solution.getLastExecutionResult();
        return result != null && result.hasTestException();
    }

    private void addToArchive(TestFitnessFunction target, TestChromosome solution) {
        logger.debug("Adding test case for goal {}: {}", target, solution);
        TestChromosome clone;
        try {
            clone = solution.clone();
        } catch (AssertionError | RuntimeException e) {
            // Defense-in-depth: a malformed chromosome (e.g., orphaned
            // VariableReference) that slipped past the offspring tripwire
            // would otherwise tear down the entire search inside clone().
            // Drop this archive update; the search continues without the
            // candidate. Non-zero Archive_Clone_Failures indicates a leak
            // worth investigating from the offspring tripwire's WARN logs.
            cloneFailureTotal++;
            try {
                ClientServices.track(RuntimeVariable.Archive_Clone_Failures, cloneFailureTotal);
            } catch (Throwable ignored) {
                // best-effort tracking
            }
            String code;
            try {
                code = solution.getTestCase().toCode();
            } catch (Throwable t) {
                code = "<toCode failed: " + t.getClass().getSimpleName() + ">";
            }
            AtMostOnceLogger.warn(logger,
                    "Archive add aborted for goal " + target + " (clone failed: "
                            + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage())
                            + "). Solution code:\n" + code);
            return;
        }
        this.uncovered.remove(target);
        this.covered.put(target, clone);
        this.removeNonCoveredTargetOfAMethod(target);
        this.hasBeenUpdated = true;

        ExecutionResult result = solution.getLastExecutionResult();
        if (result != null) {
            Map<Integer, Throwable> exceptionSnapshot = result.getCopyOfExceptionMapping();
            int testSize = result.test == null ? -1 : result.test.size();
            boolean snapshotHasTimeout = testSize >= 0
                    && exceptionSnapshot.containsKey(testSize)
                    && ExecutionResult.isTimeoutLike(exceptionSnapshot.get(testSize));
            boolean snapshotHasTestException = exceptionSnapshot.values().stream()
                    .anyMatch(ExecutionResult::isCodeUnderTestLike);

            // Use one immutable snapshot for the whole diagnostic branch to avoid
            // mixed-state logging when result internals are observed multiple times.
            if (!(snapshotHasTimeout || snapshotHasTestException)) {
                return;
            }

            AtMostOnceLogger.warn(logger,
                    "A solution with a timeout/exception result has been added to the archive. The covered goal was "
                            + target.toString());

            logger.info("ExecutionResult snapshot: snapshotHasTimeout=" + snapshotHasTimeout
                    + ", snapshotHasTestException=" + snapshotHasTestException
                    + ", exceptionCount=" + exceptionSnapshot.size()
                    + ", executedStatements=" + result.getExecutedStatements()
                    + ", executionTimeMs=" + result.getExecutionTime()
                    + ", testSize=" + testSize
                    + ", resultIdentity=" + System.identityHashCode(result));

            if (exceptionSnapshot.isEmpty()) {
                logger.info("ExecutionResult flagged timeout/exception but has no recorded thrown exceptions.");
            } else {
                int index = 0;
                for (Map.Entry<Integer, Throwable> entry : exceptionSnapshot.entrySet()) {
                    Throwable thrown = entry.getValue();
                    Integer position = entry.getKey();
                    if (thrown == null) {
                        logger.info("Recorded thrown exception[{}] at position {}: <null>", index, position);
                        index++;
                        continue;
                    }
                    logger.info("Recorded thrown exception[{}] at position {}: {}: {}", index, position,
                            thrown.getClass().getName(), thrown.getMessage());
                    boolean atTerminalPosition = testSize >= 0 && position != null && position == testSize;
                    boolean isTimeoutExceeded = ExecutionResult.isTimeoutLike(thrown);
                    boolean isCodeUnderTestException = ExecutionResult.isCodeUnderTestLike(thrown);
                    String causeType = thrown.getCause() == null ? "<null>" : thrown.getCause().getClass().getName();
                    logger.info("  flags: atTerminalPosition=" + atTerminalPosition
                            + ", isTimeoutExceeded=" + isTimeoutExceeded
                            + ", isCodeUnderTestException=" + isCodeUnderTestException
                            + ", causeType=" + causeType);
                    logger.info("  dbg_v4: positionRaw=" + position
                            + ", testSizeRaw=" + testSize
                            + ", classRaw=" + thrown.getClass().getName()
                            + ", timeoutDirect="
                            + (thrown instanceof org.evosuite.testcase.execution.TestCaseExecutor.TimeoutExceeded)
                            + ", codeUnderDirect="
                            + (thrown instanceof org.evosuite.testcase.execution.CodeUnderTestException)
                            + ", timeoutByName="
                            + org.evosuite.testcase.execution.TestCaseExecutor.TimeoutExceeded.class.getName()
                            .equals(thrown.getClass().getName())
                            + ", codeUnderByName="
                            + org.evosuite.testcase.execution.CodeUnderTestException.class.getName()
                            .equals(thrown.getClass().getName()));
                    for (StackTraceElement elem : thrown.getStackTrace()) {
                        logger.info("  at {}", elem);
                    }
                    index++;
                }
            }
            logger.info(solution.toString());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isArchiveEmpty() {
        return this.covered.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfTargets() {
        return this.covered.keySet().size() + this.uncovered.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfCoveredTargets() {
        return this.covered.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfCoveredTargets(Class<?> targetClass) {
        return (int) this.covered.keySet().stream().filter(target -> target.getClass() == targetClass)
                .count();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<TestFitnessFunction> getCoveredTargets() {
        return this.covered.keySet();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfUncoveredTargets() {
        return this.uncovered.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfUncoveredTargets(Class<?> targetClass) {
        return (int) this.uncovered.stream().filter(target -> target.getClass() == targetClass).count();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<TestFitnessFunction> getUncoveredTargets() {
        return new LinkedHashSet<>(this.uncovered);
    }

    private Set<TestFitnessFunction> getTargets() {
        Set<TestFitnessFunction> targets = new LinkedHashSet<>();
        targets.addAll(this.getCoveredTargets());
        targets.addAll(this.getUncoveredTargets());
        return targets;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasTarget(TestFitnessFunction target) {
        assert target != null;
        return this.covered.containsKey(target) || this.uncovered.contains(target);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfSolutions() {
        return this.covered.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<TestChromosome> getSolutions() {
        return new LinkedHashSet<>(this.covered.values());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TestChromosome getSolution() {
        return this.getRandomSolution();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TestChromosome getSolution(TestFitnessFunction target) {
        assert target != null;
        assert this.covered.containsKey(target);
        return this.covered.get(target);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasSolution(TestFitnessFunction target) {
        assert target != null;
        return this.covered.containsKey(target);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TestChromosome getRandomSolution() {
        // TODO this gives higher probability to tests that cover more targets. Maybe it is not the best
        // way, but likely the quickest to compute. A proper way to do it would be to first call
        // 'getSolutions' and only then select one at random.
        TestChromosome randomChoice = Randomness.choice(this.getSolutions());
        if (randomChoice == null) {
            return null;
        }
        return randomChoice.clone();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected TestSuiteChromosome createMergedSolution(TestSuiteChromosome solution) {
        // Deactivate in case a test is executed and would access the archive as this might cause a
        // concurrent access
        Properties.TEST_ARCHIVE = false;

        TestSuiteChromosome mergedSolution = solution.clone();

        // Skip solutions that have been modified as those might not have been evaluated yet, or
        // have thrown some exception and therefore they may slow down future analysis on the
        // final test suite.  Timed-out tests are kept: the JUnit writer wraps them in an
        // ExecutorService with future.get(timeout) so they won't hang.
        mergedSolution.getTestChromosomes()
                .removeIf(t -> t.isChanged()
                        || (t.getLastExecutionResult() != null
                        && t.getLastExecutionResult().hasTestException()));

        // to avoid adding the same solution to 'mergedSolution' suite
        Set<TestChromosome> solutionsSampledFromArchive = new LinkedHashSet<>();

        for (TestFitnessFunction target : this.getTargets()) {
            // has target been covered? to answer it, we perform a local check rather than calling method
            // {@link TestFitnessFunction.isCoveredBy} as it may perform a fitness evaluation to access
            // whether that 'target' is covered or not (and therefore, it could be more expensive)
            boolean isGoalCovered = false;
            for (TestChromosome test : mergedSolution.getTestChromosomes()) {
                if (test.getTestCase().isGoalCovered(target)) {
                    isGoalCovered = true;
                    break;
                }
            }

            if (!isGoalCovered) {
                TestChromosome chromosome = this.covered.get(target);

                // is there any solution in the archive that covers it, and has that solution not been
                // considered yet?
                if (chromosome != null && !solutionsSampledFromArchive.contains(chromosome)) {
                    solutionsSampledFromArchive.add(chromosome);
                    mergedSolution.addTest(chromosome);
                }
            }
        }

        // re-evaluate merged solution
        for (FitnessFunction<TestSuiteChromosome> ff : solution.getFitnessValues().keySet()) {
            ff.getFitness(mergedSolution);
        }

        // re-active it
        Properties.TEST_ARCHIVE = true;

        return mergedSolution;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void shrinkSolutions(int size) {
        // NO-OP
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "NumTargets: " + this.getNumberOfTargets() + ", NumCoveredTargets: "
                + this.getNumberOfCoveredTargets() + ", NumSolutions: " + this.getNumberOfSolutions();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset() {
        super.reset();
        this.covered.clear();
        this.uncovered.clear();
    }
}
