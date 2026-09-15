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
package org.evosuite.llm.prompt;

import org.evosuite.Properties;
import org.evosuite.llm.search.ProblemCard;
import org.evosuite.llm.search.ProblemCardType;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds, for a goal or a problem card, the population test that comes closest
 * to covering it and attaches it as a concrete exemplar.
 *
 * <p>The "closest" test for a goal is the individual whose cached fitness for
 * that goal is nearest the population-wide best distance. Every card type with
 * related goals has such a test, so exemplars are not limited to branch cards;
 * {@link Properties#LLM_STAGNATION_EXEMPLAR_SCOPE} narrows attachment back to
 * {@link ProblemCardType#BRANCH_POLARITY_GAP} for the comparison arm.
 */
public final class ExemplarAttacher {

    /** Exemplar caption for branch cards; the wording predates multi-type exemplars and is kept. */
    static final String BRANCH_EXEMPLAR_FORMAT =
            "Closest near-miss for %s has branch distance %.4f; flip the condition";
    /** Exemplar caption for every other card type. */
    static final String GENERIC_EXEMPLAR_FORMAT =
            "Closest existing test for %s has fitness distance %.4f; start from it";

    private final GoalDescriptionMapper goalDescriptionMapper;

    public ExemplarAttacher() {
        this(new GoalDescriptionMapper());
    }

    public ExemplarAttacher(GoalDescriptionMapper goalDescriptionMapper) {
        this.goalDescriptionMapper = goalDescriptionMapper == null
                ? new GoalDescriptionMapper() : goalDescriptionMapper;
    }

    /**
     * Returns the individual whose cached fitness for {@code goal} is nearest
     * {@code expectedDistance} (normally the population best for that goal),
     * or null when no individual carries a finite fitness for the goal.
     */
    public static TestChromosome closestChromosome(TestFitnessFunction goal,
                                                   List<TestChromosome> population,
                                                   double expectedDistance) {
        if (goal == null || population == null) {
            return null;
        }
        TestChromosome closest = null;
        double smallestDelta = Double.POSITIVE_INFINITY;
        for (TestChromosome chromosome : population) {
            if (chromosome == null || chromosome.getTestCase() == null) {
                continue;
            }
            Double distance = chromosome.getFitnessValues().get(goal);
            if (distance == null || !Double.isFinite(distance)) {
                continue;
            }
            double delta = Math.abs(distance - expectedDistance);
            if (delta < smallestDelta) {
                smallestDelta = delta;
                closest = chromosome;
            }
        }
        return closest;
    }

    /** Test-case view of {@link #closestChromosome}. */
    public static TestCase closestTest(TestFitnessFunction goal,
                                       List<TestChromosome> population,
                                       double expectedDistance) {
        TestChromosome closest = closestChromosome(goal, population, expectedDistance);
        return closest == null ? null : closest.getTestCase();
    }

    /**
     * Returns the card's related goal with the smallest positive finite best
     * distance, or null when none qualifies (covered goals have distance 0 and
     * need no exemplar; unreached ones have no meaningful closest test).
     */
    public static TestFitnessFunction closestRelatedGoal(ProblemCard card,
                                                         Map<TestFitnessFunction, Double> bestFitnessPerGoal) {
        if (card == null || bestFitnessPerGoal == null) {
            return null;
        }
        TestFitnessFunction closestGoal = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (TestFitnessFunction goal : card.getRelatedGoals()) {
            Double distance = bestFitnessPerGoal.get(goal);
            if (distance != null && Double.isFinite(distance) && distance > 0.0
                    && distance < closestDistance) {
                closestGoal = goal;
                closestDistance = distance;
            }
        }
        return closestGoal;
    }

    /**
     * Attaches to each eligible card the population test closest to its
     * nearest related goal. Cards outside the configured scope, without a
     * qualifying goal, or without a matching test are returned unchanged.
     */
    public List<ProblemCard> attach(List<ProblemCard> cards,
                                    List<TestChromosome> population,
                                    Map<TestFitnessFunction, Double> bestFitnessPerGoal) {
        if (cards == null || cards.isEmpty() || population == null || population.isEmpty()
                || bestFitnessPerGoal == null || bestFitnessPerGoal.isEmpty()) {
            return cards;
        }
        List<ProblemCard> enriched = new ArrayList<>(cards.size());
        for (ProblemCard card : cards) {
            ProblemCard withExemplar = card == null || !inScope(card.getType()) ? card
                    : attach(card, population, bestFitnessPerGoal);
            enriched.add(withExemplar);
        }
        return enriched;
    }

    private ProblemCard attach(ProblemCard card,
                               List<TestChromosome> population,
                               Map<TestFitnessFunction, Double> bestFitnessPerGoal) {
        TestFitnessFunction goal = closestRelatedGoal(card, bestFitnessPerGoal);
        if (goal == null) {
            return card;
        }
        double distance = bestFitnessPerGoal.get(goal);
        TestCase closestTest = closestTest(goal, population, distance);
        if (closestTest == null) {
            return card;
        }
        return card.withConcreteExample(closestTest, describe(card.getType(), goal, distance));
    }

    /** The caption rendered above an exemplar; the single home of both wordings. */
    public String describe(ProblemCardType type, TestFitnessFunction goal, double distance) {
        String format = type == ProblemCardType.BRANCH_POLARITY_GAP
                ? BRANCH_EXEMPLAR_FORMAT : GENERIC_EXEMPLAR_FORMAT;
        return String.format(Locale.ROOT, format, goalDescriptionMapper.describe(goal), distance);
    }

    private static boolean inScope(ProblemCardType type) {
        if (type == null) {
            return false;
        }
        switch (Properties.LLM_STAGNATION_EXEMPLAR_SCOPE) {
            case BRANCH_POLARITY_GAP:
                return type == ProblemCardType.BRANCH_POLARITY_GAP;
            case ALL_CARD_TYPES:
            default:
                return true;
        }
    }
}
