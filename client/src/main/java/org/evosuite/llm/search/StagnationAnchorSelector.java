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
package org.evosuite.llm.search;

import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Selects the anchor test and its closest uncovered goals for the
 * test-anchored stagnation prompt.
 *
 * <p>The anchor is the test in the current population with the lowest fitness
 * value on any single uncovered goal — i.e. the population's best
 * near-miss. Goals shown to the LLM are the {@code maxGoals} uncovered goals
 * with the lowest fitness under that anchor.
 *
 * <p>Returns {@code null} when no test in the population has a usable fitness
 * entry for any uncovered goal; callers should fall back to a pool-level
 * prompt in that case.
 */
public final class StagnationAnchorSelector {

    private StagnationAnchorSelector() {}

    /** Anchor test plus its per-goal fitness map over the chosen goal subset. */
    public static final class AnchorSelection {
        private final TestChromosome anchor;
        private final List<TestFitnessFunction> goals;
        private final Map<TestFitnessFunction, Double> goalFitness;

        AnchorSelection(TestChromosome anchor,
                        List<TestFitnessFunction> goals,
                        Map<TestFitnessFunction, Double> goalFitness) {
            this.anchor = anchor;
            this.goals = goals;
            this.goalFitness = goalFitness;
        }

        public TestChromosome getAnchor() {
            return anchor;
        }

        public List<TestFitnessFunction> getGoals() {
            return goals;
        }

        public Map<TestFitnessFunction, Double> getGoalFitness() {
            return goalFitness;
        }
    }

    /**
     * Selects the anchor + its top-{@code maxGoals} closest uncovered goals.
     * Returns {@code null} when no anchor with a usable fitness signal exists.
     */
    public static AnchorSelection select(List<TestChromosome> population,
                                         Collection<TestFitnessFunction> uncoveredGoals,
                                         int maxGoals) {
        if (population == null || population.isEmpty()
                || uncoveredGoals == null || uncoveredGoals.isEmpty()
                || maxGoals <= 0) {
            return null;
        }

        TestChromosome bestAnchor = null;
        double bestMinFitness = Double.MAX_VALUE;
        int bestLength = Integer.MAX_VALUE;
        int bestIdentity = Integer.MAX_VALUE;

        for (TestChromosome candidate : population) {
            if (candidate == null) {
                continue;
            }
            double minFitness = minFitnessOverGoals(candidate, uncoveredGoals);
            if (minFitness == Double.MAX_VALUE) {
                continue;
            }
            int length = candidate.size();
            int identity = System.identityHashCode(candidate);
            if (minFitness < bestMinFitness
                    || (minFitness == bestMinFitness && length < bestLength)
                    || (minFitness == bestMinFitness && length == bestLength
                            && identity < bestIdentity)) {
                bestAnchor = candidate;
                bestMinFitness = minFitness;
                bestLength = length;
                bestIdentity = identity;
            }
        }

        if (bestAnchor == null) {
            return null;
        }

        Map<TestFitnessFunction, Double> anchorFitness =
                computeFitnessOverUncovered(bestAnchor, uncoveredGoals);
        if (anchorFitness.isEmpty()) {
            return null;
        }

        List<TestFitnessFunction> ranked = anchorFitness.entrySet().stream()
                .sorted(Comparator.comparingDouble(Map.Entry::getValue))
                .limit(maxGoals)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        Map<TestFitnessFunction, Double> filteredFitness = new LinkedHashMap<>();
        for (TestFitnessFunction goal : ranked) {
            filteredFitness.put(goal, anchorFitness.get(goal));
        }

        return new AnchorSelection(bestAnchor, Collections.unmodifiableList(ranked),
                Collections.unmodifiableMap(filteredFitness));
    }

    private static double minFitnessOverGoals(TestChromosome candidate,
                                              Collection<TestFitnessFunction> uncoveredGoals) {
        double min = Double.MAX_VALUE;
        for (TestFitnessFunction goal : uncoveredGoals) {
            Double f = safeFitness(candidate, goal);
            if (f != null && f < min) {
                min = f;
            }
        }
        return min;
    }

    private static Map<TestFitnessFunction, Double> computeFitnessOverUncovered(
            TestChromosome anchor, Collection<TestFitnessFunction> uncoveredGoals) {
        Map<TestFitnessFunction, Double> map = new LinkedHashMap<>();
        for (TestFitnessFunction goal : uncoveredGoals) {
            Double f = safeFitness(anchor, goal);
            if (f != null) {
                map.put(goal, f);
            }
        }
        return map;
    }

    private static Double safeFitness(TestChromosome candidate, TestFitnessFunction goal) {
        try {
            return candidate.getFitness(goal);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
