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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.CharBuffer;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

public class MockInvocationLimitTest {

    private final int savedLimit = RuntimeSettings.maxMockInvocationsPerTest;

    @AfterEach
    public void restore() {
        RuntimeSettings.maxMockInvocationsPerTest = savedLimit;
    }

    private static Comparable<Object> rendered() {
        // exactly the shape EvoSuite renders
        @SuppressWarnings("unchecked")
        Comparable<Object> c = mock(Comparable.class, withSettings().stubOnly()
                .invocationListeners(new MockInvocationLimit())
                .defaultAnswer(new ViolatedAssumptionAnswer()));
        doReturn(0).when(c).compareTo(org.mockito.ArgumentMatchers.any());
        return c;
    }

    @Test
    public void stubbedCallsBeyondTheLimitThrow() {
        RuntimeSettings.maxMockInvocationsPerTest = 6; // the doReturn stub call counts as one
        Comparable<Object> c = rendered();
        for (int i = 0; i < 5; i++) {
            assertEquals(0, c.compareTo(null));
        }
        assertLimit(() -> c.compareTo(null));
    }

    /** Mockito wraps what a listener throws, so the limit arrives as a wrapped exception. */
    private static void assertLimit(org.junit.jupiter.api.function.Executable e) {
        Throwable t = assertThrows(RuntimeException.class, e);
        assertTrue(TooManyResourcesException.isOrWraps(t), String.valueOf(t));
    }

    @Test
    public void ruleAndExtensionSwallowTheWrappedLimit() throws Throwable {
        RuntimeSettings.maxMockInvocationsPerTest = 4;
        Comparable<Object> c = rendered();
        c.compareTo(null);
        c.compareTo(null);
        c.compareTo(null);
        org.junit.runners.model.Statement body = new org.junit.runners.model.Statement() {
            @Override
            public void evaluate() {
                c.compareTo(null);
            }
        };
        new org.evosuite.runtime.vnet.NonFunctionalRequirementRule().apply(body, null).evaluate();
        Throwable wrapped = assertThrows(RuntimeException.class, () -> c.compareTo(null));
        new org.evosuite.runtime.vnet.NonFunctionalRequirementExtension()
                .handleTestExecutionException(null, wrapped);
        assertThrows(IllegalStateException.class, () -> new org.evosuite.runtime.vnet
                .NonFunctionalRequirementExtension()
                .handleTestExecutionException(null, new IllegalStateException()));
    }

    @Test
    public void countIsPerMock() {
        RuntimeSettings.maxMockInvocationsPerTest = 4;
        Comparable<Object> a = rendered();
        Comparable<Object> b = rendered();
        for (int i = 0; i < 3; i++) {
            a.compareTo(null);
            b.compareTo(null);
        }
        assertLimit(() -> a.compareTo(null));
    }

    @Test
    public void scannerOverReadableThatReturnsZeroTerminates() throws Exception {
        RuntimeSettings.maxMockInvocationsPerTest = 10_000;
        Readable r = mock(Readable.class, withSettings().stubOnly()
                .invocationListeners(new MockInvocationLimit())
                .defaultAnswer(new ViolatedAssumptionAnswer()));
        doReturn(0, 0, 0, 0, 0).when(r).read(nullable(CharBuffer.class));
        long start = System.nanoTime();
        assertLimit(() -> new Scanner(r).nextLine());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 10_000);
    }
}
