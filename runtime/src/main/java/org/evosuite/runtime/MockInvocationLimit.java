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
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.runtime;

import org.mockito.listeners.InvocationListener;
import org.mockito.listeners.MethodInvocationReport;

/**
 * Invocation listener attached to every mock in a generated test. It reproduces the guard that
 * the search applies to mocks ({@code EvoInvocationListener}): a mock that is called more than
 * {@link RuntimeSettings#maxMockInvocationsPerTest} times makes the test abort with a
 * {@link TooManyResourcesException}.
 *
 * <p>Without it, SUT code that loops over a mock inside the JDK (for instance a
 * {@code Scanner} over a {@code Readable} whose {@code read} keeps returning 0) cannot be
 * stopped: no loop counter applies there and the thread interrupt is ignored.
 *
 * <p>Each mock owns its listener and is created inside the test, so the count is per mock and
 * per test, exactly as during the search. A {@code doReturn(..).when(mock)} stub is also
 * reported to the listener, although it never reaches the default answer.
 */
public class MockInvocationLimit implements InvocationListener {

    private int invocations = 0;

    @Override
    public void reportInvocation(MethodInvocationReport report) {
        int max = Math.max(1, RuntimeSettings.maxMockInvocationsPerTest);
        if (++invocations > max) {
            throw new TooManyResourcesException("Mock invocation limit exceeded (" + max
                    + ") - likely an infinite loop calling a mocked method");
        }
    }
}
