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
 * A thread the SUT started that loops on wait(), swallowing the interrupt, must not make the
 * end of the test wait for the whole timeout: with the real pause of mocked waits it never
 * reached the cap in time, so every such test cost the full timeout.
 */
public class LeftoverSutThreadTest {

    private String oldClassName;

    public static class Looper extends MockThread {
        private final Object lock = new Object();

        @Override
        public void run() {
            synchronized (lock) {
                while (true) {
                    try {
                        MockObjectWait.wait(lock, 2000L);
                    } catch (InterruptedException e) {
                        // swallowed, like the SUT
                    }
                }
            }
        }
    }

    @Before
    public void setUp() {
        oldClassName = RuntimeSettings.className;
        RuntimeSettings.className = Looper.class.getName();
        MockFramework.enable();
        MockObjectWait.reset();
    }

    @After
    public void tearDown() {
        MockFramework.disable();
        RuntimeSettings.className = oldClassName;
    }

    @Test(timeout = 30_000)
    public void leftoverThreadThatSwallowsInterruptsEndsWithinTheJoinWindow() throws Exception {
        ThreadStopper stopper = new ThreadStopper(b -> { }, 10_000L);
        stopper.storeCurrentThreads();
        Looper looper = new Looper();
        looper.setDaemon(true);
        looper.start();
        Thread.sleep(200);
        assertTrue(looper.isAlive());

        long start = System.nanoTime();
        stopper.killAndJoinClientThreads();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertFalse("thread still alive after " + elapsedMs + " ms", looper.isAlive());
        assertTrue("took " + elapsedMs + " ms", elapsedMs < 3_000L);
    }
}
