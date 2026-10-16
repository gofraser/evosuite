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
package org.evosuite.testcase.execution;

import org.evosuite.runtime.ArrayAllocationBudget;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;

/**
 * The cumulative array budget belongs to one test. Class initialisers that run between tests
 * (static field re-initialisation, the search inspecting a class's annotations) must not be
 * charged against the total of the test that just finished.
 */
public class TestCaseExecutorArrayBudgetTest {

    private static final long LIMIT = 1_000_000L;

    private final long originalLimit = RuntimeSettings.maxCumulativeArrayBytes;

    @AfterEach
    public void restore() {
        RuntimeSettings.maxCumulativeArrayBytes = originalLimit;
        ArrayAllocationBudget.reset();
    }

    /** Code under test whose arrays use most of the budget. */
    public static void allocateMostOfTheBudget() {
        ArrayAllocationBudget.checkAndRecord(LIMIT * 3 / 4, 1);
    }

    @Test
    public void aFinishedTestLeavesNoChargeForWhatRunsBeforeTheNextOne() throws Exception {
        RuntimeSettings.maxCumulativeArrayBytes = LIMIT;
        DefaultTestCase testCase = new DefaultTestCase();
        testCase.addStatement(new MethodStatement(testCase,
                new GenericMethod(TestCaseExecutorArrayBudgetTest.class
                        .getMethod("allocateMostOfTheBudget"), TestCaseExecutorArrayBudgetTest.class),
                null, Collections.emptyList()));

        ExecutionResult result = TestCaseExecutor.runTest(testCase);
        Assertions.assertTrue(result.noThrownExceptions());

        // eg a class initialiser run while the search inspects a class
        Assertions.assertDoesNotThrow(() -> ArrayAllocationBudget.checkAndRecord(LIMIT / 2, 1));
    }
}
