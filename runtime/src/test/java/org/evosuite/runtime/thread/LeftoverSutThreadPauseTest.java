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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertTrue;

/**
 * A leftover thread whose loop catches Throwable, while the leftover threads are being
 * stopped, must neither keep a monitor from other threads nor spin without pausing.
 */
public class LeftoverSutThreadPauseTest {

    private String oldClassName;
    private final AtomicBoolean quit = new AtomicBoolean();

    public static class WaitLooper extends MockThread {
        final Object lock = new Object();
        final CountDownLatch inLoop = new CountDownLatch(1);
        final AtomicBoolean quit;

        WaitLooper(AtomicBoolean quit) {
            this.quit = quit;
        }

        @Override
        public void run() {
            synchronized (lock) {
                inLoop.countDown();
                while (!quit.get()) {
                    try {
                        MockObjectWait.wait(lock, 1000L);
                    } catch (Throwable t) {
                        // swallowed, like the SUT
                    }
                }
            }
        }
    }

    public static class SleepLooper extends MockThread {
        final AtomicLong iterations = new AtomicLong();
        final AtomicBoolean quit;

        SleepLooper(AtomicBoolean quit) {
            this.quit = quit;
        }

        @Override
        public void run() {
            while (!quit.get()) {
                try {
                    MockThread.sleep(1000L);
                } catch (Throwable t) {
                    // swallowed, like the SUT
                }
                iterations.incrementAndGet();
            }
        }
    }

    @Before
    public void setUp() {
        oldClassName = RuntimeSettings.className;
        RuntimeSettings.className = LeftoverSutThreadPauseTest.class.getName();
        MockFramework.enable();
        MockObjectWait.reset();
        MockThread.reset();
    }

    @After
    public void tearDown() {
        quit.set(true);
        KillSwitchHandler.setStoppingLeftoverThreads(false);
        MockFramework.disable();
        RuntimeSettings.className = oldClassName;
    }

    @Test(timeout = 30_000)
    public void waitingLeftoverThreadReleasesItsMonitorWhileBeingStopped() throws Exception {
        WaitLooper looper = new WaitLooper(quit);
        looper.setDaemon(true);
        looper.start();
        assertTrue(looper.inLoop.await(5, TimeUnit.SECONDS));
        KillSwitchHandler.setStoppingLeftoverThreads(true);
        looper.interrupt();

        // A thread that spins on the monitor can still let another one in now and then;
        // one that really waits lets it in every time.
        final AtomicLong taken = new AtomicLong();
        Thread other = new Thread(() -> {
            while (!quit.get()) {
                synchronized (looper.lock) {
                    taken.incrementAndGet();
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        other.setDaemon(true);
        other.start();
        Thread.sleep(1000);
        long n = taken.get();
        Thread.State state = other.getState();
        quit.set(true);
        assertTrue("another thread took the monitor " + n + " times in 1 s, state " + state, n >= 20);
    }

    @Test(timeout = 30_000)
    public void sleepingLeftoverThreadDoesNotSpinWhileBeingStopped() throws Exception {
        SleepLooper looper = new SleepLooper(quit);
        looper.setDaemon(true);
        looper.start();
        Thread.sleep(100);
        KillSwitchHandler.setStoppingLeftoverThreads(true);
        looper.interrupt();
        long before = looper.iterations.get();
        Thread.sleep(200);
        long n = looper.iterations.get() - before;
        quit.set(true);
        assertTrue("loop ran " + n + " iterations in 200 ms", n < 1000);
    }
}
