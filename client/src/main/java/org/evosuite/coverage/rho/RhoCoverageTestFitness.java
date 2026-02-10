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
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class RhoCoverageTestFitness extends TestFitnessFunction {

    private static final long serialVersionUID = -1483213330289592274L;

    private static final Logger logger = LoggerFactory.getLogger(RhoCoverageTestFitness.class);

    private int previousNumberOfOnes = 0;
    private int previousNumberOfTestCases = 0;

    private final Set<Set<Integer>> coverageMatrixGeneratedSoFar = new LinkedHashSet<>();

    @Override
    public double getFitness(TestChromosome individual, ExecutionResult result) {

        Set<Set<Integer>> tmpCoverageMatrix = new LinkedHashSet<>(this.coverageMatrixGeneratedSoFar);

        double fitness = 1.0;

        int numberOfGoals = RhoCoverageFactory.getNumberGoals();
        int numberOfOnes = RhoCoverageFactory.getNumberOfOnes() + this.previousNumberOfOnes;
        int numberOfTestCases = RhoCoverageFactory.getNumberOfTestCases() + this.previousNumberOfTestCases;

        Set<Integer> coveredLines = result.getTrace().getCoveredLines();

        if (Properties.STRATEGY == Properties.Strategy.ENTBUG) {
            // order set
            List<Integer> coveredLinesList = new ArrayList<>(coveredLines);
            Collections.sort(coveredLinesList);
            Set<Integer> coveredLinesOrdered = new LinkedHashSet<>(coveredLinesList);

            // no coverage
            if (coveredLinesOrdered.isEmpty()) {
                updateIndividual(individual, 1.0);
                return 1.0;
            } else if (!tmpCoverageMatrix.add(coveredLinesOrdered)) {
                // already exists locally
                updateIndividual(individual, 1.0);
                return 1.0;
            } else if (RhoCoverageFactory.exists(coveredLinesList)) {
                // already exists on the original test suite
                updateIndividual(individual, 1.0);
                return 1.0;
            } else {
                // good
                numberOfOnes += coveredLinesOrdered.size();
                numberOfTestCases++;
            }
        } else {
            numberOfOnes += coveredLines.size();
            numberOfTestCases++;
        }

        fitness = RhoAux.calculateRho(numberOfOnes, numberOfTestCases, numberOfGoals);

        updateIndividual(individual, fitness);
        return fitness;
    }

    @Override
    public int compareTo(TestFitnessFunction other) {
        return compareClassName(other);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result
                + ((coverageMatrixGeneratedSoFar == null) ? 0 : coverageMatrixGeneratedSoFar.hashCode());
        result = prime * result + previousNumberOfOnes;
        result = prime * result + previousNumberOfTestCases;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        RhoCoverageTestFitness other = (RhoCoverageTestFitness) obj;
        if (coverageMatrixGeneratedSoFar == null) {
            if (other.coverageMatrixGeneratedSoFar != null) {
                return false;
            }
        } else if (!coverageMatrixGeneratedSoFar.equals(other.coverageMatrixGeneratedSoFar)) {
            return false;
        }
        if (previousNumberOfOnes != other.previousNumberOfOnes) {
            return false;
        }
        return previousNumberOfTestCases == other.previousNumberOfTestCases;
    }

    @Override
    public String getTargetClass() {
        return null;
    }

    @Override
    public String getTargetMethod() {
        return null;
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
