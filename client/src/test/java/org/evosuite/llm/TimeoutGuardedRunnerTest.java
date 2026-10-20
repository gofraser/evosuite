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
package org.evosuite.llm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeoutGuardedRunnerTest {

    private static final long ONE_SECOND = TimeUnit.SECONDS.toNanos(1);

    @Test
    void returnsTheResultAndWrapsTheCallsOwnFailure() throws Exception {
        AtomicInteger executors = new AtomicInteger();
        try (TimeoutGuardedRunner runner = new TimeoutGuardedRunner(() -> {
            executors.incrementAndGet();
            return Executors.newSingleThreadExecutor();
        }, () -> { })) {
            assertEquals("ok", runner.run(cancellation -> "ok", ONE_SECOND, Long.MAX_VALUE));

            IllegalStateException failure = new IllegalStateException("provider");
            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> runner.run(cancellation -> {
                        throw failure;
                    }, ONE_SECOND, Long.MAX_VALUE));
            assertSame(failure, thrown.getCause());
            assertEquals(1, executors.get());
            assertEquals(1L, runner.getGeneration());
        }
    }

    @Test
    void anExhaustedDeadlineTimesOutWithoutWaiting() {
        try (TimeoutGuardedRunner runner = new TimeoutGuardedRunner(
                TimeoutGuardedRunner::newDefaultExecutor, () -> { })) {
            assertThrows(TimeoutException.class,
                    () -> runner.run(cancellation -> "late", ONE_SECOND, System.nanoTime() - 1L));
        }
    }

    @Test
    void aWorkerThatIgnoresCancellationGetsItsExecutorReplaced() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger replacements = new AtomicInteger();
        ExecutorService first = Executors.newCachedThreadPool();
        AtomicInteger created = new AtomicInteger();
        try (TimeoutGuardedRunner runner = new TimeoutGuardedRunner(
                () -> created.getAndIncrement() == 0 ? first : Executors.newCachedThreadPool(),
                replacements::incrementAndGet)) {
            assertThrows(TimeoutException.class, () -> runner.run(cancellation -> {
                // Ignores both the interrupt and the cancellation, like a blocked socket read.
                while (true) {
                    try {
                        if (release.await(10, TimeUnit.SECONDS)) {
                            return "released";
                        }
                    } catch (InterruptedException ignored) {
                        // keep blocking
                    }
                }
            }, TimeUnit.MILLISECONDS.toNanos(50), Long.MAX_VALUE));

            assertEquals(2L, runner.getGeneration());
            assertEquals(1, replacements.get());
            assertTrue(first.isShutdown());
            assertEquals("next", runner.run(cancellation -> "next", ONE_SECOND, Long.MAX_VALUE));
        } finally {
            release.countDown();
        }
    }
}
