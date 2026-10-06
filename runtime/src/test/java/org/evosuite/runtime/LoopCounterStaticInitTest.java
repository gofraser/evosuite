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
 * {@link LoopCounter#checkLoop(int)} must not throw a {@link TooManyResourcesException}
 * while a static initializer is on the stack -- neither for a mere thread interrupt nor
 * for exceeding the iteration limit. Terminating a genuinely runaway static initializer
 * is left to the time-gated guards that fire only once the test has already run out
 * of budget: the kill-switch probe in {@code ExecutionTracer.checkTimeout()}, the
 * timed-out-class tracking in {@code ClassReInitializeExecutor}, and the per-thread
 * deadline armed when {@code TestCaseExecutor} abandons a worker. Counting iterations
 * cannot tell a stuck initializer from a slow but terminating one, and the price of
 * guessing wrong is the whole class, for the rest of the run.
 */
public class LoopCounterStaticInitTest {

    /**
     * Loop index used by the probe classes below. Set by the test before the probe's
     * static initializer is triggered.
     */
    private static volatile int probeIndex;

    private static volatile boolean abandonedProbeEntered;

    private static volatile boolean abandonProbeStop;

    private static volatile int abandonedProbeTimeouts;

    private static volatile boolean abandonedProbeRepeatedTimeout;

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
    public void testStaticInitIsNotTerminatedByIterationLimit() {
        RuntimeSettings.maxNumberOfIterationsPerLoop = 5;
        probeIndex = LoopCounter.getInstance().getNewIndex();

        // Triggers CountLimitClinitProbe.<clinit>, which loops far past the limit set
        // above. Building a large lookup table in a static initializer is ordinary
        // code -- org.jmol.g3d.Normix3D needs more than 100,000 iterations -- and
        // stopping it would cost every class that reads from it, for the whole run.
        assertFalse(CountLimitClinitProbe.threw,
                "the iteration limit must not terminate a static initializer");
        assertEquals(CountLimitClinitProbe.MAX_ITERATIONS, CountLimitClinitProbe.completed,
                "the static initializer should have run to completion");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @SuppressWarnings("deprecation")
    public void testAbandonedStaticInitGetsBoundedEscape() throws InterruptedException {
        probeIndex = LoopCounter.getInstance().getNewIndex();
        abandonProbeStop = false;
        abandonedProbeTimeouts = 0;
        abandonedProbeRepeatedTimeout = false;
        Thread worker = new Thread(() -> {
            try {
                // A class literal does not initialize the class. Initialization
                // starts only when this worker asks Class.forName to resolve it.
                Class.forName(AbandonedClinitProbe.class.getName());
            } catch (Throwable ignored) {
                // Throwing out of an abandoned <clinit> is expected: the class is
                // already known to be stuck and must not retain its init lock.
            }
        }, "abandoned-clinit-probe");

        LoopCounter.getInstance().abandonStaticInitializer(worker);
        // The executor disables ordinary loop counting while waiting out a
        // static initializer. The abandonment guard must remain effective even
        // after that flag is cleared.
        LoopCounter.getInstance().setActive(false);
        // A subsequent test reset must not erase the abandoned worker's
        // deadline along with the shared loop counters.
        LoopCounter.getInstance().reset();
        worker.setDaemon(true);
        try {
            worker.start();
            worker.join(5000);

            assertFalse(worker.isAlive(),
                    "an abandoned static initializer must not run indefinitely");
            assertTrue(abandonedProbeEntered,
                    "the probe should have entered its static initializer");
            assertTrue(abandonedProbeRepeatedTimeout,
                    "the deadline must remain armed after a static initializer catches a timeout");
        } finally {
            // Keep a regression failure from leaking a non-terminating probe
            // into the rest of the runtime test JVM.
            if (worker.isAlive()) {
                abandonProbeStop = true;
                worker.interrupt();
                worker.join(1000);
            }
            if (worker.isAlive()) {
                worker.stop();
                worker.join(1000);
            }
        }
    }

    /**
     * Loop back-edges run on deep SUT stacks, so with nothing abandoned the probe
     * must be one volatile read and must not look the current thread up in the
     * deadline map.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testNothingArmedDoesNotConsultDeadlineMap() throws Exception {
        LoopCounter lc = LoopCounter.getInstance();
        // Drop entries left behind by earlier tests' dead workers.
        Thread dead = new Thread(() -> { });
        dead.start();
        dead.join();
        lc.protectFromReset(dead);

        java.lang.reflect.Field f = LoopCounter.class.getDeclaredField("abandonedStaticInitDeadlines");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<Thread, Long> original = (java.util.Map<Thread, Long>) f.get(lc);
        final java.util.concurrent.atomic.AtomicInteger lookups =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.Map<Thread, Long> counting = new java.util.concurrent.ConcurrentHashMap<Thread, Long>() {
            @Override
            public Long get(Object key) {
                lookups.incrementAndGet();
                return super.get(key);
            }
        };
        f.set(lc, counting);
        try {
            int index = lc.getNewIndex();
            for (int i = 0; i < 100; i++) {
                lc.checkLoop(index);
            }
            assertFalse(lc.shouldTerminateAbandonedStaticInitializer());
            assertEquals(0, lookups.get(),
                    "the deadline map must not be read while no deadline is armed");

            // Arming must switch the slow path on again.
            Thread other = new Thread(() -> { });
            lc.abandonStaticInitializer(other);
            lc.checkLoop(index);
            assertTrue(lookups.get() > 0, "an armed deadline must enable the lookup");
        } finally {
            f.set(lc, original);
            Thread gone = new Thread(() -> { });
            gone.start();
            gone.join();
            lc.protectFromReset(gone); // clears the flag again; the dead entry is pruned
        }
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
     * Static initializer that loops well past the iteration limit, standing in for a
     * legitimate one that builds a large table.
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

    /** A deliberately runaway initializer used only after the executor has abandoned it. */
    private static class AbandonedClinitProbe {

        static {
            abandonedProbeEntered = true;
            while (!abandonProbeStop) {
                try {
                    LoopCounter.getInstance().checkLoop(probeIndex);
                } catch (TooManyResourcesException e) {
                    if (++abandonedProbeTimeouts >= 2) {
                        abandonedProbeRepeatedTimeout = true;
                        break;
                    }
                }
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
