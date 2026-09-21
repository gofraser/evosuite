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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end regression test for the &lt;clinit&gt; re-check {@link TestCaseExecutor} performs
 * immediately before forcing a stuck worker thread (interrupt/{@code Thread.stop()}).
 *
 * <p>{@code initialInStaticInit}/{@code initialInProtectedBootstrap} in {@link
 * TestCaseExecutor#execute(org.evosuite.testcase.TestCase, Scope, int)} are snapshotted once,
 * when the timeout is first caught. A thread that was executing ordinary SUT code at that
 * moment — so takes the fast kill-switch-swallow escalation added for 104_vuze's
 * {@code accept_loop()} (see {@link TestCaseExecutorKillSwitchEscalationTest}), which skips the
 * &lt;clinit&gt; grace/abandon handling entirely — can enter a real &lt;clinit&gt; by the time
 * the last-resort force-stop decision is made a moment later. Forcing it there (interrupt can be
 * turned into an exception inside the initializer; {@code Thread.stop()} injects
 * {@code ThreadDeath} with no regard for the frame it lands in) is exactly the class-poisoning
 * failure {@code abandonStuckStaticInit} exists to avoid — this reproduced on the 108_liferay
 * cluster as a stuck {@code PropsUtil}/{@code PortletPreferencesPersistenceImpl} &lt;clinit&gt;
 * chain, followed by {@code NoClassDefFoundError} on every later test in the process.
 *
 * <p>The SUT statement spins in a plain (non-&lt;clinit&gt;) loop, swallowing
 * {@link TestCaseExecutor.TimeoutExceeded} the same way {@code accept_loop()} does, so the
 * kill-switch probe counter is nonzero and the fast path fires. It then touches {@link
 * BlockingClinitHolder}, whose &lt;clinit&gt; blocks — on a latch held by this test class, not
 * by itself, to avoid a class-initialization deadlock when the test thread later releases it —
 * putting the worker thread inside a real &lt;clinit&gt; frame by the time TestCaseExecutor
 * reaches its force-stop decision.
 */
public class TestCaseExecutorClinitReCheckTest {

    private static volatile CountDownLatch releaseLatch;

    private int originalTimeout;
    private int originalShutdownTimeout;

    @AfterEach
    public void restore() {
        Properties.TIMEOUT = originalTimeout;
        Properties.SHUTDOWN_TIMEOUT = originalShutdownTimeout;
        ExecutionTracer.setKillSwitch(false);
        // Always release, even on assertion failure, so a stuck worker thread does not sit
        // blocked in BlockingClinitHolder's <clinit> for the rest of the test JVM's life.
        CountDownLatch latch = releaseLatch;
        if (latch != null) {
            latch.countDown();
        }
        TestCaseExecutor.initExecutor();
        TestCaseExecutor.resetAdaptiveTimeoutStateIfPresent();
    }

    /**
     * Spins for 250ms, swallowing every {@link TestCaseExecutor.TimeoutExceeded} exactly like
     * {@code accept_loop()} does — TIMEOUT is set to 150ms below, so this is still spinning (and
     * has already generated several kill-switch probes) when TestCaseExecutor's first
     * post-kill-switch wait ends and it decides to fast-escalate. It then touches {@link
     * BlockingClinitHolder}, putting the thread inside a real &lt;clinit&gt; well before
     * TestCaseExecutor reaches its force-stop decision (SHUTDOWN_TIMEOUT after that, 300ms
     * below).
     */
    public static void spinThenBlockInClinit() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
        while (System.nanoTime() < deadline) {
            try {
                ExecutionTracer.checkTimeout();
            } catch (Throwable ignored) {
                // exactly what accept_loop() does
            }
        }
        BlockingClinitHolder.touch();
    }

    static final class BlockingClinitHolder {
        static volatile boolean initialized = false;

        static {
            // Block on a latch owned by the outer (already-initialized) test class rather
            // than on anything of our own: the test thread needs to be able to release it
            // without itself touching (and so blocking on) this class.
            CountDownLatch latch = releaseLatch;
            while (latch != null && latch.getCount() > 0) {
                try {
                    latch.await();
                } catch (InterruptedException ignored) {
                    // The liferay <clinit> chain this guards against does not necessarily
                    // respond to interrupt either; keep waiting rather than let this escape
                    // and poison the class.
                }
            }
            initialized = true;
        }

        static void touch() {
            // Calling this is enough to trigger <clinit> on first use.
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    public void reChecksStaticInitBeforeForcingAThreadTheFastPathWouldOtherwiseStop() throws Exception {
        originalTimeout = Properties.TIMEOUT;
        originalShutdownTimeout = Properties.SHUTDOWN_TIMEOUT;
        Properties.TIMEOUT = 150;
        Properties.SHUTDOWN_TIMEOUT = 300;
        releaseLatch = new CountDownLatch(1);

        DefaultTestCase testCase = buildBlockingTestCase();

        ExecutionResult result = TestCaseExecutor.runTest(testCase);
        Assertions.assertNotNull(result);

        // Let the worker actually finish <clinit> now that the fix should have left it running
        // (abandoned, not interrupted/stopped) while it was inside BlockingClinitHolder.
        releaseLatch.countDown();

        // If TestCaseExecutor forced the thread while it was inside <clinit> (the bug this test
        // guards against), BlockingClinitHolder is now permanently erroneous and this throws
        // NoClassDefFoundError/ExceptionInInitializerError instead of returning — the JVM marks
        // a class whose <clinit> did not complete normally as erroneous for the rest of the
        // process, exactly the "every later test throws NoClassDefFoundError" symptom seen on
        // the 108_liferay cluster.
        BlockingClinitHolder.touch();
        Assertions.assertTrue(BlockingClinitHolder.initialized,
                "BlockingClinitHolder's <clinit> should have completed normally, not been "
                        + "poisoned by a force-stop while the worker thread was inside it");
    }

    private static DefaultTestCase buildBlockingTestCase() throws Exception {
        DefaultTestCase testCase = new DefaultTestCase();
        Method spin = TestCaseExecutorClinitReCheckTest.class.getMethod("spinThenBlockInClinit");
        MethodStatement ms = new MethodStatement(testCase,
                new GenericMethod(spin, TestCaseExecutorClinitReCheckTest.class),
                null,
                Collections.emptyList());
        testCase.addStatement(ms);
        return testCase;
    }
}
