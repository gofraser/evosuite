/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.ga.stoppingconditions;

import org.evosuite.Properties;
import org.evosuite.ga.Chromosome;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class StoppingConditionsTest {

    // Concrete class for generic T
    static abstract class TestChromosome extends Chromosome<TestChromosome> {
        private static final long serialVersionUID = 1L;
    }

    @Test
    public void testMaxTimeStoppingCondition() {
        MaxTimeStoppingCondition<TestChromosome> condition = new MaxTimeStoppingCondition<>();
        Properties.SEARCH_BUDGET = 2; // 2 seconds
        condition.setLimit(2);

        condition.reset();
        assertFalse(condition.isFinished());

        // Test forceCurrentValue
        // forceCurrentValue(1) means 1 second has passed.
        condition.forceCurrentValue(1);
        long val = condition.getCurrentValue();

        // Since we use System.nanoTime(), exact match is hard.
        // forceCurrentValue sets startTime such that (now - startTime) == value.
        // But execution time passes between forceCurrentValue and getCurrentValue.
        // So val should be >= 1.
        assertTrue("Expected >= 1, got " + val, val >= 1);

        condition.forceCurrentValue(3);
        assertTrue(condition.isFinished());
    }

    @Test
    public void testMaxFitnessEvaluationsStoppingCondition() {
        MaxFitnessEvaluationsStoppingCondition<TestChromosome> c1 = new MaxFitnessEvaluationsStoppingCondition<>();
        MaxFitnessEvaluationsStoppingCondition<TestChromosome> c2 = new MaxFitnessEvaluationsStoppingCondition<>();

        c1.setLimit(10);
        c2.setLimit(10);

        c1.fitnessEvaluation(null);
        c1.fitnessEvaluation(null);

        assertEquals(2, c1.getCurrentValue());
        assertEquals(0, c2.getCurrentValue()); // Verify isolation

        c2.fitnessEvaluation(null);
        assertEquals(2, c1.getCurrentValue());
        assertEquals(1, c2.getCurrentValue());

        c1.reset();
        assertEquals(0, c1.getCurrentValue());
        assertEquals(1, c2.getCurrentValue());
    }

    @Test
    public void testMaxTestsStoppingCondition() {
        MaxTestsStoppingCondition<TestChromosome> condition = new MaxTestsStoppingCondition<>();
        condition.reset(); // Resets global counter

        assertEquals(0, MaxTestsStoppingCondition.getNumExecutedTests());

        MaxTestsStoppingCondition.testExecuted();
        assertEquals(1, MaxTestsStoppingCondition.getNumExecutedTests());
        assertEquals(1, condition.getCurrentValue());

        condition.reset();
        assertEquals(0, MaxTestsStoppingCondition.getNumExecutedTests());
    }

    @Test
    public void testMaxStatementsStoppingCondition() {
        MaxStatementsStoppingCondition<TestChromosome> condition = new MaxStatementsStoppingCondition<>();
        condition.reset(); // Resets global counter

        assertEquals(0, MaxStatementsStoppingCondition.getNumExecutedStatements());

        MaxStatementsStoppingCondition.statementsExecuted(5);
        assertEquals(5, MaxStatementsStoppingCondition.getNumExecutedStatements());
        assertEquals(5, condition.getCurrentValue());

        condition.reset();
        assertEquals(0, MaxStatementsStoppingCondition.getNumExecutedStatements());
    }

    @Test
    public void testGlobalTimeStoppingCondition() {
        GlobalTimeStoppingCondition<TestChromosome> condition = new GlobalTimeStoppingCondition<>();
        GlobalTimeStoppingCondition.forceReset();
        // Reset sets startTime if 0. forceReset sets to 0.

        // First, check that if startTime is 0, getCurrentValue is 0.
        assertEquals(0, condition.getCurrentValue());

        // Now call reset() to start the timer
        condition.reset();
        long val = condition.getCurrentValue();
        assertTrue(val >= 0);

        // Force value
        condition.forceCurrentValue(100);
        // Should be approx 100
        assertTrue(condition.getCurrentValue() >= 100);
    }

    @Test
    public void testZeroFitnessStoppingCondition() {
        ZeroFitnessStoppingCondition<TestChromosome> condition = new ZeroFitnessStoppingCondition<>();
        GeneticAlgorithm<TestChromosome> ga = mock(GeneticAlgorithm.class);
        TestChromosome ind = mock(TestChromosome.class);

        when(ga.getBestIndividual()).thenReturn(ind);

        when(ind.getFitness()).thenReturn(0.5);
        condition.iteration(ga);
        assertFalse(condition.isFinished());
        assertEquals(1, condition.getCurrentValue()); // 0.5 + 0.5 = 1.0 -> 1

        when(ind.getFitness()).thenReturn(0.0);
        condition.iteration(ga);
        assertTrue(condition.isFinished());
        assertEquals(0, condition.getCurrentValue());

        when(ind.getFitness()).thenReturn(-1.0);
        condition.iteration(ga);
        assertTrue(condition.isFinished());
    }
}
