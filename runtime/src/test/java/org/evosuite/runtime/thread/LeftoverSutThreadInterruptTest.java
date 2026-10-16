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
package org.evosuite.runtime.thread;

import org.evosuite.runtime.LoopCounter;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.java.lang.MockObjectWait;
import org.evosuite.runtime.mock.java.lang.MockThread;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A SUT thread that pauses in a mocked sleep() or wait() inside a loop that swallows every
 * exception (for example {@code try { ...; Thread.sleep(10); } catch (Exception e)})
 * is told to stop by an interrupt. The real pause ends with an InterruptedException, which
 * clears the flag, the SUT swallows it, and the loop's back-edge check in LoopCounter, which
 * ends a loop whose thread is still interrupted, finds nothing to react to. The thread then
 * kept running, busy, until the loop iteration cap, and every test that had started such a
 * thread waited for that in the join (tens of milliseconds each, so a search spent most of
 * its time there).
 */
public class LeftoverSutThreadInterruptTest {

    private String oldClassName;
    private long oldMaxIterations;

    /** Pauses in a mocked sleep() and swallows whatever it throws, like the SUT. */
    public static class SleepSwallower extends MockThread {
        @Override
        public void run() {
            while (true) {
                try {
                    MockThread.sleep(10L);
                } catch (Exception e) {
                    // swallowed, like the SUT
                }
                LoopCounter.getInstance().checkLoop(0);
            }
        }
    }

    /** Pauses in a mocked wait() and swallows whatever it throws, like the SUT. */
    public static class WaitSwallower extends MockThread {
        private final Object lock = new Object();

        @Override
        public void run() {
            synchronized (lock) {
                while (true) {
                    try {
                        MockObjectWait.wait(lock, 10L);
                    } catch (Exception e) {
                        // swallowed, like the SUT
                    }
                    LoopCounter.getInstance().checkLoop(1);
                }
            }
        }
    }

    @Before
    public void setUp() {
        oldClassName = RuntimeSettings.className;
        oldMaxIterations = RuntimeSettings.maxNumberOfIterationsPerLoop;
        // Leave the interrupt as the only thing that can end the loop.
        RuntimeSettings.maxNumberOfIterationsPerLoop = Long.MAX_VALUE;
        LoopCounter.getInstance().reset();
        MockFramework.enable();
        MockObjectWait.reset();
        MockThread.reset();
    }

    @After
    public void tearDown() {
        MockFramework.disable();
        RuntimeSettings.className = oldClassName;
        RuntimeSettings.maxNumberOfIterationsPerLoop = oldMaxIterations;
        LoopCounter.getInstance().reset();
    }

    private void assertEndsPromptly(MockThread leftover) throws Exception {
        ThreadStopper stopper = new ThreadStopper(b -> { }, 10_000L);
        stopper.storeCurrentThreads();
        leftover.setDaemon(true);
        leftover.start();
        // Let it settle into its real pause.
        Thread.sleep(200);
        assertTrue(leftover.isAlive());

        long start = System.nanoTime();
        stopper.killAndJoinClientThreads();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertFalse("thread still alive after " + elapsedMs + " ms", leftover.isAlive());
        assertTrue("took " + elapsedMs + " ms", elapsedMs < 2_000L);
    }

    @Test(timeout = 30_000)
    public void sleepingThreadThatSwallowsExceptionsEndsWhenInterrupted() throws Exception {
        RuntimeSettings.className = SleepSwallower.class.getName();
        assertEndsPromptly(new SleepSwallower());
    }

    @Test(timeout = 30_000)
    public void waitingThreadThatSwallowsExceptionsEndsWhenInterrupted() throws Exception {
        RuntimeSettings.className = WaitSwallower.class.getName();
        assertEndsPromptly(new WaitSwallower());
    }
}
