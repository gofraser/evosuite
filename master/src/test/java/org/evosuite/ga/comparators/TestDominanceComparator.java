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
package org.evosuite.ga.comparators;

import java.util.ArrayList;
import java.util.List;

import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.NSGAChromosome;
import org.evosuite.ga.problems.Problem;
import org.evosuite.ga.problems.multiobjective.FON;
import org.evosuite.ga.problems.singleobjective.Booths;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author José Campos
 */
public class TestDominanceComparator {

    /**
     * A maximization fitness function for testing.
     */
    @SuppressWarnings("serial")
    private static class MaximizingFitnessFunction extends FitnessFunction<NSGAChromosome> {
        @Override
        public double getFitness(NSGAChromosome c) {
            return 0;
        }

        @Override
        public boolean isMaximizationFunction() {
            return true;
        }
    }

    @Test
    public void testDominanceComparatorMaximizationFunction() {
        FitnessFunction<NSGAChromosome> ff = new MaximizingFitnessFunction();

        NSGAChromosome c1 = new NSGAChromosome();
        NSGAChromosome c2 = new NSGAChromosome();

        // Set Fitness: c1 has higher fitness, which is better for maximization
        c1.setFitness(ff, 0.7);
        c2.setFitness(ff, 0.3);

        List<NSGAChromosome> population = new ArrayList<>();
        population.add(c1);
        population.add(c2);

        // After sorting, c1 (higher fitness) should come first for maximization
        population.sort(new DominanceComparator<>());

        Assert.assertEquals(0.7, population.get(0).getFitness(ff), 0.0);
        Assert.assertEquals(0.3, population.get(1).getFitness(ff), 0.0);
    }

    @Test
    public void testDominanceComparatorMixedMinMax() {
        // Test with one minimization and one maximization function
        Problem<NSGAChromosome> p = new FON();
        List<FitnessFunction<NSGAChromosome>> fitnessFunctions = p.getFitnessFunctions();
        FitnessFunction<NSGAChromosome> ff_min = fitnessFunctions.get(0); // minimization
        FitnessFunction<NSGAChromosome> ff_max = new MaximizingFitnessFunction(); // maximization

        NSGAChromosome c1 = new NSGAChromosome();
        NSGAChromosome c2 = new NSGAChromosome();

        // c1: low on minimization (good), high on maximization (good) - should dominate
        c1.setFitness(ff_min, 0.2);
        c1.setFitness(ff_max, 0.8);
        // c2: high on minimization (bad), low on maximization (bad) - should be dominated
        c2.setFitness(ff_min, 0.5);
        c2.setFitness(ff_max, 0.3);

        List<NSGAChromosome> population = new ArrayList<>();
        population.add(c2);
        population.add(c1);

        // After sorting, c1 should come first as it dominates c2
        population.sort(new DominanceComparator<>());

        Assert.assertEquals(0.2, population.get(0).getFitness(ff_min), 0.0);
        Assert.assertEquals(0.8, population.get(0).getFitness(ff_max), 0.0);
    }

    @Test
    public void testDominanceComparatorOneFitness() {
        Problem<NSGAChromosome> p = new Booths();
        List<FitnessFunction<NSGAChromosome>> fitnessFunctions = p.getFitnessFunctions();
        FitnessFunction<NSGAChromosome> ff = fitnessFunctions.get(0);

        NSGAChromosome c1 = new NSGAChromosome();
        NSGAChromosome c2 = new NSGAChromosome();

        // Set Fitness
        c1.setFitness(ff, 0.7);
        c2.setFitness(ff, 0.3);

        List<NSGAChromosome> population = new ArrayList<>();
        population.add(c1);
        population.add(c2);

        population.sort(new DominanceComparator<>());

        Assert.assertEquals(0.3, population.get(0).getFitness(ff), 0.0);
        Assert.assertEquals(0.7, population.get(1).getFitness(ff), 0.0);
    }

    @Test
    public void testDominanceComparatorSeveralFitnessesNoDomination() {
        Problem<NSGAChromosome> p = new FON();
        List<FitnessFunction<NSGAChromosome>> fitnessFunctions = p.getFitnessFunctions();
        FitnessFunction<NSGAChromosome> ff_1 = fitnessFunctions.get(0);
        FitnessFunction<NSGAChromosome> ff_2 = fitnessFunctions.get(1);

        NSGAChromosome c1 = new NSGAChromosome();
        NSGAChromosome c2 = new NSGAChromosome();

        // Set Fitness
        c1.setFitness(ff_1, 0.7);
        c1.setFitness(ff_2, 0.2);
        c2.setFitness(ff_1, 0.3);
        c2.setFitness(ff_2, 0.5);

        List<NSGAChromosome> population = new ArrayList<>();
        population.add(c1);
        population.add(c2);

        population.sort(new DominanceComparator<>());

        Assert.assertEquals(0.7, population.get(0).getFitness(ff_1), 0.0);
        Assert.assertEquals(0.2, population.get(0).getFitness(ff_2), 0.0);
        Assert.assertEquals(0.3, population.get(1).getFitness(ff_1), 0.0);
        Assert.assertEquals(0.5, population.get(1).getFitness(ff_2), 0.0);
    }

    @Test
    public void testDominanceComparatorSeveralFitnessesDomination() {
        Problem<NSGAChromosome> p = new FON();
        List<FitnessFunction<NSGAChromosome>> fitnessFunctions = p.getFitnessFunctions();
        FitnessFunction<NSGAChromosome> ff_1 = fitnessFunctions.get(0);
        FitnessFunction<NSGAChromosome> ff_2 = fitnessFunctions.get(1);

        NSGAChromosome c1 = new NSGAChromosome();
        NSGAChromosome c2 = new NSGAChromosome();

        // Set Fitness
        c1.setFitness(ff_1, 0.7);
        c1.setFitness(ff_2, 0.6);
        c2.setFitness(ff_1, 0.3);
        c2.setFitness(ff_2, 0.5);

        List<NSGAChromosome> population = new ArrayList<>();
        population.add(c1);
        population.add(c2);

        population.sort(new DominanceComparator<>());

        Assert.assertEquals(0.3, population.get(0).getFitness(ff_1), 0.0);
        Assert.assertEquals(0.5, population.get(0).getFitness(ff_2), 0.0);
        Assert.assertEquals(0.7, population.get(1).getFitness(ff_1), 0.0);
        Assert.assertEquals(0.6, population.get(1).getFitness(ff_2), 0.0);
    }
}
