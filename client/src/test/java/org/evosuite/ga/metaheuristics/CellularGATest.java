/**
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
package org.evosuite.ga.metaheuristics;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.DummyChromosome;
import org.evosuite.ga.FitnessFunction;
import org.junit.Assert;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;

public class CellularGATest {

    @Test
    public void testPopulationConsistency() {
        Properties.POPULATION = 9; // Square number for L5 (3x3 grid)
        Properties.MODEL = Properties.CGA_Models.LINEAR_FIVE;
        Properties.ELITE = 0; // We removed implicit elitism usage but just to be safe with standard GA properties

        ChromosomeFactory<DummyChromosome> factory = new ChromosomeFactory<DummyChromosome>() {
            @Override
            public DummyChromosome getChromosome() {
                return new DummyChromosome(1);
            }
        };

        CellularGA<DummyChromosome> ga = new CellularGA<>(Properties.MODEL, factory);

        FitnessFunction<DummyChromosome> ff = new FitnessFunction<DummyChromosome>() {
            @Override
            public double getFitness(DummyChromosome individual) {
                return 0.0;
            }
            @Override
            public boolean isMaximizationFunction() {
                return false;
            }
        };
        ga.addFitnessFunction(ff);

        ga.initializePopulation();
        Assert.assertEquals(9, ga.getPopulation().size());

        ga.run();
        Assert.assertEquals(9, ga.getPopulation().size());

        ga.run();
        Assert.assertEquals(9, ga.getPopulation().size());
    }

    @Test
    public void testReplacementLogic() {
        Properties.POPULATION = 9;
        Properties.MODEL = Properties.CGA_Models.LINEAR_FIVE;

        ChromosomeFactory<DummyChromosome> factory = new ChromosomeFactory<DummyChromosome>() {
            @Override
            public DummyChromosome getChromosome() {
                return new DummyChromosome(1);
            }
        };

        CellularGA<DummyChromosome> ga = new CellularGA<>(Properties.MODEL, factory);

        // Minimization function
        FitnessFunction<DummyChromosome> ff = new FitnessFunction<DummyChromosome>() {
            @Override
            public double getFitness(DummyChromosome individual) {
                // If fitness is set, return it, otherwise 10.0
                return individual.getFitness();
            }
            @Override
            public boolean isMaximizationFunction() {
                return false;
            }
        };
        ga.addFitnessFunction(ff);

        // Manually setup population
        List<DummyChromosome> main = new ArrayList<>();
        List<DummyChromosome> temp = new ArrayList<>();

        for(int i=0; i<Properties.POPULATION; i++) {
            DummyChromosome c = new DummyChromosome(1);
            c.setFitness(ff, 10.0);
            main.add(c);

            DummyChromosome t = new DummyChromosome(1);
            if (i == 0) {
                t.setFitness(ff, 5.0); // Better
            } else {
                t.setFitness(ff, 15.0); // Worse
            }
            temp.add(t);
        }

        // This is what run() does internally but we test replacePopulations directly
        ga.replacePopulations(main, temp);

        // Index 0 should be replaced (5.0 < 10.0)
        Assert.assertEquals(5.0, main.get(0).getFitness(), 0.001);

        // Index 1 should NOT be replaced (15.0 > 10.0)
        Assert.assertEquals(10.0, main.get(1).getFitness(), 0.001);
    }
}
