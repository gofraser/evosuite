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
package org.evosuite.llm.search;

import org.evosuite.Properties;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;

import java.util.Collection;

/**
 * Evaluates a chromosome against a frozen set of goals without touching the
 * search: no archive update, no goal-manager progress, no listener
 * notification, no population change. Used for probes whose only purpose is a
 * number in a sidecar — the semantic operator's result before later operators
 * alter it, and its standard-operator twin — so the probe cannot cover a goal
 * away from under the individual it is being compared with.
 *
 * <p>The test still runs once, so execution budgets see it like any probe.
 */
public final class IsolatedEvaluation {

    private IsolatedEvaluation() {
    }

    /**
     * Runs {@code chromosome} once and stores its fitness for exactly {@code goals}; any
     * previously cached values are discarded first.
     */
    public static void evaluate(TestChromosome chromosome, Collection<TestFitnessFunction> goals) {
        chromosome.clearCachedMutationResults();
        chromosome.clearCachedResults();
        chromosome.getFitnessValues().clear();
        ExecutionResult result = TestCaseExecutor.runTest(chromosome.getTestCase());
        chromosome.setLastExecutionResult(result);
        chromosome.setChanged(false);
        // Every goal type routes its archive update through this flag (see e.g.
        // LineCoverageTestFitness, which toggles it the same way for its own probe).
        boolean archive = Properties.TEST_ARCHIVE;
        Properties.TEST_ARCHIVE = false;
        try {
            for (TestFitnessFunction goal : goals) {
                goal.getFitness(chromosome, result);
            }
        } finally {
            Properties.TEST_ARCHIVE = archive;
        }
    }

    /** The cached distance of {@code chromosome} to {@code goal}, or NaN when it was not evaluated for it. */
    public static double distance(TestChromosome chromosome, TestFitnessFunction goal) {
        if (chromosome == null || goal == null) {
            return Double.NaN;
        }
        Double value = chromosome.getFitnessValues().get(goal);
        return value == null ? Double.NaN : value;
    }
}
