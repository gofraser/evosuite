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
package org.evosuite.runtime;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.PropertyPermission;

public class SystemTimeBehaviorTest {

    @Test
    public void shouldRestoreCapturedPropertyReads() {
        org.evosuite.runtime.System.fullReset();
        try {
            org.evosuite.runtime.System.handlePropertyPermission(
                    new PropertyPermission("evosuite.first", "read"));
            java.util.Set<String> snapshot =
                    org.evosuite.runtime.System.getAllPropertiesReadSoFar();
            org.evosuite.runtime.System.handlePropertyPermission(
                    new PropertyPermission("evosuite.auxiliary", "read"));

            org.evosuite.runtime.System.restorePropertiesReadSoFar(snapshot);

            Assertions.assertEquals(
                    Collections.singleton("evosuite.first"),
                    org.evosuite.runtime.System.getAllPropertiesReadSoFar());
        } finally {
            org.evosuite.runtime.System.fullReset();
        }
    }

    @Test
    public void shouldReturnSameMockedCurrentTimeMillisOnConsecutiveReads() {
        org.evosuite.runtime.System.resetRuntime();

        long t1 = org.evosuite.runtime.System.currentTimeMillis();
        long t2 = org.evosuite.runtime.System.currentTimeMillis();
        long t3 = org.evosuite.runtime.System.currentTimeMillis();

        Assertions.assertEquals(t1, t2);
        Assertions.assertEquals(t2, t3);
        Assertions.assertTrue(org.evosuite.runtime.System.wasTimeAccessed());
    }

    @Test
    public void shouldReturnSameMockedNanoTimeOnConsecutiveReads() {
        org.evosuite.runtime.System.resetRuntime();

        long n1 = org.evosuite.runtime.System.nanoTime();
        long n2 = org.evosuite.runtime.System.nanoTime();
        long n3 = org.evosuite.runtime.System.nanoTime();

        Assertions.assertEquals(n1, n2);
        Assertions.assertEquals(n2, n3);
        Assertions.assertTrue(org.evosuite.runtime.System.wasTimeAccessed());
    }

    @Test
    public void shouldAdvanceMockedCurrentTimeMillisExplicitly() {
        org.evosuite.runtime.System.resetRuntime();

        long before = org.evosuite.runtime.System.currentTimeMillis();
        org.evosuite.runtime.System.advanceCurrentTimeMillis(500L);
        long after = org.evosuite.runtime.System.currentTimeMillis();

        Assertions.assertEquals(before + 500L, after);
    }

    @Test
    public void shouldIgnoreNonPositiveAdvance() {
        org.evosuite.runtime.System.resetRuntime();

        long before = org.evosuite.runtime.System.currentTimeMillis();
        org.evosuite.runtime.System.advanceCurrentTimeMillis(0L);
        org.evosuite.runtime.System.advanceCurrentTimeMillis(-100L);
        long after = org.evosuite.runtime.System.currentTimeMillis();

        Assertions.assertEquals(before, after);
    }

    @Test
    public void shouldResetMockedTimeToStableStartValue() {
        org.evosuite.runtime.System.resetRuntime();
        long baseline = org.evosuite.runtime.System.currentTimeMillis();

        org.evosuite.runtime.System.currentTimeMillis();
        org.evosuite.runtime.System.currentTimeMillis();

        org.evosuite.runtime.System.resetRuntime();
        long afterReset = org.evosuite.runtime.System.currentTimeMillis();

        Assertions.assertEquals(baseline, afterReset);
    }

    @Test
    public void shouldSetCurrentTimeMillisToAnyValue() {
        org.evosuite.runtime.System.resetRuntime();

        // A test seeds the clock; a SUT comparing against a small constant needs it
        // to go below the starting value as well as above.
        org.evosuite.runtime.System.setCurrentTimeMillis(100L);
        Assertions.assertEquals(100L, org.evosuite.runtime.System.currentTimeMillis());

        org.evosuite.runtime.System.setCurrentTimeMillis(-123L);
        Assertions.assertEquals(-123L, org.evosuite.runtime.System.currentTimeMillis());

        org.evosuite.runtime.System.setCurrentTimeMillis(5000L);
        Assertions.assertEquals(5000L, org.evosuite.runtime.System.currentTimeMillis());
    }
}
