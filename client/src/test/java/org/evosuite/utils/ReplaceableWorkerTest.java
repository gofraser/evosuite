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
package org.evosuite.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplaceableWorkerTest {

    private final AtomicBoolean release = new AtomicBoolean();
    private final ReplaceableWorker worker = new ReplaceableWorker(r -> {
        Thread t = new Thread(r, "replaceable-worker-test");
        t.setDaemon(true);
        return t;
    });

    @AfterEach
    void releaseStuckTasks() {
        release.set(true);
        worker.reset();
    }

    @Test
    void returnsTheResultAndKeepsTheWorker() throws Exception {
        assertNull(worker.thread());
        assertEquals("a", worker.call(() -> "a", 5, TimeUnit.SECONDS));
        Thread first = worker.thread();
        assertEquals("b", worker.call(() -> "b", 5, TimeUnit.SECONDS));
        assertSame(first, worker.thread());
    }

    @Test
    void taskFailureKeepsTheWorker() throws Exception {
        worker.call(() -> "warm up", 5, TimeUnit.SECONDS);
        Thread first = worker.thread();
        assertThrows(ExecutionException.class, () -> worker.call(() -> {
            throw new IllegalStateException("boom");
        }, 5, TimeUnit.SECONDS));
        assertEquals("ok", worker.call(() -> "ok", 5, TimeUnit.SECONDS));
        assertSame(first, worker.thread());
    }

    @Test
    void timeoutReplacesAWorkerThatIgnoresTheInterrupt() throws Exception {
        worker.call(() -> "warm up", 5, TimeUnit.SECONDS);
        Thread stuck = worker.thread();
        assertThrows(TimeoutException.class, () -> worker.call(() -> {
            while (!release.get()) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException ignored) {
                    // Simulate a task that ignores cancellation.
                }
            }
            return null;
        }, 100, TimeUnit.MILLISECONDS));

        assertEquals("next", worker.call(() -> "next", 5, TimeUnit.SECONDS));
        assertNotSame(stuck, worker.thread());
    }

    @Test
    void interruptWithoutReplacementKeepsTheTaskAndTheWorker() throws Exception {
        worker.call(() -> "warm up", 5, TimeUnit.SECONDS);
        Thread before = worker.thread();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> worker.call(() -> {
                try {
                    Thread.sleep(300L);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                }
                return null;
            }, 5, TimeUnit.SECONDS, false));
        } finally {
            Thread.interrupted();
        }
        assertEquals("next", worker.call(() -> "next", 5, TimeUnit.SECONDS));
        assertSame(before, worker.thread());
        assertEquals(false, interrupted.get());
    }

    @Test
    void interruptOfTheCallerCancelsAndReplaces()throws Exception {
        worker.call(() -> "warm up", 5, TimeUnit.SECONDS);
        Thread before = worker.thread();
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> worker.call(() -> {
                Thread.sleep(5_000L);
                return null;
            }, 5, TimeUnit.SECONDS));
        } finally {
            Thread.interrupted();
        }
        assertEquals("next", worker.call(() -> "next", 5, TimeUnit.SECONDS));
        assertNotSame(before, worker.thread());
    }
}
