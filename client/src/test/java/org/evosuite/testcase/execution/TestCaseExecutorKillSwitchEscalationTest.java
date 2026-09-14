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

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end regression test for the kill-switch fast-escalation path in
 * {@link TestCaseExecutor#execute}.
 *
 * <p>The scenario this guards against: a SUT that keeps running instrumented code after
 * the kill switch was set, because it swallows {@link TestCaseExecutor.TimeoutExceeded}
 * the same way {@code VirtualBlockingServerChannelSelector.accept_loop()} in 104_vuze
 * swallows every {@code Throwable} in its own catch-and-retry loop. Without fast
 * escalation, such a thread is only ever terminated by the last-resort
 * {@code Thread.stop()}, reached after paying the entire graceful shutdown chain
 * (several more {@code SHUTDOWN_TIMEOUT}-sized waits) on top of the initial timeout.
 */
public class TestCaseExecutorKillSwitchEscalationTest {

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
     * Loops exactly the way {@code accept_loop()} does: catch {@code Throwable}, ignore
     * it, go straight back around. Each iteration calls {@link ExecutionTracer#checkTimeout()}
     * directly, standing in for the {@code checkTimeout()} probe the bytecode instrumenter
     * would otherwise insert at every line/branch/method entry of real SUT code.
     *
     * <p>Bounded by wall-clock time rather than an iteration count so that this method
     * always returns on its own — even if the JVM running the test does not honour
     * {@code Thread.stop()} on a busy-looping thread (see the JDK 20+ note in
     * {@link TestCaseExecutor#execute}) — instead of leaking a CPU-spinning thread for the
     * rest of the test JVM's life.
     */
    public static void spinSwallowingTimeout() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try {
                ExecutionTracer.checkTimeout();
            } catch (Throwable ignored) {
                // exactly what accept_loop() does
            }
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    public void escalatesQuicklyWhenSutSwallowsTimeoutExceeded() throws Exception {
        originalTimeout = Properties.TIMEOUT;
        originalShutdownTimeout = Properties.SHUTDOWN_TIMEOUT;
        Properties.TIMEOUT = 300;
        Properties.SHUTDOWN_TIMEOUT = 150;

        DefaultTestCase testCase = buildSpinningTestCase();

        long start = System.currentTimeMillis();
        ExecutionResult result = TestCaseExecutor.runTest(testCase);
        long elapsed = System.currentTimeMillis() - start;

        Assertions.assertNotNull(result);
        // Without fast escalation, a thread that keeps swallowing TimeoutExceeded is only
        // ever stopped after the full graceful chain — several more SHUTDOWN_TIMEOUT-sized
        // waits — on top of TIMEOUT itself. With it, we go straight to the Thread.stop()
        // last resort after just one such wait. This bound is generous on purpose: the
        // point is that execution returns quickly, not a tight timing measurement.
        Assertions.assertTrue(elapsed < 10_000,
                "expected the swallowing SUT to be force-stopped within a few seconds, took " + elapsed + "ms");
    }

    private static DefaultTestCase buildSpinningTestCase() throws Exception {
        DefaultTestCase testCase = new DefaultTestCase();
        Method spin = TestCaseExecutorKillSwitchEscalationTest.class.getMethod("spinSwallowingTimeout");
        MethodStatement ms = new MethodStatement(testCase,
                new GenericMethod(spin, TestCaseExecutorKillSwitchEscalationTest.class),
                null,
                Collections.emptyList());
        testCase.addStatement(ms);
        return testCase;
    }
}
