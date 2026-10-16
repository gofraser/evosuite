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
package org.evosuite.runtime.mock.java.util.concurrent;

import org.evosuite.runtime.RuntimeSettings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MockThreadPoolExecutorTest {

    private int originalMaxNumberOfThreads;

    @Before
    public void setUp() {
        originalMaxNumberOfThreads = RuntimeSettings.maxNumberOfThreads;
    }

    @After
    public void tearDown() {
        RuntimeSettings.maxNumberOfThreads = originalMaxNumberOfThreads;
    }

    @Test
    public void testHugeCoreAndMaxPoolSizeAreClamped() {
        RuntimeSettings.maxNumberOfThreads = 100;

        MockThreadPoolExecutor executor = new MockThreadPoolExecutor(2_145_463_437, 2_145_463_437, 0L,
                TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            assertEquals(100, executor.getCorePoolSize());
            assertEquals(100, executor.getMaximumPoolSize());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testSmallPoolIsUntouched() {
        RuntimeSettings.maxNumberOfThreads = 100;

        MockThreadPoolExecutor executor = new MockThreadPoolExecutor(2, 4, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(10));
        try {
            assertEquals(2, executor.getCorePoolSize());
            assertEquals(4, executor.getMaximumPoolSize());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testClampNeverMakesCoreExceedMaximum() {
        RuntimeSettings.maxNumberOfThreads = 100;

        // corePoolSize below the cap, maximumPoolSize far above it: after clamping,
        // corePoolSize (50) must still be <= maximumPoolSize (100).
        MockThreadPoolExecutor executor = new MockThreadPoolExecutor(50, 2_000_000_000, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        try {
            assertTrue(executor.getCorePoolSize() <= executor.getMaximumPoolSize());
            assertEquals(50, executor.getCorePoolSize());
            assertEquals(100, executor.getMaximumPoolSize());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testNegativeCorePoolSizeStillThrowsIllegalArgumentException() {
        try {
            new MockThreadPoolExecutor(-1, 4, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // exactly what the real JDK constructor throws for a negative corePoolSize
        }
    }

    @Test
    public void testCoreExceedingMaximumStillThrowsEvenWhenBothClampUnderTheCap() {
        RuntimeSettings.maxNumberOfThreads = 4;

        // core=10, max=5 violates corePoolSize <= maximumPoolSize, but clamping each bound
        // independently to the cap of 4 would turn both into 4 and hide that: the real
        // constructor must still see the original numbers and reject them.
        try {
            new MockThreadPoolExecutor(10, 5, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // exactly what the real JDK constructor throws for corePoolSize > maximumPoolSize
        }
    }

    @Test
    public void testZeroMaximumPoolSizeStillThrowsIllegalArgumentException() {
        try {
            new MockThreadPoolExecutor(0, 0, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // real JDK requires maximumPoolSize > 0
        }
    }

    @Test
    public void testHugeScheduledCorePoolSizeIsClamped() {
        RuntimeSettings.maxNumberOfThreads = 100;

        MockScheduledThreadPoolExecutor executor = new MockScheduledThreadPoolExecutor(1724);
        try {
            assertEquals(100, executor.getCorePoolSize());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testSmallScheduledCorePoolSizeIsUntouched() {
        RuntimeSettings.maxNumberOfThreads = 100;

        MockScheduledThreadPoolExecutor executor = new MockScheduledThreadPoolExecutor(4);
        try {
            assertEquals(4, executor.getCorePoolSize());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testNegativeScheduledCorePoolSizeStillThrows() {
        try {
            new MockScheduledThreadPoolExecutor(-1);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // exactly what the real JDK constructor throws for a negative corePoolSize
        }
    }
}
