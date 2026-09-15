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
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stall-boost behaviour through {@link OperatorTargetSelector#select}, split
 * out of {@link OperatorTargetSelectorTest} to keep each test class focused.
 * In particular, these tests exercise the once-per-generation guard: repeated
 * {@code select} calls carrying the same {@code generation} number must not
 * each count as a fresh consecutive observation (see
 * {@link GoalStallTrackerTest} for lower-level coverage of the same guard).
 */
class OperatorTargetSelectorStallTest {

    private int savedStallGenerations;

    @BeforeEach
    void saveProperties() {
        savedStallGenerations = Properties.LLM_OPERATOR_STALL_GENERATIONS;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_OPERATOR_STALL_GENERATIONS = savedStallGenerations;
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
    void stallBoostAppliesAfterConfiguredGenerationsAndResetsOnChange() {
        Properties.LLM_OPERATOR_STALL_GENERATIONS = 2;
        TestFitnessFunction g = goal("g");
        TestChromosome parent = chromosome(1);
        parent.setFitness(g, 0.4);

        OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());
        List<TestFitnessFunction> goals = Collections.singletonList(g);
        List<TestChromosome> population = Collections.singletonList(parent);

        OperatorTarget first = selector.select(population, goals, 1, 1, true).get(0);
        assertFalse(first.isStalled());

        OperatorTarget second = selector.select(population, goals, 1, 2, true).get(0);
        assertFalse(second.isStalled(), "stall count reaches the threshold only after 2 unchanged observations");

        OperatorTarget third = selector.select(population, goals, 1, 3, true).get(0);
        assertTrue(third.isStalled());
        assertEquals(1.5 * (1.0 - 0.4), third.getPriority(), 1e-9);

        parent.setFitness(g, 0.3);
        OperatorTarget afterImprovement = selector.select(population, goals, 1, 4, true).get(0);
        assertFalse(afterImprovement.isStalled(), "a changed best distance resets the stall counter");
    }

    @Test
    void stallCountIsNotStaleAfterGoalTemporarilyLeavesKeptRange() {
        Properties.LLM_OPERATOR_STALL_GENERATIONS = 3;
        TestFitnessFunction g = goal("g");
        TestChromosome parent = chromosome(1);
        List<TestFitnessFunction> goals = Collections.singletonList(g);
        List<TestChromosome> population = Collections.singletonList(parent);
        OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());

        parent.setFitness(g, 0.5);
        selector.select(population, goals, 1, 1, true); // 1st consecutive observation at 0.5
        selector.select(population, goals, 1, 2, true); // 2nd consecutive observation at 0.5
        parent.setFitness(g, 1.0);
        assertTrue(selector.select(population, goals, 1, 3, true).isEmpty(), "best 1.0 is not kept, so nothing is targetable");
        parent.setFitness(g, 0.5);
        selector.select(population, goals, 1, 4, true); // 1st consecutive observation after rejoining
        OperatorTarget last = selector.select(population, goals, 1, 5, true).get(0); // 2nd consecutive observation after rejoining

        assertFalse(last.isStalled(),
                "only 2 consecutive observations at 0.5 since rejoining the kept set, not " + Properties.LLM_OPERATOR_STALL_GENERATIONS);
    }

    @Test
    void fourConsecutiveUnchangedGenerationsStallThroughTheSelector() {
        Properties.LLM_OPERATOR_STALL_GENERATIONS = 3;
        TestFitnessFunction g = goal("g");
        TestChromosome parent = chromosome(1);
        parent.setFitness(g, 0.5);
        List<TestFitnessFunction> goals = Collections.singletonList(g);
        List<TestChromosome> population = Collections.singletonList(parent);
        OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());

        selector.select(population, goals, 1, 1, true);
        selector.select(population, goals, 1, 2, true);
        selector.select(population, goals, 1, 3, true);
        OperatorTarget fourth = selector.select(population, goals, 1, 4, true).get(0);

        assertTrue(fourth.isStalled(), "4 consecutive unchanged observations must be stalled at threshold 3");
    }

    @Test
    void repeatedCallsWithinOneGenerationDoNotEachCountAsAnObservation() {
        Properties.LLM_OPERATOR_STALL_GENERATIONS = 3;
        TestFitnessFunction g = goal("g");
        TestChromosome parent = chromosome(1);
        parent.setFitness(g, 0.5);
        List<TestFitnessFunction> goals = Collections.singletonList(g);
        List<TestChromosome> population = Collections.singletonList(parent);
        OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());

        selector.select(population, goals, 1, 1, true);
        selector.select(population, goals, 1, 1, true);
        OperatorTarget third = selector.select(population, goals, 1, 1, true).get(0);

        assertFalse(third.isStalled(),
                "3 calls for the same generation must not count as 3 consecutive observations");
    }

    @Test
    void distinctGenerationsAdvanceStallAndARepeatCallReusesIt() {
        Properties.LLM_OPERATOR_STALL_GENERATIONS = 3;
        TestFitnessFunction g = goal("g");
        TestChromosome parent = chromosome(1);
        parent.setFitness(g, 0.5);
        List<TestFitnessFunction> goals = Collections.singletonList(g);
        List<TestChromosome> population = Collections.singletonList(parent);
        OperatorTargetSelector selector = new OperatorTargetSelector(null, noCards());

        selector.select(population, goals, 1, 0, true); // baseline observation
        selector.select(population, goals, 1, 1, true);
        OperatorTarget afterGenerationTwo = selector.select(population, goals, 1, 2, true).get(0);
        assertFalse(afterGenerationTwo.isStalled(), "only 3 consecutive observations so far, below the threshold of 3");

        OperatorTarget afterGenerationThree = selector.select(population, goals, 1, 3, true).get(0);
        assertTrue(afterGenerationThree.isStalled(), "4 consecutive observations reach the threshold of 3");

        OperatorTarget repeatedGenerationThree = selector.select(population, goals, 1, 3, true).get(0);
        assertTrue(repeatedGenerationThree.isStalled(), "a repeat call at the same generation must keep reporting the reused count");
    }
}
