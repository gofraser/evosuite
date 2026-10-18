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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code RANDOM} targeting, split out of {@link OperatorTargetSelectorTest}.
 *
 * <p>These tests exercise the uniform-sampling-without-underfill behaviour of
 * {@link RandomPairSampler}: {@code selectRandom} must fill up to {@code k}
 * targets from every valid {@code (parent, goal)} pair, not just from the
 * first {@code k} shuffled individuals, and a pair suppressed by the
 * repeated-injection memory must be skipped in favour of another valid pair
 * rather than left unfilled.
 */
class OperatorTargetSelectorRandomTest {

    private Properties.LlmOperatorTargeting savedTargeting;

    @BeforeEach
    void saveProperties() {
        savedTargeting = Properties.LLM_OPERATOR_TARGETING;
        Properties.LLM_OPERATOR_TARGETING = Properties.LlmOperatorTargeting.RANDOM;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_OPERATOR_TARGETING = savedTargeting;
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

    @Test
    void randomModeYieldsDistinctParentsAndGoalsFromCurrentGoals() {
        Randomness.State savedRandomState = Randomness.snapshot();
        try {
            Randomness.setSeed(42L);
            TestFitnessFunction goalA = goal("A");
            TestFitnessFunction goalB = goal("B");
            TestFitnessFunction goalC = goal("C");
            List<TestChromosome> population = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                TestChromosome tc = chromosome(i);
                tc.setFitness(goalA, 0.5);
                tc.setFitness(goalB, 0.5);
                tc.setFitness(goalC, 0.5);
                population.add(tc);
            }
            Collection<TestFitnessFunction> goals = Arrays.asList(goalA, goalB, goalC);

            OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());
            List<OperatorTarget> result = selector.select(population, goals, 3, 1, true);

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
    void randomModeReturnsAllValidParentsWhenKExceedsThem() {
        Randomness.State savedRandomState = Randomness.snapshot();
        try {
            Randomness.setSeed(7L);
            TestFitnessFunction goalA = goal("A");
            TestChromosome c1 = chromosome(1);
            c1.setFitness(goalA, 0.5);
            TestChromosome c2 = chromosome(2);
            c2.setFitness(goalA, 0.5);
            List<TestChromosome> population = Arrays.asList(c1, c2);
            Collection<TestFitnessFunction> goals = Collections.singletonList(goalA);

            OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());
            List<OperatorTarget> result = selector.select(population, goals, 5, 1, true);

            assertEquals(2, result.size(), "k larger than the number of valid parents returns all valid parents");
        } finally {
            Randomness.restore(savedRandomState);
        }
    }

