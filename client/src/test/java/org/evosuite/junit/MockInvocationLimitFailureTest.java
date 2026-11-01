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
        assertTrue(JUnitFailurePolicy.isMockInvocationLimitFailure(
                failure("org.mockito.exceptions.base.MockitoException", WRAPPED)));
        assertTrue(JUnitFailurePolicy.isMockInvocationLimitFailure(failure(
                "shaded.org.evosuite.shaded.org.mockito.exceptions.base.MockitoException", WRAPPED)));
    }

    @Test
    public void otherMockitoFailuresAreNot() {
        assertFalse(JUnitFailurePolicy.isMockInvocationLimitFailure(
                failure("org.mockito.exceptions.base.MockitoException", "Cannot mock final class")));
        assertFalse(JUnitFailurePolicy.isMockInvocationLimitFailure(
                failure("java.lang.IllegalStateException", WRAPPED)));
    }
}
