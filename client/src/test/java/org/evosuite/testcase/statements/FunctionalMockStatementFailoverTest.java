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
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.variable.VariableReferenceImpl;
import org.evosuite.utils.generic.GenericClassFactory;
import org.mockito.MockSettings;
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
        FunctionalMockFailover.reset();
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
        assertTrue(FunctionalMockFailover.isCodeUnderTestInitializationFailure(chain));
    }

    @Test
    public void testClassFailoverBlacklistsOnlyFailingClass() {
        FunctionalMockFailover.reset();
        Properties.FUNCTIONAL_MOCKING_FAILOVER_MODE = Properties.FunctionalMockingFailoverMode.CLASS;
        Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_COUNT = 100;
        Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_RATIO = 1.0;

        FunctionalMockFailover.registerMockAttempt();
        assertThrows(CodeUnderTestException.class, () ->
                FunctionalMockFailover.handleRecoverableMockFailure(
                        "example.FailingClass", new ExceptionInInitializerError()));

        assertTrue(FunctionalMockFailover.isClassBlacklisted("example.FailingClass"));
        assertFalse(FunctionalMockFailover.isFunctionalMockingGloballyDisabled());
    }

    @Test
    public void testGlobalFailoverDisablesFunctionalMocking() {
        FunctionalMockFailover.reset();
        Properties.FUNCTIONAL_MOCKING_FAILOVER_MODE = Properties.FunctionalMockingFailoverMode.GLOBAL;
        Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_COUNT = 1;
        Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_RATIO = 0.5;
        Properties.P_FUNCTIONAL_MOCKING = 0.7;
        Properties.MOCK_IF_NO_GENERATOR = true;

        FunctionalMockFailover.registerMockAttempt();
        assertThrows(CodeUnderTestException.class, () ->
                FunctionalMockFailover.handleRecoverableMockFailure(
                        "example.GlobalFail", new NoClassDefFoundError("missing")));

        assertTrue(FunctionalMockFailover.isFunctionalMockingGloballyDisabled());
        assertEquals(0.0, Properties.P_FUNCTIONAL_MOCKING, 0.0);
        assertFalse(Properties.MOCK_IF_NO_GENERATOR);
    }

    @Test
    public void testFailoverOffDoesNotBlacklistOrDisableGlobally() {
        FunctionalMockFailover.reset();
        Properties.FUNCTIONAL_MOCKING_FAILOVER_MODE = Properties.FunctionalMockingFailoverMode.OFF;
        Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_COUNT = 1;
        Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_RATIO = 0.0;
        Properties.P_FUNCTIONAL_MOCKING = 0.6;
        Properties.MOCK_IF_NO_GENERATOR = true;

        FunctionalMockFailover.registerMockAttempt();
        assertThrows(CodeUnderTestException.class, () ->
                FunctionalMockFailover.handleRecoverableMockFailure(
                        "example.NoFailover", new LinkageError("broken-linkage")));

        assertFalse(FunctionalMockFailover.isClassBlacklisted("example.NoFailover"));
        assertFalse(FunctionalMockFailover.isFunctionalMockingGloballyDisabled());
        assertEquals(0.6, Properties.P_FUNCTIONAL_MOCKING, 0.0);
        assertTrue(Properties.MOCK_IF_NO_GENERATOR);
    }

    @Test
    public void testCauseChainRecognizesModuleAccessRefusal() {
        Throwable chain = new RuntimeException(
                new java.lang.reflect.InaccessibleObjectException("module java.base does not open"));
        assertTrue(FunctionalMockFailover.isMockAccessRefusal(chain));
        assertFalse(FunctionalMockFailover.isMockAccessRefusal(new IllegalStateException("other")));
    }

    @Test
    public void testModuleAccessRefusalFailsOnlyTheStatement() throws Exception {
        FunctionalMockFailover.reset();
        Properties.FUNCTIONAL_MOCKING_FAILOVER_MODE = Properties.FunctionalMockingFailoverMode.CLASS;

        TestCase tc = new DefaultTestCase();
        VariableReference ref = new VariableReferenceImpl(tc, java.util.List.class);
        FunctionalMockStatement stmt = new FunctionalMockStatement(tc, ref,
                GenericClassFactory.get(java.util.List.class)) {
            @Override
            protected MockSettings createMockSettings() {
                throw new java.lang.reflect.InaccessibleObjectException(
                        "Unable to make public java.lang.Object() accessible");
            }
        };
        tc.addStatement(stmt);

        // The refusal is reported as an exception of the statement, not as an EvosuiteError
        // that would abort the search.
        Throwable result = stmt.execute(new Scope(), System.out);

        assertNotNull(result);
        assertTrue(FunctionalMockFailover.isClassBlacklisted("java.util.List"));
    }
}
