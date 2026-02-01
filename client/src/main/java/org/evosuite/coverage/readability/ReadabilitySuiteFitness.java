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
package org.evosuite.coverage.readability;

import org.evosuite.testcase.TestChromosome;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;

/**
 * Fitness function that measures the readability of a test suite.
 * Currently, it uses a simple metric based on the size of the test case
 * and the number of assertions.
 */
public class ReadabilitySuiteFitness extends TestSuiteFitnessFunction {


    private static final long serialVersionUID = 6243235746473531638L;


    @Override
    public double getFitness(TestSuiteChromosome suite) {
        double average = 0.0;

        for (TestChromosome ec : suite.getTestChromosomes()) {
            average += getScore(ec);
        }

        if (!suite.getTestChromosomes().isEmpty()) {
            average /= suite.getTestChromosomes().size();
        }

        updateIndividual(suite, average);
        return average;
    }


    /**
     * Calculate the readability score of a test chromosome.
     * Lower is better (more readable).
     *
     * @param test The test chromosome to evaluate
     * @return The readability score (size + assertions)
     */
    public double getScore(TestChromosome test) {
        if (test == null || test.getTestCase() == null) {
            return 0.0;
        }
        // Size (number of statements) + number of assertions
        return test.getTestCase().size() + test.getTestCase().getAssertions().size();
    }


    @Override
    public boolean isMaximizationFunction() {
        return false;
    }
}
