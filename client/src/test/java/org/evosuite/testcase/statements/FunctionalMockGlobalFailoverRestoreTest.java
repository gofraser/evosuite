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
package org.evosuite.testcase.statements;

import org.evosuite.Properties;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two owners switch functional mocking off by writing the same Properties: the executor,
 * temporarily, after a streak of timeouts, and the GLOBAL mock failover, permanently. When the
 * failover fires during a timeout streak, the executor's later restore must not switch mocking
 * back on, or new mocks are generated that the disabled failover then fails at execution.
 */
public class FunctionalMockGlobalFailoverRestoreTest {

    private Properties.FunctionalMockingFailoverMode failoverMode;
    private int failureThresholdCount;
    private double failureThresholdRatio;
    private double pFunctionalMocking;
    private boolean mockIfNoGenerator;

    public static class Sleeper {
        public static void sleepLong() throws InterruptedException {
            Thread.sleep(30_000L);
        }
    }

    @BeforeEach
    public void setUp() {
        failoverMode = Properties.FUNCTIONAL_MOCKING_FAILOVER_MODE;
        failureThresholdCount = Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_COUNT;
        failureThresholdRatio = Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_RATIO;
        pFunctionalMocking = Properties.P_FUNCTIONAL_MOCKING;
        mockIfNoGenerator = Properties.MOCK_IF_NO_GENERATOR;
        FunctionalMockStatement.resetMockingFailoverStateForTests();
        TestCaseExecutor.getInstance().resetAdaptiveTimeoutState();
    }

    @AfterEach
    public void tearDown() {
        TestCaseExecutor.getInstance().resetAdaptiveTimeoutState();
        FunctionalMockStatement.resetMockingFailoverStateForTests();
        Properties.FUNCTIONAL_MOCKING_FAILOVER_MODE = failoverMode;
        Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_COUNT = failureThresholdCount;
        Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_RATIO = failureThresholdRatio;
        Properties.P_FUNCTIONAL_MOCKING = pFunctionalMocking;
        Properties.MOCK_IF_NO_GENERATOR = mockIfNoGenerator;
    }

    @Test
    public void executorRestoreDoesNotUndoTheGlobalFailover() throws Exception {
        Properties.FUNCTIONAL_MOCKING_FAILOVER_MODE = Properties.FunctionalMockingFailoverMode.GLOBAL;
        Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_COUNT = 1;
        Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_RATIO = 0.5;
        Properties.P_FUNCTIONAL_MOCKING = 0.7;
        Properties.MOCK_IF_NO_GENERATOR = true;
        TestCaseExecutor executor = TestCaseExecutor.getInstance();

        // A timeout streak: the next timeout makes the executor switch mocking off for now.
        Field streak = TestCaseExecutor.class.getDeclaredField("consecutiveTimeoutCount");
        streak.setAccessible(true);
        Field threshold = TestCaseExecutor.class.getDeclaredField("CONSECUTIVE_TIMEOUT_THRESHOLD");
        threshold.setAccessible(true);
        streak.setInt(executor, threshold.getInt(null) - 1);
        ExecutionResult timedOut = executor.execute(sleepingTest(), 300);
        assertTrue(timedOut.hasTimeout(), "precondition: the execution timed out");
        assertEquals(0.0, Properties.P_FUNCTIONAL_MOCKING, 0.0,
                "precondition: the executor switched mocking off after the streak");

        // During the streak, mocks keep failing and the GLOBAL failover disables mocking for good.
        FunctionalMockStatement.registerMockAttemptForTests();
        assertThrows(CodeUnderTestException.class, () ->
                FunctionalMockStatement.registerRecoverableMockFailureForTests(
                        "example.GlobalFail", new NoClassDefFoundError("missing")));
        assertTrue(FunctionalMockStatement.isFunctionalMockingGloballyDisabledForTests(),
                "precondition: the global failover fired");

        // The streak breaks: the executor restores what it saved.
        ExecutionResult quick = executor.execute(quickTest(), 5_000);
        assertFalse(quick.hasTimeout(), "precondition: the second execution finished");

        assertAll(
                () -> assertEquals(0.0, Properties.P_FUNCTIONAL_MOCKING, 0.0,
                        "functional mocking was switched back on although it is globally disabled"),
                () -> assertFalse(Properties.MOCK_IF_NO_GENERATOR,
                        "mock-if-no-generator was switched back on although mocking is globally disabled"));
    }

    private static DefaultTestCase sleepingTest() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new MethodStatement(tc,
                new GenericMethod(Sleeper.class.getMethod("sleepLong"), Sleeper.class), null,
                Collections.emptyList()));
        return tc;
    }

    private static DefaultTestCase quickTest() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 1));
        return tc;
    }
}
