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
package org.evosuite.dse;

import org.evosuite.EvoSuite;
import org.evosuite.Properties;
import org.evosuite.SystemTestBase;
import org.evosuite.symbolic.dse.algorithm.ExplorationAlgorithmBase;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class DSESystemTestBase extends SystemTestBase {
    @BeforeEach
    public void init() {
        Properties.VIRTUAL_FS = true;
        Properties.VIRTUAL_NET = true;
        Properties.LOCAL_SEARCH_PROBABILITY = 1.0;
        Properties.LOCAL_SEARCH_RATE = 1;
        Properties.LOCAL_SEARCH_BUDGET_TYPE = Properties.LocalSearchBudgetType.TESTS;
        Properties.LOCAL_SEARCH_BUDGET = 100;
        Properties.SEARCH_BUDGET = 50000;
        // Properties.CONCOLIC_TIMEOUT = Integer.MAX_VALUE;
        Properties.RESET_STATIC_FIELD_GETS = true;

        Properties.STOPPING_CONDITION = Properties.StoppingCondition.MAXTIME;
        Properties.SEARCH_BUDGET = 60 * 60 * 10; // 10 hours
        Properties.MINIMIZATION_TIMEOUT = 60 * 60;
        Properties.ASSERTION_TIMEOUT = 60 * 60;
        // Properties.TIMEOUT = Integer.MAX_VALUE;

        Properties.STRATEGY = Properties.Strategy.DSE;

        Properties.CRITERION = new Properties.Criterion[]{Properties.Criterion.BRANCH};

        Properties.MINIMIZE = true;
        Properties.ASSERTIONS = true;
    }

    /**
     * Runs DSE on a given SUT and checks for an expected amount of goals.
     *
     * @param expectedCoveredGoals
     * @param expectedNotCoveredGoals
     * @param SUT
     */
    protected void testDSEExecution(int expectedCoveredGoals, int expectedNotCoveredGoals, Class SUT) {
        TestSuiteChromosome generatedTestSuite = generateTestSuiteUsingDSE(SUT);

        assertEquals(expectedCoveredGoals, generatedTestSuite.getNumOfCoveredGoals());
        assertEquals(expectedNotCoveredGoals, generatedTestSuite.getNumOfNotCoveredGoals());
    }

    /**
     * Runs DSE on a given SUT and checks the total amount of goals and a lower bound for the covered goals.
     *
     * @param expectedGoals the total number of goals
     * @param minimumCoveredGoals the least number of goals that must be covered
     * @param SUT
     */
    protected void testDSEExecutionCoversAtLeast(int expectedGoals, int minimumCoveredGoals, Class SUT) {
        TestSuiteChromosome generatedTestSuite = generateTestSuiteUsingDSE(SUT);

        assertEquals(expectedGoals,
                generatedTestSuite.getNumOfCoveredGoals() + generatedTestSuite.getNumOfNotCoveredGoals());
        assertTrue(generatedTestSuite.getNumOfCoveredGoals() >= minimumCoveredGoals);
    }

    private TestSuiteChromosome generateTestSuiteUsingDSE(Class SUT) {
        Object result = executeDSE(SUT);
        ExplorationAlgorithmBase dse = getDSEAFromResult(result);
        TestSuiteChromosome generatedTestSuite = dse.getGeneratedTestSuite();
        System.out.println("Generated Test Suite:\n" + generatedTestSuite);

        assertFalse(generatedTestSuite.getTests().isEmpty());
        return generatedTestSuite;
    }

    /**
     * Runs DSE on a given SUT and checks that the generation fails
     *
     * @param SUT
     */
    protected void testDSEExecutionFailure(Class SUT) {
        Object result = executeDSE(SUT);
        checkDSEGenerationFailed(result);
    }

    /**
     * Runs DSE on a given SUT.
     *
     * @param SUT
     */
    private Object executeDSE(Class SUT) {
        EvoSuite evosuite = new EvoSuite();
        String targetClass = SUT.getCanonicalName();
        Properties.TARGET_CLASS = targetClass;
        Properties.SHOW_PROGRESS = true;

        String[] command = new String[]{"-generateSuiteUsingDSE", "-class", targetClass};
        return evosuite.parseCommandLine(command);
    }
}