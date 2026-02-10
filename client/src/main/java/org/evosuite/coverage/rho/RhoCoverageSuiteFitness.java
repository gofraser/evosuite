/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.coverage.rho;

import org.evosuite.Properties;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;

import java.util.*;

/**
 * Fitness function for a test suite using Rho coverage.
 *
 * @author José Campos
 */
public class RhoCoverageSuiteFitness extends TestSuiteFitnessFunction {

    private static final long serialVersionUID = 5460600509431741746L;

    private int previousNumberOfOnes = 0;
    private int previousNumberOfTestCases = 0;

    private final Set<Set<Integer>> coverageMatrixGeneratedSoFar = new LinkedHashSet<>();

    @Override
    public double getFitness(TestSuiteChromosome suite) {
        return this.getFitness(suite, true);
    }

    protected double getFitness(TestSuiteChromosome suite, boolean updateFitness) {

        Set<Set<Integer>> tmpCoverageMatrix = new LinkedHashSet<>(this.coverageMatrixGeneratedSoFar);

        double fitness;

        int numberOfGoals = RhoCoverageFactory.getNumberGoals();
        int numberOfOnes = RhoCoverageFactory.getNumberOfOnes() + this.previousNumberOfOnes;
        int numberOfTestCases = RhoCoverageFactory.getNumberOfTestCases() + this.previousNumberOfTestCases;

        List<ExecutionResult> results = runTestSuite(suite);
        for (ExecutionResult result : results) {

            // Execute test cases and collect the covered lines
            Set<Integer> coveredLines = result.getTrace().getCoveredLines();

            if (Properties.STRATEGY == Properties.Strategy.ENTBUG) {
                // order set
                List<Integer> coveredLinesList = new ArrayList<>(coveredLines);
                Collections.sort(coveredLinesList);
                Set<Integer> coveredLinesOrdered = new LinkedHashSet<>(coveredLinesList);

                // there is coverage, and it is new (not in local matrix), and not in original matrix
                if (!coveredLinesOrdered.isEmpty()
                        && tmpCoverageMatrix.add(coveredLinesOrdered)
                        && !RhoCoverageFactory.exists(coveredLinesList)) {
                    numberOfOnes += coveredLinesOrdered.size();
                    numberOfTestCases++;
                }
            } else {
                numberOfOnes += coveredLines.size();
                numberOfTestCases++;
            }
        }

        fitness = RhoAux.calculateRho(numberOfOnes, numberOfTestCases, numberOfGoals);

        if (updateFitness) {
            updateIndividual(suite, fitness);
        }
        return fitness;
    }

    public void incrementNumberOfOnes(int n) {
        this.previousNumberOfOnes += n;
    }

    public int getNumberOfOnes() {
        return this.previousNumberOfOnes;
    }

    public void incrementNumberOfTestCases() {
        this.previousNumberOfTestCases++;
    }

    public int getNumberOfTestCases() {
        return this.previousNumberOfTestCases;
    }

    public void addTestCoverage(Set<Integer> testCoverage) {
        this.coverageMatrixGeneratedSoFar.add(testCoverage);
    }
}
