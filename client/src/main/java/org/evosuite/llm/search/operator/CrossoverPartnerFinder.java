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

import org.evosuite.Properties;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.utils.Randomness;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Picks a crossover partner for a mutation parent, shared by both {@code
 * DIRECTED} and {@code RANDOM} targeting: a partner is only ever eligible if
 * its cached fitness for the goal is finite and at most twice {@code best},
 * and it covers at least {@link Properties#LLM_OPERATOR_PARTNER_MIN_NEW_GOALS}
 * goals the parent does not. Both modes build the same eligible list via
 * {@link #eligible}; they differ only in how they pick from it.
 */
final class CrossoverPartnerFinder {

    private CrossoverPartnerFinder() {
    }

    /**
     * The number of goals {@code candidate} covers that {@code parent} does
     * not, or -1 when {@code candidate} is not an eligible partner: it must be
     * another individual whose cached fitness for {@code goal} is finite and
     * at most {@code 2 * best}, covering at least
     * {@link Properties#LLM_OPERATOR_PARTNER_MIN_NEW_GOALS} new goals.
     */
    private static int newGoalsIfEligible(TestChromosome candidate, TestChromosome parent,
                                          Set<TestFitnessFunction> parentCovered,
                                          TestFitnessFunction goal, double best) {
        if (candidate == null || candidate == parent || candidate.getTestCase() == null) {
            return -1;
        }
        Double distance = candidate.getFitnessValues().get(goal);
        if (distance == null || !Double.isFinite(distance) || distance > 2.0 * best) {
            return -1;
        }
        int newGoals = countNewGoals(candidate.getTestCase().getCoveredGoals(), parentCovered);
        return newGoals < Properties.LLM_OPERATOR_PARTNER_MIN_NEW_GOALS ? -1 : newGoals;
    }

    /** The eligible partners for {@code parent}; see {@link #newGoalsIfEligible}. */
    static List<TestChromosome> eligible(List<TestChromosome> population, TestChromosome parent,
                                         TestFitnessFunction goal, double best) {
        Set<TestFitnessFunction> parentCovered = parent.getTestCase().getCoveredGoals();
        List<TestChromosome> result = new ArrayList<>();
        for (TestChromosome candidate : population) {
            if (newGoalsIfEligible(candidate, parent, parentCovered, goal, best) >= 0) {
                result.add(candidate);
            }
        }
        return result;
    }

    /** Among the eligible candidates, the one adding the most goals over the parent (ties broken by smaller distance), or null. */
    static TestChromosome directed(List<TestChromosome> population, TestChromosome parent,
                                   TestFitnessFunction goal, double best) {
        Set<TestFitnessFunction> parentCovered = parent.getTestCase().getCoveredGoals();
        TestChromosome chosen = null;
        int chosenNewGoals = -1;
        double chosenDistance = Double.POSITIVE_INFINITY;
        for (TestChromosome candidate : population) {
            int newGoals = newGoalsIfEligible(candidate, parent, parentCovered, goal, best);
            if (newGoals < 0) {
                continue;
            }
            double distance = candidate.getFitnessValues().get(goal);
            if (newGoals > chosenNewGoals || (newGoals == chosenNewGoals && distance < chosenDistance)) {
                chosen = candidate;
                chosenNewGoals = newGoals;
                chosenDistance = distance;
            }
        }
        return chosen;
    }

    /** A uniformly random pick among the eligible candidates, or null if none qualifies. */
    static TestChromosome random(List<TestChromosome> population, TestChromosome parent,
                                 TestFitnessFunction goal, double best) {
        List<TestChromosome> candidates = eligible(population, parent, goal, best);
        return candidates.isEmpty() ? null : Randomness.choice(candidates);
    }

    private static int countNewGoals(Set<TestFitnessFunction> candidateGoals, Set<TestFitnessFunction> parentGoals) {
        int count = 0;
        for (TestFitnessFunction goal : candidateGoals) {
            if (!parentGoals.contains(goal)) {
                count++;
            }
        }
        return count;
    }
}
