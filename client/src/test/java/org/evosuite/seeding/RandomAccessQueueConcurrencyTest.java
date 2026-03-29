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
package org.evosuite.seeding;

import org.evosuite.utils.DefaultRandomAccessQueue;
import org.evosuite.utils.RandomAccessQueue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class RandomAccessQueueConcurrencyTest {

    @Test
    public void defaultRandomAccessQueueConcurrentReadWriteDoesNotFail() throws Exception {
        assertNull(runConcurrentReadWrite(new DefaultRandomAccessQueue<>()));
    }

    @Test
    public void frequencyBasedRandomAccessQueueConcurrentReadWriteDoesNotFail() throws Exception {
        assertNull(runConcurrentReadWrite(new FrequencyBasedRandomAccessQueue<>()));
    }

    private Throwable runConcurrentReadWrite(RandomAccessQueue<Integer> queue) throws Exception {
        queue.restrictedAdd(0); // keep queue non-empty for random reads

        int writerThreads = 4;
        int readerThreads = 4;
        int operationsPerThread = 2_000;

        ExecutorService executor = Executors.newFixedThreadPool(writerThreads + readerThreads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>(null);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < writerThreads; i++) {
            final int base = i * operationsPerThread;
            futures.add(executor.submit(() -> {
                await(start);
                for (int j = 0; j < operationsPerThread; j++) {
                    try {
                        queue.restrictedAdd(base + j);
                    } catch (Throwable t) {
                        firstFailure.compareAndSet(null, t);
                        return;
                    }
                }
            }));
        }

        for (int i = 0; i < readerThreads; i++) {
            futures.add(executor.submit(() -> {
                await(start);
                for (int j = 0; j < operationsPerThread; j++) {
                    try {
                        assertNotNull(queue.getRandomValue());
                    } catch (Throwable t) {
                        firstFailure.compareAndSet(null, t);
                        return;
                    }
                }
            }));
        }

        start.countDown();

        for (Future<?> future : futures) {
            future.get(15, TimeUnit.SECONDS);
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        return firstFailure.get();
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
