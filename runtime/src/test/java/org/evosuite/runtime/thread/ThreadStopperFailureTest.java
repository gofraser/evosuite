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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

/**
 * Thread.interrupt() is not final, so a SUT thread class can override it and throw. When
 * stopping such a leftover thread failed, ThreadStopper left both the kill switch and the
 * stopping-leftover-threads flag on: every later test then ran with them set, and the next
 * pause of any SUT thread was turned into a stop.
 */
public class ThreadStopperFailureTest {

    /** A SUT thread whose interrupt() refuses to work. */
    public static class UninterruptibleThread extends Thread {
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void run() {
            try {
                release.await();
            } catch (InterruptedException e) {
                // ends the thread
            }
        }

        @Override
        public void interrupt() {
            throw new UnsupportedOperationException("not interruptible");
        }

        void release() {
            release.countDown();
        }
    }

    @Test(timeout = 30_000)
    public void flagsAreClearedWhenStoppingAThreadFails() throws Exception {
        AtomicBoolean killSwitch = new AtomicBoolean();
        ThreadStopper stopper = new ThreadStopper(killSwitch::set, 1_000L);
        stopper.storeCurrentThreads();

        UninterruptibleThread leftover = new UninterruptibleThread();
        leftover.setDaemon(true);
        leftover.start();
        try {
            try {
                stopper.killAndJoinClientThreads();
                fail("the SUT's interrupt() was expected to throw");
            } catch (UnsupportedOperationException expected) {
                // the failure itself is the SUT's
            }
            assertFalse("kill switch left on", killSwitch.get());
            assertFalse("stopping-leftover-threads flag left on",
                    KillSwitchHandler.isStoppingLeftoverThreads());
        } finally {
            leftover.release();
            leftover.join(5_000L);
            KillSwitchHandler.setStoppingLeftoverThreads(false);
        }
    }
}
