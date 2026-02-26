/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.seeding;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class ObjectPoolTest {

    @Test
    public void testAddAndGetSequence() {
        ObjectPool pool = new ObjectPool();
        GenericClass<?> clazz = GenericClassFactory.get(String.class);
        TestCase testCase = new DefaultTestCase(); // minimal test case

        pool.addSequence(clazz, testCase);

        assertTrue(pool.hasSequence(clazz));
        assertEquals(1, pool.getNumberOfClasses());
        assertEquals(1, pool.getNumberOfSequences());

        TestCase retrieved = pool.getRandomSequence(clazz);
        assertNotNull(retrieved);
    }

    @Test
    public void testSerialization() throws IOException {
        ObjectPool pool = new ObjectPool();
        GenericClass<?> clazz = GenericClassFactory.get(String.class);
        TestCase testCase = new DefaultTestCase();
        pool.addSequence(clazz, testCase);

        File tempFile = File.createTempFile("objectpool", ".ser");
        tempFile.deleteOnExit();
        String path = tempFile.getAbsolutePath();

        pool.writePool(path);

        ObjectPool loadedPool = ObjectPool.getPoolFromFile(path);
        assertNotNull(loadedPool);
        assertTrue(loadedPool.hasSequence(clazz));
        assertEquals(1, loadedPool.getNumberOfSequences());
    }

    @Test
    public void testConcurrentReadWrite_noException() throws Exception {
        ObjectPool pool = new ObjectPool();
        GenericClass<?> clazz = GenericClassFactory.get(String.class);

        // Pre-populate with one sequence
        pool.addSequence(clazz, new DefaultTestCase());

        int writerCount = 4;
        int readerCount = 8;
        int opsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(writerCount + readerCount);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicBoolean cmeDetected = new AtomicBoolean(false);
        AtomicInteger successfulReads = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();

        // Writer threads: add sequences concurrently
        for (int w = 0; w < writerCount; w++) {
            futures.add(executor.submit(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        pool.addSequence(clazz, new DefaultTestCase());
                    }
                } catch (ConcurrentModificationException e) {
                    cmeDetected.set(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        // Reader threads: call hasSequence + getRandomSequence concurrently
        for (int r = 0; r < readerCount; r++) {
            futures.add(executor.submit(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        try {
                            boolean has = pool.hasSequence(clazz);
                            if (has) {
                                TestCase seq = pool.getRandomSequence(clazz);
                                if (seq != null) {
                                    successfulReads.incrementAndGet();
                                }
                            }
                        } catch (ConcurrentModificationException e) {
                            cmeDetected.set(true);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        // Release all threads simultaneously
        startGate.countDown();

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertFalse(cmeDetected.get(), "No ConcurrentModificationException should occur");
        assertTrue(successfulReads.get() > 0, "Some reads should have succeeded");
        // Pool should have the original + all writer additions
        assertTrue(pool.getNumberOfSequences() >= 1, "Pool should have at least the original sequence");
    }

    @Test
    public void testGetSequences_returnsDefensiveCopy() {
        ObjectPool pool = new ObjectPool();
        GenericClass<?> clazz = GenericClassFactory.get(String.class);
        pool.addSequence(clazz, new DefaultTestCase());

        Set<TestCase> sequences = pool.getSequences(clazz);
        int sizeBefore = sequences.size();

        // Adding to pool should not affect the returned snapshot
        pool.addSequence(clazz, new DefaultTestCase());

        assertEquals(sizeBefore, sequences.size(),
                "getSequences should return a snapshot, not a live view");
    }
}
