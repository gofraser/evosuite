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
package org.evosuite.coverage.io;

import org.evosuite.Properties;
import org.evosuite.ga.archive.Archive;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionObserver;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testsuite.AbstractFitnessFactory;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;

import java.util.*;
import java.util.function.Supplier;

/**
 * Fitness function for evaluating test suite coverage of the values of a method, either its
 * arguments or its return value.
 * Computes fitness based on how well the test suite covers various value categories.
 *
 * @param <F> the type of the test fitness of a single goal
 * @author Jose Miguel Rojas
 */
public abstract class IOCoverageSuiteFitness<F extends IOCoverageTestFitness<?>> extends TestSuiteFitnessFunction {

    private static final long serialVersionUID = 4208651972316395847L;

    private final String name;
    private final int totalGoals;
    private final Set<F> goals = new LinkedHashSet<>();

    private final Set<F> toRemoveGoals = new LinkedHashSet<>();
    private final Set<F> removedGoals = new LinkedHashSet<>();

    // Some stuff for debug output
    private int maxCoveredGoals = 0;
    private double bestFitness = Double.MAX_VALUE;

    /**
     * Initializes a new suite fitness, adding the observer to the executor.
     *
     * @param name            the name of the criterion, such as {@code Input}
     * @param observerClass   the observer that records the values the goals are computed from
     * @param observerFactory creates the observer if the executor does not have one yet
     * @param factory         creates the goals
     */
    protected IOCoverageSuiteFitness(String name, Class<? extends ExecutionObserver> observerClass,
                                     Supplier<? extends ExecutionObserver> observerFactory,
                                     AbstractFitnessFactory<F> factory) {
        this.name = name;
        IOCoverageTestFitness.ensureObserver(observerClass, observerFactory);

        determineCoverageGoals(factory);

        totalGoals = goals.size();
    }

    /**
     * Initialize the set of known coverage goals.
     */
    private void determineCoverageGoals(AbstractFitnessFactory<F> factory) {
        for (F goal : factory.getCoverageGoals()) {
            goals.add(goal);
            if (Properties.TEST_ARCHIVE) {
                Archive.getArchiveInstance().addTarget(goal);
            }

        }
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Execute all tests and count covered goals.
     */
    @Override
    public double getFitness(TestSuiteChromosome suite) {
        logger.trace("Calculating test suite fitness");
        double fitness = 0.0;

        List<ExecutionResult> results = runTestSuite(suite);

        boolean hasTimeoutOrTestException = false;
        for (ExecutionResult result : results) {
            if (result.hasTimeout() || result.hasTestException()) {
                hasTimeoutOrTestException = true;
                break;
            }
        }

        Set<TestFitnessFunction> setOfCoveredGoals = new LinkedHashSet<>();
        if (hasTimeoutOrTestException) {
            logger.info("Test suite has timed out, setting fitness to max value " + totalGoals);
            fitness = totalGoals;
        } else {
            fitness = computeDistance(results, setOfCoveredGoals);
        }

        int coveredGoals = setOfCoveredGoals.size() + removedGoals.size();

        if (totalGoals > 0) {
            suite.setCoverage(this, (double) coveredGoals / (double) totalGoals);
        } else {
            suite.setCoverage(this, 1.0);
        }

        suite.setNumOfCoveredGoals(this, coveredGoals);

        printStatusMessages(suite, coveredGoals, fitness);
        updateIndividual(suite, fitness);

        assert (coveredGoals <= totalGoals) : "Covered " + coveredGoals + " vs total goals " + totalGoals;
        assert (fitness >= 0.0);
        assert (fitness != 0.0 || coveredGoals == totalGoals) : "Fitness: " + fitness + ", "
                + "coverage: " + coveredGoals + "/" + totalGoals;
        assert (suite.getCoverage(this) <= 1.0) && (suite.getCoverage(this) >= 0.0) : "Wrong coverage value "
                + suite.getCoverage(this);

        return fitness;
    }

    @Override
    public boolean updateCoveredGoals() {
        if (!Properties.TEST_ARCHIVE) {
            return false;
        }

        for (F goal : this.toRemoveGoals) {
            if (this.goals.remove(goal)) {
                this.removedGoals.add(goal);
            } else {
                throw new IllegalStateException("goal to remove not found");
            }
        }

        this.toRemoveGoals.clear();
        logger.info("Current state of archive: " + Archive.getArchiveInstance().toString());

        return true;
    }

    private double computeDistance(List<ExecutionResult> results, Set<TestFitnessFunction> setOfCoveredGoals) {

        Map<F, Double> mapDistances = new LinkedHashMap<>();
        for (F testFitness : this.goals) {
            mapDistances.put(testFitness, Double.MAX_VALUE);
        }

        for (ExecutionResult result : results) {
            if (result.hasTimeout() || result.hasTestException()) {
                continue;
            }

            TestChromosome test = new TestChromosome();
            test.setTestCase(result.test);
            test.setLastExecutionResult(result);
            test.setChanged(false);

            for (final F testFitness : this.goals) {
                if (!mapDistances.containsKey(testFitness)) {
                    continue;
                }

                // archive is updated by the TestFitnessFunction class
                double distance = testFitness.getFitness(test, result);

                mapDistances.put(testFitness, Math.min(distance, mapDistances.get(testFitness)));

                if (distance == 0.0) {
                    mapDistances.remove(testFitness);
                    setOfCoveredGoals.add(testFitness); // helper to count the number of covered goals
                    // goal to not be considered by next EA iteration
                    this.toRemoveGoals.add(testFitness);
                }
            }
        }

        return mapDistances.values().stream().mapToDouble(TestFitnessFunction::normalize).sum();
    }

    /**
     * Prints useful debug information about coverage status.
     *
     * @param suite        the test suite being evaluated
     * @param coveredGoals the number of covered goals
     * @param fitness      the current fitness value
     */
    private void printStatusMessages(TestSuiteChromosome suite, int coveredGoals, double fitness) {
        if (coveredGoals > maxCoveredGoals) {
            logger.info("(" + name + " Goals) Best individual covers " + coveredGoals + "/"
                    + totalGoals + " " + name.toLowerCase() + " goals");
            maxCoveredGoals = coveredGoals;
            logger.info("Fitness: " + fitness + ", size: " + suite.size() + ", length: "
                    + suite.totalLengthOfTestCases());

        }
        if (fitness < bestFitness) {
            logger.info("(Fitness) Best individual covers " + coveredGoals + "/"
                    + totalGoals + " " + name.toLowerCase() + " goals");
            bestFitness = fitness;
            logger.info("Fitness: " + fitness + ", size: " + suite.size() + ", length: "
                    + suite.totalLengthOfTestCases());

        }
    }

}
