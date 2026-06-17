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
 */
package org.evosuite.strategy;

import org.evosuite.Properties;
import org.evosuite.ga.stoppingconditions.MaxFitnessEvaluationsStoppingCondition;
import org.evosuite.ga.stoppingconditions.MaxGenerationStoppingCondition;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmStrategyControlFlowTest {

    private final boolean originalStopZero = Properties.STOP_ZERO;

    @AfterEach
    void restoreProperties() {
        Properties.STOP_ZERO = originalStopZero;
    }

    @Test
    void emptySuiteStillHonorsPrimaryStoppingCondition() {
        LlmStrategy strategy = new LlmStrategy();
        MaxGenerationStoppingCondition<TestSuiteChromosome> condition =
                new MaxGenerationStoppingCondition<>();
        condition.setLimit(1);
        condition.forceCurrentValue(1);

        assertTrue(strategy.isFinishedWithTests(
                new TestSuiteChromosome(), condition));
    }

    @Test
    void emptySuiteDoesNotTriggerStopZeroByItself() {
        Properties.STOP_ZERO = true;
        LlmStrategy strategy = new LlmStrategy();
        MaxGenerationStoppingCondition<TestSuiteChromosome> condition =
                new MaxGenerationStoppingCondition<>();
        condition.setLimit(2);

        assertFalse(strategy.isFinishedWithTests(
                new TestSuiteChromosome(), condition));
    }

    @Test
    void fitnessBudgetAdvancesOnlyForEvaluatedRounds() {
        LlmStrategy strategy = new LlmStrategy();
        MaxFitnessEvaluationsStoppingCondition<TestSuiteChromosome> condition =
                new MaxFitnessEvaluationsStoppingCondition<>();
        condition.setLimit(2);
        TestSuiteChromosome suite = new TestSuiteChromosome();

        strategy.advanceStoppingCondition(condition, 1, suite, false);
        assertEquals(0, condition.getCurrentValue());

        strategy.advanceStoppingCondition(condition, 2, suite, true);
        assertEquals(1, condition.getCurrentValue());
    }

    @Test
    void goalFitnessUsesCachedExecutionAndStoresDistance() {
        LlmStrategy strategy = new LlmStrategy();
        DefaultTestCase testCase = new DefaultTestCase();
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(testCase);
        chromosome.setLastExecutionResult(new ExecutionResult(testCase));
        chromosome.setChanged(false);
        AtomicInteger evaluations = new AtomicInteger();
        TestFitnessFunction goal = new TestFitnessFunction() {
            @Override
            public double getFitness(TestChromosome individual,
                                     ExecutionResult result) {
                evaluations.incrementAndGet();
                assertEquals(chromosome.getLastExecutionResult(), result);
                return 0.25;
            }

            @Override
            public int compareTo(TestFitnessFunction other) {
                return 0;
            }

            @Override
            public String getTargetClass() {
                return "Target";
            }

            @Override
            public String getTargetMethod() {
                return "method()V";
            }

            @Override
            public boolean equals(Object other) {
                return this == other;
            }

            @Override
            public int hashCode() {
                return 17;
            }
        };

        strategy.measureGoalFitness(Collections.singletonList(chromosome),
                Collections.singletonList(goal));

        assertEquals(1, evaluations.get());
        assertEquals(0.25, chromosome.getFitnessValues().get(goal), 0.0);
    }
}
