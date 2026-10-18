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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EvoSuiteOwnedThreads} membership is by identity, not by name: two distinct threads
 * with the same name must not be confused with one another.
 */
public class EvoSuiteOwnedThreadsTest {

    @Test
    public void unregisteredThreadIsNotOwned() {
        Thread t = new Thread(() -> { }, "some-thread");
        assertFalse(EvoSuiteOwnedThreads.isOwned(t));
    }

    @Test
    public void registeredThreadIsOwned() {
        Thread t = new Thread(() -> { }, "owned-thread");
        EvoSuiteOwnedThreads.register(t);
        assertTrue(EvoSuiteOwnedThreads.isOwned(t));
    }

    @Test
    public void distinctThreadsWithTheSameNameAreNotConfused() {
        Thread owned = new Thread(() -> { }, "same-name");
        Thread impostor = new Thread(() -> { }, "same-name");
        EvoSuiteOwnedThreads.register(owned);
        assertTrue(EvoSuiteOwnedThreads.isOwned(owned));
        assertFalse(EvoSuiteOwnedThreads.isOwned(impostor));
    }

    @Test
    public void threadStopperSkipsAnOwnedThreadRegardlessOfName() throws Exception {
        ThreadStopper stopper = new ThreadStopper(kill -> { }, 200L);
        stopper.storeCurrentThreads();
        stopper.startRecordingTime();

        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        Thread owned = new Thread(() -> {
            started.countDown();
            try {
                Thread.sleep(60_000L);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        }, "not-named-specially");
        owned.setDaemon(true);
        EvoSuiteOwnedThreads.register(owned);
        owned.start();
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));

        stopper.killAndJoinClientThreads();

        assertFalse(interrupted.get(), "an EvoSuiteOwnedThreads-registered thread must not be interrupted");
        owned.interrupt();
        owned.join(5000);
    }
}
