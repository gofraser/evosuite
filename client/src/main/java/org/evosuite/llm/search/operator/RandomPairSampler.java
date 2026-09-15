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
package org.evosuite.llm.search.operator;

import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@code (parent, goal)} pairs that {@code RANDOM} targeting
 * samples from: every combination of a population member and a kept goal for
 * which that member has a finite cached fitness value. Shuffling the result
 * with {@link org.evosuite.utils.Randomness#shuffle} and then scanning it in
 * order, skipping any parent already used, samples uniformly among the valid
 * pairs without ever reusing a parent.
 */
final class RandomPairSampler {

    private RandomPairSampler() {
    }

    /** One (parent, goal) pairing with the parent's cached distance for that goal. */
    static final class Pair {
        final TestChromosome parent;
        final TestFitnessFunction goal;
        final double distance;

        Pair(TestChromosome parent, TestFitnessFunction goal, double distance) {
            this.parent = parent;
            this.goal = goal;
            this.distance = distance;
        }
    }

    /** Every (parent, goal) pair in {@code population x kept.keySet()} with a finite cached distance. */
    static List<Pair> validPairs(List<TestChromosome> population, Map<TestFitnessFunction, Double> kept) {
        List<Pair> pairs = new ArrayList<>();
        for (TestChromosome parent : population) {
            for (TestFitnessFunction goal : kept.keySet()) {
                Double distance = parent.getFitnessValues().get(goal);
                if (distance != null && Double.isFinite(distance)) {
                    pairs.add(new Pair(parent, goal, distance));
                }
            }
        }
        return pairs;
    }
}
