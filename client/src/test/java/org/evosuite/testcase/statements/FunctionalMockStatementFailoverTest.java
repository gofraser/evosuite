/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testcase.statements;

import org.evosuite.Properties;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class FunctionalMockStatementFailoverTest {

    private static final Properties.FunctionalMockingFailoverMode DEFAULT_FAILOVER_MODE =
            Properties.FUNCTIONAL_MOCKING_FAILOVER_MODE;
    private static final int DEFAULT_FAILURE_THRESHOLD_COUNT =
            Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_COUNT;
    private static final double DEFAULT_FAILURE_THRESHOLD_RATIO =
            Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_RATIO;
    private static final double DEFAULT_P_FUNCTIONAL_MOCKING = Properties.P_FUNCTIONAL_MOCKING;
    private static final boolean DEFAULT_MOCK_IF_NO_GENERATOR = Properties.MOCK_IF_NO_GENERATOR;

    @AfterEach
    public void tearDown() {
        FunctionalMockStatement.resetMockingFailoverStateForTests();
        Properties.FUNCTIONAL_MOCKING_FAILOVER_MODE = DEFAULT_FAILOVER_MODE;
        Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_COUNT = DEFAULT_FAILURE_THRESHOLD_COUNT;
        Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_RATIO = DEFAULT_FAILURE_THRESHOLD_RATIO;
        Properties.P_FUNCTIONAL_MOCKING = DEFAULT_P_FUNCTIONAL_MOCKING;
        Properties.MOCK_IF_NO_GENERATOR = DEFAULT_MOCK_IF_NO_GENERATOR;
    }

    @Test
    public void testCauseChainRecognizesExceptionInInitializerError() {
        Throwable chain = new RuntimeException(
                new ExceptionInInitializerError(new NullPointerException("simulated")));
        assertTrue(FunctionalMockStatement.isCodeUnderTestInitializationFailureForTests(chain));
    }

    @Test
    public void testClassFailoverBlacklistsOnlyFailingClass() {
        FunctionalMockStatement.resetMockingFailoverStateForTests();
        Properties.FUNCTIONAL_MOCKING_FAILOVER_MODE = Properties.FunctionalMockingFailoverMode.CLASS;
        Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_COUNT = 100;
        Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_RATIO = 1.0;

        FunctionalMockStatement.registerMockAttemptForTests();
        assertThrows(CodeUnderTestException.class, () ->
                FunctionalMockStatement.registerRecoverableMockFailureForTests(
                        "example.FailingClass", new ExceptionInInitializerError()));

        assertTrue(FunctionalMockStatement.isClassBlacklistedForTests("example.FailingClass"));
        assertFalse(FunctionalMockStatement.isFunctionalMockingGloballyDisabledForTests());
    }

    @Test
    public void testGlobalFailoverDisablesFunctionalMocking() {
        FunctionalMockStatement.resetMockingFailoverStateForTests();
        Properties.FUNCTIONAL_MOCKING_FAILOVER_MODE = Properties.FunctionalMockingFailoverMode.GLOBAL;
        Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_COUNT = 1;
        Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_RATIO = 0.5;
        Properties.P_FUNCTIONAL_MOCKING = 0.7;
        Properties.MOCK_IF_NO_GENERATOR = true;

        FunctionalMockStatement.registerMockAttemptForTests();
        assertThrows(CodeUnderTestException.class, () ->
                FunctionalMockStatement.registerRecoverableMockFailureForTests(
                        "example.GlobalFail", new NoClassDefFoundError("missing")));

        assertTrue(FunctionalMockStatement.isFunctionalMockingGloballyDisabledForTests());
        assertEquals(0.0, Properties.P_FUNCTIONAL_MOCKING, 0.0);
        assertFalse(Properties.MOCK_IF_NO_GENERATOR);
    }

    @Test
    public void testFailoverOffDoesNotBlacklistOrDisableGlobally() {
        FunctionalMockStatement.resetMockingFailoverStateForTests();
        Properties.FUNCTIONAL_MOCKING_FAILOVER_MODE = Properties.FunctionalMockingFailoverMode.OFF;
        Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_COUNT = 1;
        Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_RATIO = 0.0;
        Properties.P_FUNCTIONAL_MOCKING = 0.6;
        Properties.MOCK_IF_NO_GENERATOR = true;

        FunctionalMockStatement.registerMockAttemptForTests();
        assertThrows(CodeUnderTestException.class, () ->
                FunctionalMockStatement.registerRecoverableMockFailureForTests(
                        "example.NoFailover", new LinkageError("broken-linkage")));

        assertFalse(FunctionalMockStatement.isClassBlacklistedForTests("example.NoFailover"));
        assertFalse(FunctionalMockStatement.isFunctionalMockingGloballyDisabledForTests());
        assertEquals(0.6, Properties.P_FUNCTIONAL_MOCKING, 0.0);
        assertTrue(Properties.MOCK_IF_NO_GENERATOR);
    }
}
