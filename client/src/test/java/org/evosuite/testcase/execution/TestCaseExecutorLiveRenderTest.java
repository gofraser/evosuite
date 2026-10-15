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

import org.evosuite.Properties;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cancelling a test that outlives its timeout must not render the test while the executor
 * thread is still running it: a functional mock holds null placeholders for the calls it is
 * observing, rendering one threw a NullPointerException, and the error ended the search.
 */
public class TestCaseExecutorLiveRenderTest {

    private int originalTimeout;
    private int originalShutdownTimeout;

    @AfterEach
    public void restore() {
        Properties.TIMEOUT = originalTimeout;
        Properties.SHUTDOWN_TIMEOUT = originalShutdownTimeout;
        ExecutionTracer.setKillSwitch(false);
        TestCaseExecutor.initExecutor();
        TestCaseExecutor.resetAdaptiveTimeoutStateIfPresent();
    }

    /**
     * Ignores the interrupt and never reaches a kill-switch probe, like code under test stuck in
     * a JDK call; bounded so that it returns on its own.
     */
    public static void sleepThroughInterrupts() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
                // keep going
            }
        }
    }

    /** Stands in for a test whose statements are only consistent once execution finished. */
    private static class UnrenderableWhileRunning extends DefaultTestCase {
        private static final long serialVersionUID = 1L;

        @Override
        public String toCode() {
            throw new IllegalStateException("rendered while the executor thread runs the test");
        }

        @Override
        public DefaultTestCase clone() {
            return this;
        }
    }

    /** Code under test that runs out of memory. */
    public static void runOutOfMemory() {
        throw new OutOfMemoryError("Java heap space");
    }

    /** Counts renderings instead of failing them, so the executor's own handling is observed. */
    private static class CountingRenders extends DefaultTestCase {
        private static final long serialVersionUID = 1L;
        private static final AtomicInteger RENDERS = new AtomicInteger();

        @Override
        public String toCode() {
            RENDERS.incrementAndGet();
            return super.toCode();
        }

        @Override
        public DefaultTestCase clone() {
            return this;
        }
    }

    /**
     * The executor thread can be stopped by the timeout at any point; rendering the test while
     * handling an OutOfMemoryError initialised the escaping helper there, the stop landed in its
     * class initialiser, and every later rendering failed for the rest of the run (jiggler).
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    public void anOutOfMemoryErrorIsNotHandledByRenderingTheTestOnTheExecutorThread() throws Exception {
        originalTimeout = Properties.TIMEOUT;
        originalShutdownTimeout = Properties.SHUTDOWN_TIMEOUT;

        DefaultTestCase testCase = new CountingRenders();
        testCase.addStatement(new MethodStatement(testCase,
                new GenericMethod(TestCaseExecutorLiveRenderTest.class.getMethod("runOutOfMemory"),
                        TestCaseExecutorLiveRenderTest.class),
                null, Collections.emptyList()));
        CountingRenders.RENDERS.set(0);

        TestCaseExecutor.runTest(testCase);

        Assertions.assertEquals(0, CountingRenders.RENDERS.get());
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    public void aTestThatOutlivesItsTimeoutIsNotRenderedWhileItRuns() throws Exception {
        originalTimeout = Properties.TIMEOUT;
        originalShutdownTimeout = Properties.SHUTDOWN_TIMEOUT;
        Properties.TIMEOUT = 300;
        Properties.SHUTDOWN_TIMEOUT = 150;

        DefaultTestCase testCase = new UnrenderableWhileRunning();
        testCase.addStatement(new MethodStatement(testCase,
                new GenericMethod(TestCaseExecutorLiveRenderTest.class
                        .getMethod("sleepThroughInterrupts"),
                        TestCaseExecutorLiveRenderTest.class),
                null, Collections.emptyList()));

        ExecutionResult result = TestCaseExecutor.runTest(testCase);

        Assertions.assertNotNull(result);
    }
}
