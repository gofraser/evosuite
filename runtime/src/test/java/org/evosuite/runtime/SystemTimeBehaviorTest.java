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
    public void shouldIncreaseMockedCurrentTimeMillisDeterministically() {
        org.evosuite.runtime.System.resetRuntime();

        long t1 = org.evosuite.runtime.System.currentTimeMillis();
        long t2 = org.evosuite.runtime.System.currentTimeMillis();
        long t3 = org.evosuite.runtime.System.currentTimeMillis();

        Assertions.assertEquals(t1 + 1L, t2);
        Assertions.assertEquals(t2 + 1L, t3);
        Assertions.assertTrue(org.evosuite.runtime.System.wasTimeAccessed());
    }

    @Test
    public void shouldIncreaseMockedNanoTimeDeterministically() {
        org.evosuite.runtime.System.resetRuntime();

        long n1 = org.evosuite.runtime.System.nanoTime();
        long n2 = org.evosuite.runtime.System.nanoTime();
        long n3 = org.evosuite.runtime.System.nanoTime();

        Assertions.assertEquals(1000L, n2 - n1);
        Assertions.assertEquals(1000L, n3 - n2);
        Assertions.assertTrue(org.evosuite.runtime.System.wasTimeAccessed());
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
    public void shouldIgnoreBackwardSetCurrentTimeMillis() {
        org.evosuite.runtime.System.resetRuntime();

        long baseline = org.evosuite.runtime.System.currentTimeMillis();

        org.evosuite.runtime.System.setCurrentTimeMillis(0L);
        org.evosuite.runtime.System.setCurrentTimeMillis(-123L);

        long afterBackwardSet = org.evosuite.runtime.System.currentTimeMillis();
        Assertions.assertEquals(baseline + 1L, afterBackwardSet);

        org.evosuite.runtime.System.setCurrentTimeMillis(afterBackwardSet + 1000L);
        long afterForwardSet = org.evosuite.runtime.System.currentTimeMillis();
        Assertions.assertEquals(afterBackwardSet + 1000L, afterForwardSet);
    }
}
