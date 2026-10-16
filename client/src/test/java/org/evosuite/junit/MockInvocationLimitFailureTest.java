package org.evosuite.junit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JUnit check keeps a test that ended on the mock invocation limit, like a loop-cap abort. */
public class MockInvocationLimitFailureTest {

    private static final String WRAPPED = "\nThe invocation listener with type "
            + "shaded.org.evosuite.runtime.MockInvocationLimit\nthrew an exception : "
            + "shaded.org.evosuite.runtime.TooManyResourcesExceptionMock invocation limit exceeded (10000)";

    private static JUnitFailure failure(String cls, String message) {
        return new JUnitFailure(message, cls, "test0", false, "");
    }

    @Test
    public void wrappedLimitIsRecognisedPlainAndShaded() {
        assertTrue(JUnitAnalyzer.isMockInvocationLimitFailure(
                failure("org.mockito.exceptions.base.MockitoException", WRAPPED)));
        assertTrue(JUnitAnalyzer.isMockInvocationLimitFailure(failure(
                "shaded.org.evosuite.shaded.org.mockito.exceptions.base.MockitoException", WRAPPED)));
    }

    @Test
    public void otherMockitoFailuresAreNot() {
        assertFalse(JUnitAnalyzer.isMockInvocationLimitFailure(
                failure("org.mockito.exceptions.base.MockitoException", "Cannot mock final class")));
        assertFalse(JUnitAnalyzer.isMockInvocationLimitFailure(
                failure("java.lang.IllegalStateException", WRAPPED)));
    }
}
