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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class MockThreadTest {

    @Before
    public void setUp() {
        MockThread.reset();
        org.evosuite.runtime.System.resetRuntime();
    }

    @After
    public void tearDown() {
        MockThread.reset();
        org.evosuite.runtime.System.resetRuntime();
    }

    @Test
    public void testSleepAdvancesMockedClock() throws InterruptedException {
        long before = org.evosuite.runtime.System.currentTimeMillis();
        MockThread.sleep(500L);
        long after = org.evosuite.runtime.System.currentTimeMillis();

        assertEquals(before + 500L, after);
    }

    @Test
    public void testSleepDoesNotReallySleep() throws InterruptedException {
        long start = java.lang.System.currentTimeMillis();
        // Requesting a nominally long sleep should return in (real) time close
        // to instantly, since MockThread no longer really sleeps.
        MockThread.sleep(60_000L);
        long elapsed = java.lang.System.currentTimeMillis() - start;

        assertTrue("Expected near-instant return, but took " + elapsed + "ms", elapsed < 1000);
    }

    @Test
    public void testLoopOfTenSleepsPasses() throws InterruptedException {
        long before = org.evosuite.runtime.System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            MockThread.sleep(500L);
        }
        long after = org.evosuite.runtime.System.currentTimeMillis();

        assertEquals(before + 5000L, after);
    }

    @Test
    public void testCallCapEnforced() {
        boolean exceededCap = false;
        try {
            // MAX_SLEEP_CALLS_PER_TEST is 10_000; try well beyond it.
            for (int i = 0; i < 10_005; i++) {
                MockThread.sleep(1L);
            }
        } catch (TooManyResourcesException e) {
            exceededCap = true;
        } catch (InterruptedException e) {
            fail("Unexpected InterruptedException");
        }
        assertTrue("Expected TooManyResourcesException after exceeding the sleep-call cap", exceededCap);
    }

    /**
     * An uninstrumented polling loop on the test thread (HSQLDB's Server.start())
     * runs into the call cap on every test evaluation. Paying a full real
     * millisecond for each of the 10,000 calls made such tests last 7-13 s and
     * starved the search of generations.
     */
    @Test(timeout = 20_000)
    public void testTestThreadPollingLoopReachesCapQuickly() {
        long start = System.nanoTime();
        boolean exceededCap = false;
        try {
            while (true) {
                MockThread.sleep(100L);
            }
        } catch (TooManyResourcesException e) {
            exceededCap = true;
        } catch (InterruptedException e) {
            fail("Unexpected InterruptedException");
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(exceededCap);
        assertTrue("Reaching the cap took " + elapsedMs + " ms", elapsedMs < 3_000L);
    }

    /**
     * The test's own thread only yields in a mocked sleep(). 200 ms of real sleeping per test
     * (the first calls) made every evaluation of a test with a polling loop last that long
     * and the search saw a handful of generations.
     */
    @Test
    public void testTestThreadSleepDoesNotPause() throws InterruptedException {
        long start = System.nanoTime();
        for (int i = 0; i < 40; i++) {
            MockThread.sleep(10L);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue("40 sleep() calls took " + elapsedMs + " ms", elapsedMs < 100L);
    }

    @Test
    public void testTestThreadSleepObservesInterrupt() {
        Thread.currentThread().interrupt();
        try {
            MockThread.sleep(10L);
            fail("Expected InterruptedException");
        } catch (InterruptedException expected) {
            // the interrupt was observed, and cleared
            assertFalse(Thread.currentThread().isInterrupted());
        }
    }

    @Test
    public void testResetClearsCallCap()throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            MockThread.sleep(1L);
        }
        MockThread.reset();
        // Should not throw — the call count budget was reset.
        MockThread.sleep(1L);
    }

    @Test
    public void testSleepWithNanosOverloadAdvancesClock() throws InterruptedException {
        long before = org.evosuite.runtime.System.currentTimeMillis();
        MockThread.sleep(250L, 999_999);
        long after = org.evosuite.runtime.System.currentTimeMillis();

        assertEquals(before + 250L, after);
    }
}
