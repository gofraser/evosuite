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
package org.evosuite.testcase.execution;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutableSnippetEngineWedgedCompilerTest {

    @Test
    void compilationAfterAWedgedCompilerRunsOnAReplacementWorker() throws Throwable {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService wedged = Executors.newSingleThreadExecutor();
        // A javac that ignores interrupts: the worker thread stays busy until released.
        wedged.submit(() -> {
            started.countDown();
            boolean released = false;
            while (!released) {
                try {
                    release.await();
                    released = true;
                } catch (InterruptedException ignored) {
                    // keep waiting
                }
            }
        });
        try {
            started.await();
            ExecutableSnippetEngine engine = new ExecutableSnippetEngine(wedged);

            assertThrows(ExecutableSnippetEngine.AssertionCompilationTimeoutException.class,
                    () -> engine.evaluateAssertion("1 > 0",
                            Collections.<String, Type>emptyMap(),
                            Collections.<String, Object>emptyMap(),
                            300, 0));

            assertTrue(engine.evaluateAssertion("2 > 1",
                    Collections.<String, Type>emptyMap(),
                    Collections.<String, Object>emptyMap(),
                    30_000, 0));
        } finally {
            release.countDown();
            wedged.shutdownNow();
        }
    }
}
