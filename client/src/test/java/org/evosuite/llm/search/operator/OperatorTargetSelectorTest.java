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

import org.evosuite.llm.search.ProblemCard;
import org.evosuite.llm.search.ProblemCardType;
import org.evosuite.llm.search.RepeatedInjectionMemory;
import org.evosuite.llm.search.RepeatedInjectionTarget;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Empty-input handling, DIRECTED ranking, repeated-injection memory, problem
 * cards, and the cache-only selection guarantee. Crossover, stall-boost and
 * RANDOM targeting have their own test classes ({@link
 * OperatorTargetSelectorCrossoverTest}, {@link OperatorTargetSelectorStallTest},
 * {@link OperatorTargetSelectorRandomTest}) since none of these properties
 * interact with each other.
 */
class OperatorTargetSelectorTest {

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

        assertTrue(selector.select(null, goals, 1, 1, true).isEmpty());
        assertTrue(selector.select(Collections.emptyList(), goals, 1, 1, true).isEmpty());
        assertTrue(selector.select(population, null, 1, 1, true).isEmpty());
        assertTrue(selector.select(population, Collections.emptyList(), 1, 1, true).isEmpty());
        assertTrue(selector.select(population, goals, 0, 1, true).isEmpty());
        assertTrue(selector.select(population, goals, -1, 1, true).isEmpty());
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
                Arrays.asList(closeToA, closeToB), Arrays.asList(goalA, goalB), 2, 1, true);

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
                Arrays.asList(goalCovered, goalMid, goalUnreached), 3, 1, true);

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
        List<OperatorTarget> result = selector.select(Arrays.asList(worse, best), Collections.singletonList(g), 1, 1, true);

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
                Collections.singletonList(onlyParent), Arrays.asList(goalA, goalB), 2, 1, true);

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
                Arrays.asList(parentA, parentB, parentC), Arrays.asList(goalA, goalB, goalC), 2, 1, true);

        assertEquals(2, result.size());
        assertEquals(goalA, result.get(0).getGoal());
        assertEquals(goalB, result.get(1).getGoal());
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

        List<OperatorTarget> firstSelection = selector.select(population, goals, 1, 1, true);
        assertEquals(1, firstSelection.size());
        RepeatedInjectionTarget rit = firstSelection.get(0).toRepeatedInjectionTarget();

        memory.registerAttempt(Collections.singletonList(rit), true);

        List<OperatorTarget> secondSelection = selector.select(population, goals, 1, 2, true);
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
                Arrays.asList(parentA, parentB), Arrays.asList(goalA, goalB), 2, 1, true);

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

    // --- Finding 1: cache-only selection ------------------------------------------------------------

    @Test
    void selectionNeverReEvaluatesFitness() {
        AtomicInteger evaluations = new AtomicInteger();
        TestFitnessFunction goalA = new CountingStubGoal("A", evaluations);
        TestFitnessFunction goalB = new CountingStubGoal("B", evaluations);

        TestChromosome closeToA = chromosome(1);
        closeToA.setFitness(goalA, 0.2);
        closeToA.setFitness(goalB, 0.9);
        closeToA.setChanged(true);
        TestChromosome closeToB = chromosome(2);
        closeToB.setFitness(goalA, 0.95);
        closeToB.setFitness(goalB, 0.5);
        closeToB.setChanged(true);

        int evaluationsBeforeA = closeToA.getNumberOfEvaluations();
        int evaluationsBeforeB = closeToB.getNumberOfEvaluations();

        OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());
        List<OperatorTarget> result = selector.select(
                Arrays.asList(closeToA, closeToB), Arrays.asList(goalA, goalB), 2, 1, true);

        assertEquals(2, result.size());
        assertEquals(goalA, result.get(0).getGoal());
        assertEquals(goalB, result.get(1).getGoal());
        assertEquals(0, evaluations.get(), "select() must never call TestFitnessFunction.getFitness(TestChromosome, ExecutionResult)");
        assertEquals(evaluationsBeforeA, closeToA.getNumberOfEvaluations());
        assertEquals(evaluationsBeforeB, closeToB.getNumberOfEvaluations());
    }

    /** Stub goal that counts calls to {@link #getFitness(TestChromosome, ExecutionResult)} and fails the test if it is ever executed. */
    private static final class CountingStubGoal extends TestFitnessFunction {
        private static final long serialVersionUID = 1L;
        private final String name;
        private final AtomicInteger evaluations;

        CountingStubGoal(String name, AtomicInteger evaluations) {
            this.name = name;
            this.evaluations = evaluations;
        }

        @Override
        public double getFitness(TestChromosome individual, ExecutionResult result) {
            evaluations.incrementAndGet();
            return individual.getFitnessValues().getOrDefault(this, 1.0);
        }

        @Override
        public ExecutionResult runTest(org.evosuite.testcase.TestCase test) {
            throw new AssertionError("selection must not execute the test");
        }

        @Override
        public int compareTo(TestFitnessFunction other) {
            return other instanceof CountingStubGoal
                    ? name.compareTo(((CountingStubGoal) other).name) : compareClassName(other);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            return this == other || (other instanceof CountingStubGoal && name.equals(((CountingStubGoal) other).name));
        }

        @Override
        public String getTargetClass() {
            return "example.Target";
        }

        @Override
        public String getTargetMethod() {
            return name;
        }
    }
}
