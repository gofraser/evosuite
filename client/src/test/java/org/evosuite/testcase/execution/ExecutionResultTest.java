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

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ExecutionResultTest {

    @Test
    public void testOverflowExceptionPositionIsNormalizedToTerminalSlot() {
        DefaultTestCase testCase = new DefaultTestCase();
        testCase.addStatement(new StringPrimitiveStatement(testCase, "seed"));

        ExecutionResult result = new ExecutionResult(testCase, null);
        RuntimeException overflow = new RuntimeException("overflow");

        result.reportNewThrownException(testCase.size() + 6, overflow);

        Assertions.assertEquals(Integer.valueOf(testCase.size()), result.getFirstPositionOfThrownException());
        Assertions.assertSame(overflow, result.getExceptionThrownAtPosition(testCase.size()));
        Assertions.assertFalse(result.hasUndeclaredException());
    }

    @Test
    public void testTimeoutKeepsTerminalSlotWhenOverflowWasRecordedFirst() {
        DefaultTestCase testCase = new DefaultTestCase();
        testCase.addStatement(new StringPrimitiveStatement(testCase, "seed"));

        ExecutionResult result = new ExecutionResult(testCase, null);
        TestCaseExecutor.TimeoutExceeded timeout = new TestCaseExecutor.TimeoutExceeded(
                "timeout", null, testCase.size(), "seed");

        result.reportNewThrownException(testCase.size() + 4, new RuntimeException("overflow"));
        result.reportNewThrownException(testCase.size(), timeout);

        Assertions.assertTrue(result.hasTimeout());
        Assertions.assertSame(timeout, result.getExceptionThrownAtPosition(testCase.size()));
    }

    @Test
    public void testShrunkTestDoesNotCrashOnStaleExceptionPosition() {
        DefaultTestCase testCase = new DefaultTestCase();
        testCase.addStatement(new StringPrimitiveStatement(testCase, "first"));
        testCase.addStatement(new StringPrimitiveStatement(testCase, "second"));

        ExecutionResult result = new ExecutionResult(testCase, null);
        result.reportNewThrownException(1, new RuntimeException("stale"));

        testCase.chop(1);

        Assertions.assertDoesNotThrow(result::hasUndeclaredException);
        Assertions.assertFalse(result.hasUndeclaredException());
        Assertions.assertEquals(Integer.valueOf(testCase.size()), result.getFirstPositionOfThrownException());
    }

    @Test
    public void testShrunkTestNormalizesStaleTimeoutPositionToNewTerminalSlot() {
        DefaultTestCase testCase = new DefaultTestCase();
        for (int i = 0; i < 20; i++) {
            testCase.addStatement(new StringPrimitiveStatement(testCase, "s" + i));
        }

        ExecutionResult result = new ExecutionResult(testCase, null);
        TestCaseExecutor.TimeoutExceeded timeout = new TestCaseExecutor.TimeoutExceeded(
                "timeout", null, 19, "statement");

        result.reportNewThrownException(19, timeout);

        testCase.chop(16);

        Assertions.assertDoesNotThrow(result::hasUndeclaredException);
        Assertions.assertFalse(result.hasUndeclaredException());
        Assertions.assertTrue(result.hasTimeout());
        Assertions.assertEquals(Integer.valueOf(testCase.size()), result.getFirstPositionOfThrownException());
        Assertions.assertSame(timeout, result.getExceptionThrownAtPosition(testCase.size()));
    }

    @Test
    public void testHasTestExceptionDetectsDirectCodeUnderTestException() {
        DefaultTestCase testCase = new DefaultTestCase();
        testCase.addStatement(new StringPrimitiveStatement(testCase, "seed"));

        ExecutionResult result = new ExecutionResult(testCase, null);
        result.reportNewThrownException(0, new CodeUnderTestException(new NullPointerException("npe")));

        Assertions.assertTrue(result.hasTestException());
    }

    @Test
    public void testHasTestExceptionIgnoresWrappedCodeUnderTestException() {
        DefaultTestCase testCase = new DefaultTestCase();
        testCase.addStatement(new StringPrimitiveStatement(testCase, "seed"));

        ExecutionResult result = new ExecutionResult(testCase, null);
        RuntimeException wrapped = new RuntimeException(new CodeUnderTestException(new NullPointerException("npe")));
        result.reportNewThrownException(0, wrapped);

        Assertions.assertFalse(result.hasTestException());
    }

    @Test
    public void testHasTestExceptionUsesTerminatingExceptionNotSecondaryEntries() {
        DefaultTestCase testCase = new DefaultTestCase();
        testCase.addStatement(new StringPrimitiveStatement(testCase, "first"));
        testCase.addStatement(new StringPrimitiveStatement(testCase, "second"));

        ExecutionResult result = new ExecutionResult(testCase, null);
        result.reportNewThrownException(0, new NullPointerException("real-terminal"));
        result.reportNewThrownException(1, new CodeUnderTestException(new NullPointerException("secondary")));

        Assertions.assertFalse(result.hasTestException());
    }

    @Test
    public void testHasTestExceptionIgnoresDroppedInvalidCodeUnderTestEntry() {
        DefaultTestCase testCase = new DefaultTestCase();
        testCase.addStatement(new StringPrimitiveStatement(testCase, "seed"));

        ExecutionResult result = new ExecutionResult(testCase, null);
        result.reportNewThrownException(0, new NullPointerException("real-terminal"));
        result.reportNewThrownException(-1, new CodeUnderTestException(new NullPointerException("invalid")));

        Assertions.assertFalse(result.hasTestException());
    }
}
