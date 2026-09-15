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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crossover-partner selection and the {@code allowCrossover} gate, split out
 * of {@link OperatorTargetSelectorTest} to keep each test class focused.
 */
class OperatorTargetSelectorCrossoverTest {

    private boolean savedCrossover;
    private int savedPartnerMinNewGoals;

    @BeforeEach
    void saveProperties() {
        savedCrossover = Properties.LLM_OPERATOR_CROSSOVER;
        savedPartnerMinNewGoals = Properties.LLM_OPERATOR_PARTNER_MIN_NEW_GOALS;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_OPERATOR_CROSSOVER = savedCrossover;
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
                Arrays.asList(parent, partner), Collections.singletonList(g), 1, 1, true);

        assertEquals(1, result.size());
        assertTrue(result.get(0).getPartner().isPresent());
        assertEquals(partner, result.get(0).getPartner().get());
        assertEquals(OperatorTarget.Kind.CROSSOVER, result.get(0).getKind());
    }

    @Test
    void allowCrossoverFalseYieldsMutationOnlyEvenWithAValidPartner() {
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
                Arrays.asList(parent, partner), Collections.singletonList(g), 1, 1, false);

        assertEquals(1, result.size());
        assertFalse(result.get(0).getPartner().isPresent(),
                "allowCrossover=false must suppress a partner even when the property allows crossover");
        assertEquals(OperatorTarget.Kind.MUTATION, result.get(0).getKind());
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
                Arrays.asList(parent, candidate), Collections.singletonList(g), 1, 1, true);

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
                Arrays.asList(parent, tooFar), Collections.singletonList(g), 1, 1, true);

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
                Arrays.asList(parent, wouldQualify), Collections.singletonList(g), 1, 1, true);

        assertEquals(1, result.size());
        assertFalse(result.get(0).getPartner().isPresent());
    }
}