    @Test
    void randomModeFillsExactlyFromTheValidParentsAcrossSeeds() {
        Randomness.State savedRandomState = Randomness.snapshot();
        try {
            for (long seed : new long[] {1L, 2L, 3L, 42L, 100L}) {
                Randomness.setSeed(seed);
                TestFitnessFunction goalA = goal("A");
                TestChromosome c1 = chromosome(1); // no cached fitness: never a valid parent
                TestChromosome c2 = chromosome(2); // no cached fitness: never a valid parent
                TestChromosome c3 = chromosome(3);
                c3.setFitness(goalA, 0.4);
                TestChromosome c4 = chromosome(4);
                c4.setFitness(goalA, 0.6);
                List<TestChromosome> population = Arrays.asList(c1, c2, c3, c4);
                Collection<TestFitnessFunction> goals = Collections.singletonList(goalA);

                OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());
                List<OperatorTarget> result = selector.select(population, goals, 2, 1, true);

                assertEquals(2, result.size(), "seed " + seed);
                Set<TestChromosome> parents = new HashSet<>();
                for (OperatorTarget target : result) {
                    parents.add(target.getParent());
                }
                assertEquals(new HashSet<>(Arrays.asList(c3, c4)), parents, "seed " + seed);
            }
        } finally {
            Randomness.restore(savedRandomState);
        }
    }

    @Test
    void randomModeNeverRepeatsAParent() {
        Randomness.State savedRandomState = Randomness.snapshot();
        try {
            Randomness.setSeed(19L);
            TestFitnessFunction goalA = goal("A");
            TestFitnessFunction goalB = goal("B");
            List<TestChromosome> population = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                TestChromosome tc = chromosome(i);
                tc.setFitness(goalA, 0.3);
                tc.setFitness(goalB, 0.6);
                population.add(tc);
            }
            Collection<TestFitnessFunction> goals = Arrays.asList(goalA, goalB);

            OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());
            List<OperatorTarget> result = selector.select(population, goals, 4, 1, true);

            Set<TestChromosome> parents = new HashSet<>();
            for (OperatorTarget target : result) {
                assertTrue(parents.add(target.getParent()), "the same parent must never fill two slots");
            }
        } finally {
            Randomness.restore(savedRandomState);
        }
    }

    @Test
    void randomModeReturnsEmptyWhenNoValidPairExists() {
        List<TestChromosome> population = Arrays.asList(chromosome(1), chromosome(2));
        Collection<TestFitnessFunction> goals = Collections.singletonList(goal("A"));

        OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());
        List<OperatorTarget> result = selector.select(population, goals, 5, 1, true);

        assertTrue(result.isEmpty(),
                "no goal has a cached best in (0,1), so there is no valid (parent, goal) pair to sample");
    }

    @Test
    void randomModeNeverProducesNaNDistance() {
        Randomness.State savedRandomState = Randomness.snapshot();
        try {
            Randomness.setSeed(11L);
            TestFitnessFunction goalA = goal("A");
            TestFitnessFunction goalB = goal("B");
            List<TestChromosome> population = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                TestChromosome tc = chromosome(i);
                // only goalA ever gets a cached value; goalB is never cached anywhere
                tc.setFitness(goalA, 0.1 * (i + 1));
                population.add(tc);
            }
            Collection<TestFitnessFunction> goals = Arrays.asList(goalA, goalB);

            OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());
            List<OperatorTarget> result = selector.select(population, goals, 6, 1, true);

            assertFalse(result.isEmpty());
            for (OperatorTarget target : result) {
                assertEquals(goalA, target.getGoal(), "goalB has no cached value anywhere, so it can never be sampled");
                assertFalse(Double.isNaN(target.getDistance()), "distance must never be NaN");
            }
        } finally {
            Randomness.restore(savedRandomState);
        }
    }

    @Test
    void randomModeAttachesCardFromCardSource() {
        Randomness.State savedRandomState = Randomness.snapshot();
        try {
            Randomness.setSeed(3L);
            TestFitnessFunction goalA = goal("A");
            List<TestChromosome> population = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                TestChromosome tc = chromosome(i);
                tc.setFitness(goalA, 0.4);
                population.add(tc);
            }
            Collection<TestFitnessFunction> goals = Collections.singletonList(goalA);

            ProblemCard card = ProblemCard.builder(ProblemCardType.UNREACHED_METHOD)
                    .title("card").relatedGoals(Collections.singletonList(goalA))
                    .impact(1.0).blockage(1.0).confidence(1.0).build();
            OperatorTargetSelector.CardSource cardSource =
                    (goalsArg, populationArg, maxCards) -> Collections.singletonList(card);

            OperatorTargetSelector selector = new OperatorTargetSelector(null, cardSource);
            List<OperatorTarget> result = selector.select(population, goals, 3, 1, true);

            assertFalse(result.isEmpty());
            for (OperatorTarget target : result) {
                assertTrue(target.getCard().isPresent(), "RANDOM mode must attach cards just like DIRECTED");
                assertEquals(card, target.getCard().get());
            }
        } finally {
            Randomness.restore(savedRandomState);
        }
    }

    @Test
    void randomModeSkipsASuppressedPairAndContinuesSamplingInsteadOfUnderfilling() {
        Randomness.State savedRandomState = Randomness.snapshot();
        try {
            Randomness.setSeed(42L);
            TestFitnessFunction goalA = goal("A");
            TestFitnessFunction goalB = goal("B");
            TestFitnessFunction goalC = goal("C");
            List<TestChromosome> population = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                TestChromosome tc = chromosome(i);
                tc.setFitness(goalA, 0.5);
                tc.setFitness(goalB, 0.5);
                tc.setFitness(goalC, 0.5);
                population.add(tc);
            }
            Collection<TestFitnessFunction> goals = Arrays.asList(goalA, goalB, goalC);

            RepeatedInjectionMemory memory = new RepeatedInjectionMemory();
            OperatorTargetSelector selector = new OperatorTargetSelector(memory, noCards());

            List<OperatorTarget> firstSelection = selector.select(population, goals, 3, 1, true);
            assertEquals(3, firstSelection.size(), "seeded so the deterministic sample yields 3 targets");
            RepeatedInjectionTarget rit = firstSelection.get(0).toRepeatedInjectionTarget();
            memory.registerAttempt(Collections.singletonList(rit), true);

            Randomness.setSeed(42L);
            List<OperatorTarget> secondSelection = selector.select(population, goals, 3, 2, true);

            assertEquals(3, secondSelection.size(),
                    "a pair suppressed by the repeated-injection memory must be skipped in favour of another"
                            + " valid pair, not left unfilled");
            for (OperatorTarget target : secondSelection) {
                assertNotEquals(rit.getFingerprint(), target.toRepeatedInjectionTarget().getFingerprint());
            }
        } finally {
            Randomness.restore(savedRandomState);
        }
    }
}
