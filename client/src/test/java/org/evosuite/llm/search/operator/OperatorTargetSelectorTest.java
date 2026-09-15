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
import org.evosuite.llm.search.ProblemCard;
import org.evosuite.llm.search.ProblemCardType;
import org.evosuite.llm.search.RepeatedInjectionMemory;
import org.evosuite.llm.search.RepeatedInjectionTarget;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.utils.Randomness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperatorTargetSelectorTest {

    private Properties.LlmOperatorTargeting savedTargeting;
    private boolean savedCrossover;
    private int savedStallGenerations;
    private int savedPartnerMinNewGoals;

    @BeforeEach
    void saveProperties() {
        savedTargeting = Properties.LLM_OPERATOR_TARGETING;
        savedCrossover = Properties.LLM_OPERATOR_CROSSOVER;
        savedStallGenerations = Properties.LLM_OPERATOR_STALL_GENERATIONS;
        savedPartnerMinNewGoals = Properties.LLM_OPERATOR_PARTNER_MIN_NEW_GOALS;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_OPERATOR_TARGETING = savedTargeting;
        Properties.LLM_OPERATOR_CROSSOVER = savedCrossover;
        Properties.LLM_OPERATOR_STALL_GENERATIONS = savedStallGenerations;
        Properties.LLM_OPERATOR_PARTNER_MIN_NEW_GOALS = savedPartnerMinNewGoals;
    }

    private static TestChromosome chromosome(int marker) {
        DefaultTestCase testCase = new DefaultTestCase();
        testCase.addStatement(new IntPrimitiveStatement(testCase, marker));
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(testCase);
        chromosome.setLastExecutionResult(new ExecutionResult(testCase));
        chromosome.setChanged(false);
        return chromosome;
    }

    private static TestFitnessFunction goal(String name) {
        return new OperatorTargetTest.StubGoal(name);
    }

    private static OperatorTargetSelector.CardSource noCards() {
        return (goals, population, maxCards) -> Collections.emptyList();
    }

    // --- empty inputs -----------------------------------------------------

    @Test
    void emptyOrNullInputsReturnEmptyList() {
        OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());
        List<TestChromosome> population = Collections.singletonList(chromosome(1));
        List<TestFitnessFunction> goals = Collections.singletonList(goal("g"));

        assertTrue(selector.select(null, goals, 1).isEmpty());
        assertTrue(selector.select(Collections.emptyList(), goals, 1).isEmpty());
        assertTrue(selector.select(population, null, 1).isEmpty());
        assertTrue(selector.select(population, Collections.emptyList(), 1).isEmpty());
        assertTrue(selector.select(population, goals, 0).isEmpty());
        assertTrue(selector.select(population, goals, -1).isEmpty());
    }

    // --- ranking ------------------------------------------------------------

    @Test
    void closestGoalRankedFirst() {
        TestFitnessFunction goalA = goal("A");
        TestFitnessFunction goalB = goal("B");
        TestChromosome closeToA = chromosome(1);
        closeToA.setFitness(goalA, 0.2);
        closeToA.setFitness(goalB, 0.9);
        TestChromosome closeToB = chromosome(2);
        closeToB.setFitness(goalA, 0.95);
        closeToB.setFitness(goalB, 0.5);

        OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());
        List<OperatorTarget> result = selector.select(
                Arrays.asList(closeToA, closeToB), Arrays.asList(goalA, goalB), 2);

        assertEquals(2, result.size());
        assertEquals(goalA, result.get(0).getGoal(), "the goal closer to being covered has the higher priority");
        assertEquals(goalB, result.get(1).getGoal());
        assertTrue(result.get(0).getPriority() > result.get(1).getPriority());
    }

    @Test
    void coveredAndUnreachedGoalsAreSkipped() {
        TestFitnessFunction goalCovered = goal("covered");
        TestFitnessFunction goalMid = goal("mid");
        TestFitnessFunction goalUnreached = goal("unreached");

        TestChromosome parent = chromosome(1);
        parent.setFitness(goalCovered, 0.0);
        parent.getTestCase().addCoveredGoal(goalCovered);
        parent.setFitness(goalMid, 0.4);
        // goalUnreached: no cached fitness -> stub defaults to 1.0, which stays >= 1.0 (excluded)

        OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());
        List<OperatorTarget> result = selector.select(
                Collections.singletonList(parent),
                Arrays.asList(goalCovered, goalMid, goalUnreached), 3);

        assertEquals(1, result.size());
        assertEquals(goalMid, result.get(0).getGoal());
    }

    @Test
    void parentIsTheIndividualAttainingBest() {
        TestFitnessFunction g = goal("g");
        TestChromosome best = chromosome(1);
        best.setFitness(g, 0.1);
        TestChromosome worse = chromosome(2);
        worse.setFitness(g, 0.6);

        OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());
        List<OperatorTarget> result = selector.select(Arrays.asList(worse, best), Collections.singletonList(g), 1);

        assertEquals(1, result.size());
        assertEquals(best, result.get(0).getParent());
    }

    @Test
    void atMostOneTargetPerParent() {
        TestFitnessFunction goalA = goal("A");
        TestFitnessFunction goalB = goal("B");
        TestChromosome onlyParent = chromosome(1);
        onlyParent.setFitness(goalA, 0.2);
        onlyParent.setFitness(goalB, 0.3);

        OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());
        List<OperatorTarget> result = selector.select(
                Collections.singletonList(onlyParent), Arrays.asList(goalA, goalB), 2);

        assertEquals(1, result.size(), "the same parent cannot fill two slots in one batch");
        assertEquals(goalA, result.get(0).getGoal(), "the higher-priority goal wins the shared parent's slot");
    }

    @Test
    void topKLimitsResultSize() {
        TestFitnessFunction goalA = goal("A");
        TestFitnessFunction goalB = goal("B");
        TestFitnessFunction goalC = goal("C");
        TestChromosome parentA = chromosome(1);
        parentA.setFitness(goalA, 0.1);
        TestChromosome parentB = chromosome(2);
        parentB.setFitness(goalB, 0.4);
        TestChromosome parentC = chromosome(3);
        parentC.setFitness(goalC, 0.7);

        OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());
        List<OperatorTarget> result = selector.select(
                Arrays.asList(parentA, parentB, parentC), Arrays.asList(goalA, goalB, goalC), 2);

        assertEquals(2, result.size());
        assertEquals(goalA, result.get(0).getGoal());
        assertEquals(goalB, result.get(1).getGoal());
    }

    // --- crossover ------------------------------------------------------------

    @Test
    void crossoverPartnerChosenWithinRangeAndAddingGoals() {
        Properties.LLM_OPERATOR_CROSSOVER = true;
        Properties.LLM_OPERATOR_PARTNER_MIN_NEW_GOALS = 1;
        TestFitnessFunction g = goal("g");
        TestFitnessFunction goalX = goal("x");
        TestFitnessFunction goalY = goal("y");

        TestChromosome parent = chromosome(1);
        parent.setFitness(g, 0.1);
        parent.getTestCase().addCoveredGoal(goalX);

        TestChromosome partner = chromosome(2);
        partner.setFitness(g, 0.15); // <= 2 * 0.1
        partner.getTestCase().addCoveredGoal(goalX);
        partner.getTestCase().addCoveredGoal(goalY); // 1 new goal over parent

        OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());
        List<OperatorTarget> result = selector.select(
                Arrays.asList(parent, partner), Collections.singletonList(g), 1);

        assertEquals(1, result.size());
        assertTrue(result.get(0).getPartner().isPresent());
        assertEquals(partner, result.get(0).getPartner().get());
        assertEquals(OperatorTarget.Kind.CROSSOVER, result.get(0).getKind());
    }

    @Test
    void crossoverPartnerNotChosenWhenTooFewNewGoals() {
        Properties.LLM_OPERATOR_CROSSOVER = true;
        Properties.LLM_OPERATOR_PARTNER_MIN_NEW_GOALS = 1;
        TestFitnessFunction g = goal("g");
        TestFitnessFunction goalX = goal("x");

        TestChromosome parent = chromosome(1);
        parent.setFitness(g, 0.1);
        parent.getTestCase().addCoveredGoal(goalX);

        TestChromosome candidate = chromosome(2);
        candidate.setFitness(g, 0.12);
        candidate.getTestCase().addCoveredGoal(goalX); // 0 new goals over parent

        OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());
        List<OperatorTarget> result = selector.select(
                Arrays.asList(parent, candidate), Collections.singletonList(g), 1);

        assertEquals(1, result.size());
        assertFalse(result.get(0).getPartner().isPresent());
        assertEquals(OperatorTarget.Kind.MUTATION, result.get(0).getKind());
    }

    @Test
    void crossoverPartnerNotChosenWhenTooFar() {
        Properties.LLM_OPERATOR_CROSSOVER = true;
        Properties.LLM_OPERATOR_PARTNER_MIN_NEW_GOALS = 1;
        TestFitnessFunction g = goal("g");
        TestFitnessFunction goalX = goal("x");
        TestFitnessFunction goalY = goal("y");

        TestChromosome parent = chromosome(1);
        parent.setFitness(g, 0.1);
        parent.getTestCase().addCoveredGoal(goalX);

        TestChromosome tooFar = chromosome(2);
        tooFar.setFitness(g, 0.25); // > 2 * 0.1
        tooFar.getTestCase().addCoveredGoal(goalX);
        tooFar.getTestCase().addCoveredGoal(goalY);

        OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());
        List<OperatorTarget> result = selector.select(
                Arrays.asList(parent, tooFar), Collections.singletonList(g), 1);

        assertEquals(1, result.size());
        assertFalse(result.get(0).getPartner().isPresent());
    }

    @Test
    void crossoverDisabledByPropertyYieldsNoPartner() {
        Properties.LLM_OPERATOR_CROSSOVER = false;
        Properties.LLM_OPERATOR_PARTNER_MIN_NEW_GOALS = 1;
        TestFitnessFunction g = goal("g");
        TestFitnessFunction goalX = goal("x");
        TestFitnessFunction goalY = goal("y");

        TestChromosome parent = chromosome(1);
        parent.setFitness(g, 0.1);
        parent.getTestCase().addCoveredGoal(goalX);

        TestChromosome wouldQualify = chromosome(2);
        wouldQualify.setFitness(g, 0.15);
        wouldQualify.getTestCase().addCoveredGoal(goalX);
        wouldQualify.getTestCase().addCoveredGoal(goalY);

        OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());
        List<OperatorTarget> result = selector.select(
                Arrays.asList(parent, wouldQualify), Collections.singletonList(g), 1);

        assertEquals(1, result.size());
        assertFalse(result.get(0).getPartner().isPresent());
    }

    // --- stall boost ------------------------------------------------------------

    @Test
    void stallBoostAppliesAfterConfiguredGenerationsAndResetsOnChange() {
        Properties.LLM_OPERATOR_STALL_GENERATIONS = 2;
        TestFitnessFunction g = goal("g");
        TestChromosome parent = chromosome(1);
        parent.setFitness(g, 0.4);

        OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());
        List<TestFitnessFunction> goals = Collections.singletonList(g);
        List<TestChromosome> population = Collections.singletonList(parent);

        OperatorTarget first = selector.select(population, goals, 1).get(0);
        assertFalse(first.isStalled());

        OperatorTarget second = selector.select(population, goals, 1).get(0);
        assertFalse(second.isStalled(), "stall count reaches the threshold only after 2 unchanged observations");

        OperatorTarget third = selector.select(population, goals, 1).get(0);
        assertTrue(third.isStalled());
        assertEquals(1.5 * (1.0 - 0.4), third.getPriority(), 1e-9);

        parent.setFitness(g, 0.3);
        OperatorTarget afterImprovement = selector.select(population, goals, 1).get(0);
        assertFalse(afterImprovement.isStalled(), "a changed best distance resets the stall counter");
    }

    // --- repeated-injection memory ------------------------------------------------------------

    @Test
    void memorySuppressesInFlightRepeatTarget() {
        TestFitnessFunction g = goal("g");
        TestChromosome parent = chromosome(1);
        parent.setFitness(g, 0.3);

        RepeatedInjectionMemory memory = new RepeatedInjectionMemory();
        OperatorTargetSelector selector = new OperatorTargetSelector(memory, noCards());
        List<TestFitnessFunction> goals = Collections.singletonList(g);
        List<TestChromosome> population = Collections.singletonList(parent);

        List<OperatorTarget> firstSelection = selector.select(population, goals, 1);
        assertEquals(1, firstSelection.size());
        RepeatedInjectionTarget rit = firstSelection.get(0).toRepeatedInjectionTarget();

        memory.registerAttempt(Collections.singletonList(rit), true);

        List<OperatorTarget> secondSelection = selector.select(population, goals, 1);
        assertTrue(secondSelection.isEmpty(), "an in-flight repeat of the same (parent, goal) target must be suppressed");
    }

    // --- problem cards ------------------------------------------------------------

    @Test
    void cardIsAttachedFromCardSourceAndFetchedOnce() {
        TestFitnessFunction goalA = goal("A");
        TestFitnessFunction goalB = goal("B");
        TestChromosome parentA = chromosome(1);
        parentA.setFitness(goalA, 0.1);
        TestChromosome parentB = chromosome(2);
        parentB.setFitness(goalB, 0.4);

        ProblemCard card = ProblemCard.builder(ProblemCardType.UNREACHED_METHOD)
                .title("card").relatedGoals(Collections.singletonList(goalA))
                .impact(1.0).blockage(1.0).confidence(1.0).build();
        AtomicInteger calls = new AtomicInteger();
        OperatorTargetSelector.CardSource cardSource = (goals, population, maxCards) -> {
            calls.incrementAndGet();
            return Collections.singletonList(card);
        };

        OperatorTargetSelector selector = new OperatorTargetSelector(null, cardSource);
        List<OperatorTarget> result = selector.select(
                Arrays.asList(parentA, parentB), Arrays.asList(goalA, goalB), 2);

        assertEquals(1, calls.get(), "the card source must be called exactly once per select()");
        assertEquals(2, result.size());
        for (OperatorTarget target : result) {
            if (target.getGoal().equals(goalA)) {
                assertTrue(target.getCard().isPresent());
                assertEquals(card, target.getCard().get());
            } else {
                assertFalse(target.getCard().isPresent());
            }
        }
    }

    // --- RANDOM control mode ------------------------------------------------------------

    @Test
    void randomModeYieldsDistinctParentsAndGoalsFromCurrentGoals() {
        Properties.LLM_OPERATOR_TARGETING = Properties.LlmOperatorTargeting.RANDOM;
        Randomness.State savedRandomState = Randomness.snapshot();
        try {
            Randomness.setSeed(42L);
            List<TestChromosome> population = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                population.add(chromosome(i));
            }
            Collection<TestFitnessFunction> goals = Arrays.asList(goal("A"), goal("B"), goal("C"));

            OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());
            List<OperatorTarget> result = selector.select(population, goals, 3);

            assertEquals(3, result.size());
            Set<TestChromosome> parents = new HashSet<>();
            for (OperatorTarget target : result) {
                assertTrue(goals.contains(target.getGoal()));
                parents.add(target.getParent());
            }
            assertEquals(3, parents.size(), "random selection must not repeat a parent across targets");
        } finally {
            Randomness.restore(savedRandomState);
        }
    }

    @Test
    void randomModeReturnsFewerTargetsThanPopulationSize() {
        Properties.LLM_OPERATOR_TARGETING = Properties.LlmOperatorTargeting.RANDOM;
        Randomness.State savedRandomState = Randomness.snapshot();
        try {
            Randomness.setSeed(7L);
            List<TestChromosome> population = Arrays.asList(chromosome(1), chromosome(2));
            Collection<TestFitnessFunction> goals = Collections.singletonList(goal("A"));

            OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());
            List<OperatorTarget> result = selector.select(population, goals, 5);

            assertEquals(2, result.size(), "fewer targets than k are returned when the population is smaller");
        } finally {
            Randomness.restore(savedRandomState);
        }
    }
}
