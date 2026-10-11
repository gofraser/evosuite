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
package org.evosuite.runtime.mock.java.lang;

import org.evosuite.runtime.TooManyResourcesException;
import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.java.io.MockIOException;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A cause set with initCause while mocking is on must still be the exception's cause once
 * mocking is off. The generated tests ask for it then: the rule that ignores a test aborted
 * by a resource limit looks for the limit in the cause chain after the test has ended, and
 * Jackson's JsonMappingException is built with initCause.
 */
public class MockExceptionCauseTest {

    @After
    public void tearDown() {
        MockFramework.disable();
    }

    private static void assertCauseSurvivesDisabling(Throwable mock, Throwable cause) {
        MockFramework.enable();
        Throwable returned = mock.initCause(cause);
        assertSame("initCause returns the exception", mock, returned);
        assertSame(cause, mock.getCause());

        MockFramework.disable();
        assertSame(cause, mock.getCause());
        assertTrue(TooManyResourcesException.isOrWraps(mock));
    }

    @Test
    public void ioExceptionKeepsCause() {
        assertCauseSurvivesDisabling(new MockIOException("wrapper"), new TooManyResourcesException("limit"));
    }

    @Test
    public void exceptionKeepsCause() {
        assertCauseSurvivesDisabling(new MockException("wrapper"), new TooManyResourcesException("limit"));
    }

    @Test
    public void runtimeExceptionKeepsCause() {
        assertCauseSurvivesDisabling(new MockRuntimeException("wrapper"), new TooManyResourcesException("limit"));
    }

    @Test
    public void errorKeepsCause() {
        assertCauseSurvivesDisabling(new MockError("wrapper"), new TooManyResourcesException("limit"));
    }

    @Test
    public void illegalStateExceptionKeepsCause() {
        assertCauseSurvivesDisabling(new MockIllegalStateException("wrapper"), new TooManyResourcesException("limit"));
    }

    @Test
    public void illegalArgumentExceptionKeepsCause() {
        assertCauseSurvivesDisabling(new MockIllegalArgumentException("wrapper"), new TooManyResourcesException("limit"));
    }

    @Test
    public void nullPointerExceptionKeepsCause() {
        assertCauseSurvivesDisabling(new MockNullPointerException("wrapper"), new TooManyResourcesException("limit"));
    }

    @Test
    public void arithmeticExceptionKeepsCause() {
        assertCauseSurvivesDisabling(new MockArithmeticException("wrapper"), new TooManyResourcesException("limit"));
    }

    @Test
    public void arrayIndexOutOfBoundsExceptionKeepsCause() {
        assertCauseSurvivesDisabling(new MockArrayIndexOutOfBoundsException("wrapper"),
                new TooManyResourcesException("limit"));
    }

    @Test
    public void illegalAccessExceptionKeepsCause() {
        assertCauseSurvivesDisabling(new MockIllegalAccessException("wrapper"), new TooManyResourcesException("limit"));
    }
}
