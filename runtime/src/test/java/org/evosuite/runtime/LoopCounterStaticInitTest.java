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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Throwing out of a &lt;clinit&gt; makes the JVM mark the class erroneous forever, so
 * {@link LoopCounter#checkLoop(int)} must not turn a mere thread interrupt into a
 * {@link TooManyResourcesException} while a static initializer is on the stack.
 * A genuinely runaway static initializer must still be stopped by the iteration limit.
 */
public class LoopCounterStaticInitTest {

    /**
     * Loop index used by the probe classes below. Set by the test before the probe's
     * static initializer is triggered.
     */
    private static volatile int probeIndex;

    private boolean loopCounterState;
    private long maxIterations;

    @BeforeEach
    public void init() {
        Thread.interrupted(); // make sure we start with a clean interrupt flag
        loopCounterState = LoopCounter.getInstance().isActivated();
        maxIterations = RuntimeSettings.maxNumberOfIterationsPerLoop;
        LoopCounter.getInstance().setDelegate(null);
        LoopCounter.getInstance().reset();
        LoopCounter.getInstance().setActive(true);
    }

    @AfterEach
    public void tearDown() {
        Thread.interrupted(); // do not leak the interrupt flag into the next test
        LoopCounter.getInstance().setDelegate(null);
        LoopCounter.getInstance().reset();
        LoopCounter.getInstance().setActive(loopCounterState);
        RuntimeSettings.maxNumberOfIterationsPerLoop = maxIterations;
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testInterruptOutsideStaticInitStillTerminatesLoop() {
        int index = LoopCounter.getInstance().getNewIndex();
        Thread.currentThread().interrupt();

        assertThrows(TooManyResourcesException.class,
                () -> LoopCounter.getInstance().checkLoop(index));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testInterruptInsideStaticInitDoesNotThrow() {
        probeIndex = LoopCounter.getInstance().getNewIndex();

        // Triggers InterruptedClinitProbe.<clinit>
        assertFalse(InterruptedClinitProbe.threw,
                "checkLoop must not throw on a bare interrupt inside a static initializer");
        assertEquals(InterruptedClinitProbe.ITERATIONS, InterruptedClinitProbe.completed);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void testInterruptedStaticInitIsStillTerminatedByIterationLimit() {
        RuntimeSettings.maxNumberOfIterationsPerLoop = 5;
        probeIndex = LoopCounter.getInstance().getNewIndex();

        // Triggers CountLimitClinitProbe.<clinit>
        assertTrue(CountLimitClinitProbe.threw,
                "the 10x clinit iteration limit must still terminate a runaway static initializer");
        // The plain limit is 5, the clinit limit 10x that: we must get past the former
        // and stop well before the probe's own upper bound.
        assertTrue(CountLimitClinitProbe.completed >= 5,
                "should have been allowed past the normal limit, was " + CountLimitClinitProbe.completed);
        assertTrue(CountLimitClinitProbe.completed < CountLimitClinitProbe.MAX_ITERATIONS,
                "should have been stopped by the clinit limit, was " + CountLimitClinitProbe.completed);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testDelegateFastPathThrowsOnInterruptOutsideStaticInit() {
        RecordingDelegate delegate = new RecordingDelegate();
        LoopCounter.getInstance().setDelegate(delegate);
        Thread.currentThread().interrupt();

        assertThrows(TooManyResourcesException.class,
                () -> LoopCounter.getInstance().checkLoop(0));
        assertEquals(0, delegate.calls, "should have thrown before reaching the delegate");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testDelegateFastPathDoesNotThrowInsideStaticInit() {
        RecordingDelegate delegate = new RecordingDelegate();
        LoopCounter.getInstance().setDelegate(delegate);
        probeIndex = 0;

        // Triggers DelegatedClinitProbe.<clinit>
        DelegatedClinitProbe.touch();

        assertFalse(DelegatedClinitProbe.threw,
                "the delegate fast path must not throw on a bare interrupt inside a static initializer");
        assertEquals(DelegatedClinitProbe.ITERATIONS, delegate.calls,
                "every call should have been passed on to the delegate");
    }

    /**
     * Static initializer that interrupts itself and then hits a loop back-edge.
     * This is the shape of the original bug: a test times out, the executor interrupts
     * the thread, and the thread happens to be inside a &lt;clinit&gt;.
     */
    private static class InterruptedClinitProbe {

        static final int ITERATIONS = 100;

        static boolean threw = false;
        static int completed = 0;

        static {
            Thread.currentThread().interrupt();
            try {
                for (int i = 0; i < ITERATIONS; i++) {
                    LoopCounter.getInstance().checkLoop(probeIndex);
                    completed++;
                }
            } catch (TooManyResourcesException e) {
                // Recorded rather than propagated: letting it escape would poison this
                // very class, which is the failure mode under test.
                threw = true;
            }
        }
    }

    /**
     * Static initializer that never stops looping, to show that declining to throw on
     * the interrupt does not remove the iteration-limit escape hatch.
     */
    private static class CountLimitClinitProbe {

        static final int MAX_ITERATIONS = 100_000;

        static boolean threw = false;
        static int completed = 0;

        static {
            Thread.currentThread().interrupt();
            try {
                for (int i = 0; i < MAX_ITERATIONS; i++) {
                    LoopCounter.getInstance().checkLoop(probeIndex);
                    completed++;
                }
            } catch (TooManyResourcesException e) {
                threw = true;
            }
        }
    }

    /**
     * Same as {@link InterruptedClinitProbe}, but exercising the delegate fast path.
     */
    private static class DelegatedClinitProbe {

        static final int ITERATIONS = 10;

        static boolean threw = false;

        static {
            Thread.currentThread().interrupt();
            try {
                for (int i = 0; i < ITERATIONS; i++) {
                    LoopCounter.getInstance().checkLoop(probeIndex);
                }
            } catch (TooManyResourcesException e) {
                threw = true;
            }
        }

        static void touch() {
            // no-op: only here to trigger class initialization
        }
    }

    /**
     * Stand-in for the LoopCounter living in the classloader of the class under test.
     * Must be public, as it is called reflectively.
     */
    public static class RecordingDelegate {

        volatile int calls = 0;

        public void checkLoop(int index) {
            calls++;
        }

        public void reset() {
            calls = 0;
        }

        public void setActive(boolean active) {
            // no-op
        }

        public boolean isActivated() {
            return true;
        }

        public int getNewIndex() {
            return 0;
        }
    }
}
