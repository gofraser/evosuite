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

import org.junit.jupiter.api.*;

import java.util.concurrent.TimeUnit;

/**
 * Created by Andrea Arcuri on 29/03/15.
 */
public class LoopCounterTest {

    private boolean loopCounterState = false;

    @BeforeEach
    public void init() {
        LoopCounter.getInstance().reset();
        loopCounterState = LoopCounter.getInstance().isActivated();
        LoopCounter.getInstance().setActive(true);
    }

    @AfterEach
    public void tearDown() {
        LoopCounter.getInstance().reset();
        LoopCounter.getInstance().setActive(loopCounterState);
    }

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS)
    public void testInfiniteLoop() {

        Assertions.assertTrue(RuntimeSettings.maxNumberOfIterationsPerLoop > 0);//should be on by default

        int first = LoopCounter.getInstance().getNewIndex();
        int second = LoopCounter.getInstance().getNewIndex();

        while (true) {
            LoopCounter.getInstance().checkLoop(first);

            for (int i = 0; i < 100; i++) {
                try {
                    LoopCounter.getInstance().checkLoop(second); //this should fail first
                } catch (TooManyResourcesException e) {
                    //expected
                    return;
                }
            }
        }
    }

    /**
     * A loop that swallows the exception (catch Throwable around a while (true)) starts from
     * zero again after each trip. From the second trip on the exception is flagged and the
     * handlers EvoSuite instrumented, which call Runtime.rethrowIfTimeout, let it through.
     */
    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS)
    public void testSwallowedLoopLimitEscapesOnTheSecondTrip() {
        long limit = RuntimeSettings.maxNumberOfIterationsPerLoop;
        RuntimeSettings.maxNumberOfIterationsPerLoop = 50;
        try {
            int index = LoopCounter.getInstance().getNewIndex();
            int swallowed = 0;
            try {
                while (true) {
                    try {
                        LoopCounter.getInstance().checkLoop(index);
                    } catch (Throwable t) {
                        swallowed++;
                        Runtime.rethrowIfTimeout(t); // what the instrumented handler does
                    }
                }
            } catch (TooManyResourcesException escaped) {
                Assertions.assertTrue(escaped.isRepeated());
            }
            Assertions.assertEquals(2, swallowed, "the first trip is swallowed, the second one is rethrown by the handler");
        } finally {
            RuntimeSettings.maxNumberOfIterationsPerLoop = limit;
        }
    }

    /** Runs {@code index} up to the limit and returns the exception it throws there. */
    private static TooManyResourcesException trip(int index) {
        for (long i = 0; i <= RuntimeSettings.maxNumberOfIterationsPerLoop; i++) {
            try {
                LoopCounter.getInstance().checkLoop(index);
            } catch (TooManyResourcesException e) {
                return e;
            }
        }
        throw new AssertionError("loop " + index + " did not hit the limit");
    }

    /**
     * One loop hits the limit and the exception reaches the test. A different loop hitting the
     * limit afterwards has not been swallowed by anything, so the handlers must see it.
     */
    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS)
    public void testLimitOfAnotherLoopIsNotRepeated() {
        long limit = RuntimeSettings.maxNumberOfIterationsPerLoop;
        RuntimeSettings.maxNumberOfIterationsPerLoop = 50;
        try {
            int first = LoopCounter.getInstance().getNewIndex();
            int second = LoopCounter.getInstance().getNewIndex();
            Assertions.assertFalse(trip(first).isRepeated());
            Assertions.assertFalse(trip(second).isRepeated());
            Assertions.assertTrue(trip(first).isRepeated());
        } finally {
            RuntimeSettings.maxNumberOfIterationsPerLoop = limit;
        }
    }

    /**
     * A thread abandoned in the previous test keeps hitting the limit after the reset for the
     * next one. That says nothing about the loops of the next test's thread.
     */
    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS)
    public void testLimitOnAnotherThreadIsNotRepeated() throws InterruptedException {
        long limit = RuntimeSettings.maxNumberOfIterationsPerLoop;
        RuntimeSettings.maxNumberOfIterationsPerLoop = 50;
        try {
            int index = LoopCounter.getInstance().getNewIndex();
            java.util.concurrent.CountDownLatch reset = new java.util.concurrent.CountDownLatch(1);
            java.util.List<Boolean> abandonedRepeated = new java.util.concurrent.CopyOnWriteArrayList<>();
            Thread abandoned = new Thread(() -> {
                try {
                    reset.await();
                } catch (InterruptedException e) {
                    return;
                }
                abandonedRepeated.add(trip(index).isRepeated());
                abandonedRepeated.add(trip(index).isRepeated());
            });
            abandoned.start();
            LoopCounter.getInstance().protectFromReset(abandoned);
            LoopCounter.getInstance().reset();
            reset.countDown();
            abandoned.join();
            Assertions.assertEquals(java.util.Arrays.asList(false, true), abandonedRepeated,
                    "the abandoned thread keeps its own record");

            Assertions.assertFalse(trip(index).isRepeated());
        } finally {
            RuntimeSettings.maxNumberOfIterationsPerLoop = limit;
        }
    }

    /** A reset for the next test forgets the loops the previous test's thread tripped. */
    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS)
    public void testResetForgetsTrips() {
        long limit = RuntimeSettings.maxNumberOfIterationsPerLoop;
        RuntimeSettings.maxNumberOfIterationsPerLoop = 50;
        try {
            int index = LoopCounter.getInstance().getNewIndex();
            Assertions.assertFalse(trip(index).isRepeated());
            LoopCounter.getInstance().reset();
            Assertions.assertFalse(trip(index).isRepeated());
        } finally {
            RuntimeSettings.maxNumberOfIterationsPerLoop = limit;
        }
    }

    /**
     * The code under test swallows a loop-limit exception, is then interrupted, and swallows
     * that too. The interrupt must not make the loop's next trip count as its first.
     */
    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS)
    public void testInterruptKeepsTheRecordOfTrips() {
        long limit = RuntimeSettings.maxNumberOfIterationsPerLoop;
        RuntimeSettings.maxNumberOfIterationsPerLoop = 50;
        try {
            int index = LoopCounter.getInstance().getNewIndex();
            Assertions.assertFalse(trip(index).isRepeated());
            Thread.currentThread().interrupt();
            try {
                Assertions.assertThrows(TooManyResourcesException.class,
                        () -> LoopCounter.getInstance().checkLoop(index));
            } finally {
                Thread.interrupted();
            }
            Assertions.assertTrue(trip(index).isRepeated());
        } finally {
            RuntimeSettings.maxNumberOfIterationsPerLoop = limit;
        }
    }

    @Test
    public void testNoNewIndex() {
        LoopCounter.getInstance().checkLoop(0);
        LoopCounter.getInstance().checkLoop(1);
        LoopCounter.getInstance().checkLoop(2);
        LoopCounter.getInstance().checkLoop(5);
        LoopCounter.getInstance().checkLoop(6);
    }
}
