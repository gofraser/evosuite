package org.evosuite.coverage.exception;

import org.evosuite.Properties;
import org.evosuite.runtime.mock.OverrideMock;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ExceptionCoverageHelperTest {

    @Test
    public void testGetExceptionClassWithMock() {
        ExecutionResult result = mock(ExecutionResult.class);

        // Mock a normal exception
        Exception normalEx = new IllegalArgumentException();
        when(result.getExceptionThrownAtPosition(0)).thenReturn(normalEx);
        assertEquals(IllegalArgumentException.class, ExceptionCoverageHelper.getExceptionClass(result, 0));

        class MockException extends IllegalArgumentException implements OverrideMock {
             private static final long serialVersionUID = 1L;
        }

        MockException mockEx = new MockException();
        when(result.getExceptionThrownAtPosition(1)).thenReturn(mockEx);

        assertEquals(IllegalArgumentException.class, ExceptionCoverageHelper.getExceptionClass(result, 1));
    }

    @Test
    public void testIsSutExceptionSafe() throws Exception {
        String originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = null;

        try {
            ExecutionResult result = mock(ExecutionResult.class);
            result.test = mock(TestCase.class);
            MethodStatement ms = mock(MethodStatement.class);
            GenericMethod gm = mock(GenericMethod.class);
            Method m = Object.class.getMethod("toString");
            when(gm.getMethod()).thenReturn(m);
            when(ms.getMethod()).thenReturn(gm);
            when(result.test.getStatement(0)).thenReturn(ms);

            // This should NOT throw NPE and return false
            boolean isSut = ExceptionCoverageHelper.isSutException(result, 0);
            assertFalse(isSut);
        } finally {
            Properties.TARGET_CLASS = originalTargetClass;
        }
    }

    @Test
    public void testGetMethodIdentifierUnknown() {
        ExecutionResult result = mock(ExecutionResult.class);
        result.test = mock(TestCase.class);
        Statement st = mock(Statement.class); // Not MethodStatement or ConstructorStatement
        when(result.test.getStatement(0)).thenReturn(st);

        String id = ExceptionCoverageHelper.getMethodIdentifier(result, 0);
        // We will change this to return something else in implementation, but for now assert what we expect after change
        // Or if we strictly follow TDD, we assert "Unknown" and it fails.
        // Or we assert what currently is returned ("") and then change test after.
        // I will assert "Unknown" to confirm failure, then fix.
        assertEquals("Unknown", id);
    }
}
