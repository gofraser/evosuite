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
import org.evosuite.utils.Randomness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossoverPartnerFinderTest {

    private int savedPartnerMinNewGoals;

    @BeforeEach
    void saveProperties() {
        savedPartnerMinNewGoals = Properties.LLM_OPERATOR_PARTNER_MIN_NEW_GOALS;
        Properties.LLM_OPERATOR_PARTNER_MIN_NEW_GOALS = 1;
    }

    @AfterEach
    void restoreProperties() {
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

    @Test
    void directedPicksTheCandidateWithMostNewGoals() {
        TestFitnessFunction g = goal("g");
        TestFitnessFunction goalX = goal("x");
        TestFitnessFunction goalY = goal("y");
        TestFitnessFunction goalZ = goal("z");

        TestChromosome parent = chromosome(1);
        parent.setFitness(g, 0.1);

        TestChromosome oneNewGoal = chromosome(2);
        oneNewGoal.setFitness(g, 0.15);
        oneNewGoal.getTestCase().addCoveredGoal(goalX);

        TestChromosome twoNewGoals = chromosome(3);
        twoNewGoals.setFitness(g, 0.18);
        twoNewGoals.getTestCase().addCoveredGoal(goalY);
        twoNewGoals.getTestCase().addCoveredGoal(goalZ);

        TestChromosome chosen = CrossoverPartnerFinder.directed(
                Arrays.asList(parent, oneNewGoal, twoNewGoals), parent, g, 0.1);

        assertEquals(twoNewGoals, chosen);
    }

    @Test
    void directedRejectsCandidateFartherThanTwiceBest() {
        TestFitnessFunction g = goal("g");
        TestFitnessFunction goalX = goal("x");

        TestChromosome parent = chromosome(1);
        parent.setFitness(g, 0.1);

        TestChromosome tooFar = chromosome(2);
        tooFar.setFitness(g, 0.21); // > 2 * 0.1
        tooFar.getTestCase().addCoveredGoal(goalX);

        assertNull(CrossoverPartnerFinder.directed(Arrays.asList(parent, tooFar), parent, g, 0.1));
    }

    @Test
    void randomIsChosenFromTheSameEligibleSetAsDirected() {
        TestFitnessFunction g = goal("g");
        TestFitnessFunction goalX = goal("x");
        TestFitnessFunction goalY = goal("y");

        TestChromosome parent = chromosome(1);
        parent.setFitness(g, 0.1);

        TestChromosome eligible = chromosome(2);
        eligible.setFitness(g, 0.15); // <= 2 * 0.1
        eligible.getTestCase().addCoveredGoal(goalX);

        TestChromosome tooFar = chromosome(3);
        tooFar.setFitness(g, 0.5); // > 2 * 0.1
        tooFar.getTestCase().addCoveredGoal(goalY);

        Randomness.State savedState = Randomness.snapshot();
        try {
            Randomness.setSeed(1L);
            TestChromosome chosen = CrossoverPartnerFinder.random(
                    Arrays.asList(parent, eligible, tooFar), parent, g, 0.1);
            assertEquals(eligible, chosen, "the only eligible candidate must always be chosen");
        } finally {
            Randomness.restore(savedState);
        }
    }

    @Test
    void randomAppliesTheTwiceBestRuleJustLikeDirected() {
        TestFitnessFunction g = goal("g");
        TestFitnessFunction goalX = goal("x");

        TestChromosome parent = chromosome(1);
        parent.setFitness(g, 0.1);

        TestChromosome tooFar = chromosome(2);
        tooFar.setFitness(g, 0.5); // > 2 * 0.1, would add a new goal but is too far
        tooFar.getTestCase().addCoveredGoal(goalX);

        assertNull(CrossoverPartnerFinder.random(Arrays.asList(parent, tooFar), parent, g, 0.1),
                "a candidate beyond 2*best must never be picked, even if it would add goals");
    }

    @Test
    void randomReturnsNullWhenNoCandidateQualifies() {
        TestFitnessFunction g = goal("g");
        TestChromosome parent = chromosome(1);
        parent.setFitness(g, 0.1);

        TestChromosome noNewGoals = chromosome(2);
        noNewGoals.setFitness(g, 0.1);

        assertNull(CrossoverPartnerFinder.random(Arrays.asList(parent, noNewGoals), parent, g, 0.1));
    }

    @Test
    void eligibleFindsAPartnerWheneverOneExists() {
        TestFitnessFunction g = goal("g");
        TestFitnessFunction goalX = goal("x");

        TestChromosome parent = chromosome(1);
        parent.setFitness(g, 0.2);

        TestChromosome eligible = chromosome(2);
        eligible.setFitness(g, 0.3); // <= 2 * 0.2
        eligible.getTestCase().addCoveredGoal(goalX);

        List<TestChromosome> found = CrossoverPartnerFinder.eligible(
                Collections.singletonList(eligible), parent, g, 0.2);
        assertEquals(1, found.size());
        assertTrue(found.contains(eligible));
    }
}
