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
package org.evosuite.ga.operators.selection;

import java.util.ArrayList;
import java.util.List;

import org.evosuite.Properties;
import org.evosuite.ga.NSGAChromosome;
import org.evosuite.ga.metaheuristics.NSGAII;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Test Binary Tournament Selection using Crowded Comparison
 *
 * @author José Campos
 */
public class TestBinaryTournamentSelectionCrowdedComparison {
    @BeforeAll
    public static void setUp() {
        Properties.RANDOM_SEED = 1L;
    }

    @Test
    public void testNonDominationRank() {
        NSGAII<NSGAChromosome> ga = new NSGAII<>(null);
        BinaryTournamentSelectionCrowdedComparison<NSGAChromosome> ts = new BinaryTournamentSelectionCrowdedComparison<>();
        ga.setSelectionFunction(ts);

        NSGAChromosome c1 = new NSGAChromosome();
        NSGAChromosome c2 = new NSGAChromosome();

        // Set Rank
        c1.setRank(1);
        c2.setRank(0);

        List<NSGAChromosome> population = new ArrayList<>();
        population.add(c1);
        population.add(c2);

        Assertions.assertEquals(1, ts.getIndex(population));
    }

    @Test
    public void testCrowdingDistance() {
        NSGAII<NSGAChromosome> ga = new NSGAII<>(null);
        BinaryTournamentSelectionCrowdedComparison<NSGAChromosome> ts = new BinaryTournamentSelectionCrowdedComparison<>();
        ts.setMaximize(false);
        ga.setSelectionFunction(ts);

        NSGAChromosome c1 = new NSGAChromosome();
        NSGAChromosome c2 = new NSGAChromosome();

        // Set Rank
        c1.setRank(0);
        c2.setRank(0);

        // Set Distance
        c1.setDistance(0.1);
        c2.setDistance(0.5);

        List<NSGAChromosome> population = new ArrayList<>();
        population.add(c1);
        population.add(c2);

        Assertions.assertEquals(1, ts.getIndex(population));
    }
}
