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
package org.evosuite.ga.metaheuristics;

import org.evosuite.Properties;
import org.evosuite.ga.Chromosome;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.localsearch.LocalSearchObjective;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class StandardChemicalReactionTest {

    @Before
    public void setUp() {
        Properties.NEW_STATISTICS = false;
    }

    @After
    public void tearDown() {
        Properties.getInstance().resetToDefaults();
    }

    @Test
    public void testBufferCrashWhenLocalSearchWorsensFitness() {
        // Setup
        Properties.LOCAL_SEARCH_RATE = 1;
        Properties.LOCAL_SEARCH_PROBABILITY = 1.0;
        Properties.STOPPING_CONDITION = Properties.StoppingCondition.MAXGENERATIONS;
        Properties.SEARCH_BUDGET = 2; // Run for at least one iteration
        Properties.TEST_ARCHIVE = false; // Disable archive to isolate LS effect
        Properties.POPULATION = 5;

        ChromosomeFactory<TestChromosome> factory = Mockito.mock(ChromosomeFactory.class);
        Mockito.when(factory.getChromosome()).thenAnswer(invocation -> new TestChromosome());

        StandardChemicalReaction<TestChromosome> cro = new StandardChemicalReaction<>(factory);

        // Mock a fitness function
        FitnessFunction<TestChromosome> ff = new FitnessFunction<TestChromosome>() {
            @Override
            public double getFitness(TestChromosome chromosome) {
                return chromosome.fitness;
            }

            @Override
            public boolean isMaximizationFunction() {
                return false;
            }
        };
        cro.addFitnessFunction(ff);

        // Expect no exception
        cro.generateSolution();
    }

    @Test
    public void testEnergyConservationWithGoodLocalSearch() {
        // Setup
        Properties.LOCAL_SEARCH_RATE = 1;
        Properties.LOCAL_SEARCH_PROBABILITY = 1.0;
        Properties.STOPPING_CONDITION = Properties.StoppingCondition.MAXGENERATIONS;
        Properties.SEARCH_BUDGET = 2;
        Properties.TEST_ARCHIVE = false;
        Properties.POPULATION = 5;

        ChromosomeFactory<TestChromosome> factory = Mockito.mock(ChromosomeFactory.class);
        Mockito.when(factory.getChromosome()).thenAnswer(invocation -> new TestChromosome());

        StandardChemicalReaction<TestChromosome> cro = new StandardChemicalReaction<>(factory);

        FitnessFunction<TestChromosome> ff = new FitnessFunction<TestChromosome>() {
            @Override
            public double getFitness(TestChromosome chromosome) {
                return chromosome.fitness;
            }

            @Override
            public boolean isMaximizationFunction() {
                return false;
            }
        };
        cro.addFitnessFunction(ff);

        // This chromosome improves with LS (lower fitness)
        TestChromosome.improve = true;

        try {
            cro.generateSolution();
        } catch (RuntimeException e) {
            Assert.fail("Should not crash when LS improves fitness: " + e.getMessage());
        }
        TestChromosome.improve = false;
    }

    // Stub class for testing
    static class TestChromosome extends Chromosome<TestChromosome> {
        private static final long serialVersionUID = 1L;
        public double fitness = 100.0;
        public static boolean improve = false;

        public TestChromosome() {
            setKineticEnergy(Properties.INITIAL_KINETIC_ENERGY);
        }

        @Override
        public TestChromosome clone() {
            TestChromosome c = new TestChromosome();
            c.fitness = this.fitness;
            c.setKineticEnergy(this.getKineticEnergy());
            c.setNumCollisions(this.getNumCollisions());
            return c;
        }

        @Override
        public double getFitness() {
            return fitness;
        }

        @Override
        public double getFitness(FitnessFunction<TestChromosome> ff) {
            return fitness;
        }

        @Override
        public boolean equals(Object obj) { return this == obj; }
        @Override
        public int hashCode() { return System.identityHashCode(this); }
        @Override
        public int compareTo(TestChromosome o) { return Double.compare(this.fitness, o.fitness); }
        @Override
        public int compareSecondaryObjective(TestChromosome o) { return 0; }
        @Override
        public void mutate() {
             // Change state to simulate mutation
             this.setChanged(true);
        }
        @Override
        public void crossOver(TestChromosome other, int position1, int position2) throws ConstructionFailedException {
             this.setChanged(true);
        }
        @Override
        public boolean localSearch(LocalSearchObjective<TestChromosome> objective) {
            if (improve) {
                fitness -= 10.0;
                if (fitness < 0) fitness = 0;
            } else {
                fitness += 5000.0; // Worsen significantly to drain buffer
            }
            return true;
        }
        @Override
        public int size() { return 1; }

        @Override
        public TestChromosome self() {
            return this;
        }
    }
}
