/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 * <p>
 * This file is part of EvoSuite.
 * <p>
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 * <p>
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 * <p>
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.ga.metaheuristics.mapelites;

import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;

/**
 * Wrapper for fitness functions.
 */
public class FitnessFunctionWrapper {
    /**
     * Counter for Feedback-Directed Sampling.
     *
     * @see <a href="https://arxiv.org/pdf/1901.01541.pdf">Reference Paper</a>
     */
    private final Counter counter;
    private final TestFitnessFunction fitnessFunction;

    /**
     * Constructor.
     *
     * @param fitnessFunction the fitness function
     */
    public FitnessFunctionWrapper(TestFitnessFunction fitnessFunction) {
        super();
        this.fitnessFunction = fitnessFunction;
        this.counter = new Counter();
    }

    /**
     * Gets the fitness of an individual.
     *
     * @param individual the individual
     * @return the fitness
     */
    public double getFitness(TestChromosome individual) {
        return this.fitnessFunction.getFitness(individual);
    }

    /**
     * Checks if the individual covers the target.
     *
     * @param individual the individual
     * @return true if covered
     */
    public boolean isCovered(TestChromosome individual) {
        return this.fitnessFunction.isCovered(individual);
    }

    /**
     * Gets the counter.
     *
     * @return the counter
     */
    public Counter getCounter() {
        return this.counter;
    }
}
