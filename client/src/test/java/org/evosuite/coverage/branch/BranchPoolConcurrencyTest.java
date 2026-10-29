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
package org.evosuite.coverage.branch;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class BranchPoolConcurrencyTest {

    @Test
    public void concurrentLookupsOfOneLoaderShareOnePool() throws Exception {
        int threads = 8;
        int loaders = 3000;
        ClassLoader[] fresh = new ClassLoader[loaders];
        for (int i = 0; i < loaders; i++) {
            fresh[i] = new ClassLoader() {
            };
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<BranchPool[]>> results = new ArrayList<>();
            CountDownLatch start = new CountDownLatch(1);
            for (int t = 0; t < threads; t++) {
                results.add(executor.submit(() -> {
                    BranchPool[] seen = new BranchPool[loaders];
                    start.await();
                    for (int i = 0; i < loaders; i++) {
                        seen[i] = BranchPool.getInstance(fresh[i]);
                    }
                    return seen;
                }));
            }
            start.countDown();

            List<BranchPool[]> all = new ArrayList<>();
            for (Future<BranchPool[]> result : results) {
                all.add(result.get());
            }
            for (int i = 0; i < loaders; i++) {
                Set<BranchPool> distinct = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                for (BranchPool[] seen : all) {
                    distinct.add(seen[i]);
                }
                assertEquals(1, distinct.size(), "loader " + i + " got several pools");
            }
        } finally {
            executor.shutdownNow();
            for (ClassLoader loader : fresh) {
                BranchPool.release(loader);
            }
        }
    }

    @Test
    public void nullLoaderUsesTheSystemLoaderPool() {
        assertSame(BranchPool.getInstance(ClassLoader.getSystemClassLoader()),
                BranchPool.getInstance(null));
    }
}
