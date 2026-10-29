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
package org.evosuite.runtime.instrumentation;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class TransformingClassLoaderTest {

    public static class Payload {
    }

    private static final String PAYLOAD = Payload.class.getName();

    private static class SlowLoader extends TransformingClassLoader {
        SlowLoader() {
            super(TransformingClassLoaderTest.class.getClassLoader(),
                    TransformingClassLoaderTest.class.getClassLoader());
        }

        @Override
        protected boolean canInstrument(String name) {
            return name.equals(PAYLOAD);
        }

        @Override
        protected InputStream openClass(String name) {
            return getClass().getClassLoader()
                    .getResourceAsStream(name.replace('.', '/') + ".class");
        }

        @Override
        protected byte[] transform(String name, InputStream bytes) throws IOException {
            try {
                // Widen the window in which a second thread can start defining the same class.
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = bytes.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    @Test
    public void parallelFirstLoadsReturnTheSameClass() throws Exception {
        int threads = 4;
        TransformingClassLoader loader = new SlowLoader();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Class<?>>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit((Callable<Class<?>>) () -> {
                    start.await();
                    return loader.loadClass(PAYLOAD);
                }));
            }
            start.countDown();
            Class<?> first = results.get(0).get(20, TimeUnit.SECONDS);
            assertEquals(loader, first.getClassLoader());
            for (Future<Class<?>> result : results) {
                assertSame(first, result.get(20, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
