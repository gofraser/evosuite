/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 */
package org.evosuite.testcase.execution;

import org.evosuite.Properties;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericMethod;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Fork entry point: the controller owns the latch, so releasing it never touches the initializer. */
public class ExecutorRotationAbandonmentFixture {

    private static final CountDownLatch entered = new CountDownLatch(1);
    private static final CountDownLatch release = new CountDownLatch(1);
    private static final AtomicInteger ordinaryInterrupts = new AtomicInteger();
    private static volatile Thread blockedWorker;

    public static void enterInitializer() {
        LatchInitializer.answer();
    }

    private static class LatchInitializer {
        static {
            blockedWorker = Thread.currentThread();
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("initializer fixture was not released");
                }
            } catch (InterruptedException e) {
                throw new IllegalStateException("rotation interrupted initializer", e);
            }
        }

        static int answer() {
            return 42;
        }
    }

    /** Stay alive through normal timeout cleanup, including Thread.stop(), until released. */
    public static void blockOrdinaryWorker() {
        blockedWorker = Thread.currentThread();
        entered.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (release.getCount() > 0 && System.nanoTime() < deadline) {
            try {
                release.await(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                ordinaryInterrupts.incrementAndGet();
            } catch (ThreadDeath ignored) {
                // Simulates a SUT catch(Throwable) retry loop which defeats forced cleanup.
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Properties.TIMEOUT = 100;
        Properties.SHUTDOWN_TIMEOUT = 50;
        Properties.CLINIT_GRACE_TIMEOUT = 100;
        Properties.ROTATION_COOLDOWN_MS = 0;
        Properties.MAX_TOTAL_ROTATIONS = 20;
        Properties.MAX_STALLED_THREADS = 20;
        Properties.CPU_TIMEOUT = false;
        Properties.TARGET_CLASS = ExecutorRotationAbandonmentFixture.class.getName();
        Randomness.setSeed(481516L);
        TestCaseExecutor executor = TestCaseExecutor.getInstance();
        try {
            // Warm infrastructure before using a short SUT deadline. A cold executor can
            // otherwise time out in an unrelated library initializer before reaching ours.
            check(executor.execute(simpleTest(), 5000).noThrownExceptions(), "warmup failed");
            Field poolField = TestCaseExecutor.class.getDeclaredField("executor");
            poolField.setAccessible(true);
            RecordingPool retired = new RecordingPool((ExecutorService) poolField.get(executor));
            poolField.set(executor, retired);
            int rotations = executor.getTotalRotationCount();

            boolean initializer = "initializer".equals(args[0]);
            ExecutionResult timedOut = executor.execute(blockingTest(
                    initializer ? "enterInitializer" : "blockOrdinaryWorker"));
            check(entered.await(1, TimeUnit.SECONDS), "worker never entered the fixture");
            check(timedOut.hasTimeout(), "fixture must traverse real timeout handling");
            check(executor.getTotalRotationCount() == rotations + 1,
                    "fixture must traverse real executor rotation");
            check(retired.isShutdown(), "retired pool should stop accepting tasks");
            if (!initializer) {
                check(retired.shutdownNowCalls == 1 && retired.shutdownCalls == 0,
                        "ordinary stalled workers must retain interrupting shutdown");
                check(ordinaryInterrupts.get() > 0, "ordinary worker was never interrupted");
            }
            check(executor.execute(simpleTest(), 5000).noThrownExceptions(),
                    "replacement pool could not execute a fresh test");

            release.countDown();
            blockedWorker.join(3000);
            check(!blockedWorker.isAlive(), "released retired worker did not finish");
            if (initializer) {
                // On the broken rotation, await throws and poisons this class. This later
                // access then reports NoClassDefFoundError even though the latch is released.
                check(LatchInitializer.answer() == 42,
                        "initializer should remain usable after abandonment and release");
                check(retired.shutdownCalls == 1 && retired.shutdownNowCalls == 0,
                        "abandoned initializer must receive non-interrupting shutdown");
                check(executor.execute(blockingTest("enterInitializer"), 5000).noThrownExceptions(),
                        "subsequent SUT access should work on the replacement pool");
            }
            System.out.println("rotation scenario passed: " + args[0]);
        } finally {
            release.countDown();
            if (blockedWorker != null) {
                blockedWorker.join(2000);
            }
            ExecutionTracer.setKillSwitch(false);
            TestCaseExecutor.pullDown();
        }
    }

    private static DefaultTestCase simpleTest() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new StringPrimitiveStatement(test, "replacement"));
        return test;
    }

    private static DefaultTestCase blockingTest(String methodName) throws Exception {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new MethodStatement(test, new GenericMethod(
                ExecutorRotationAbandonmentFixture.class.getMethod(methodName),
                ExecutorRotationAbandonmentFixture.class), null, Collections.emptyList()));
        return test;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** Records rotation's pool API without replacing its real scheduling or worker factory. */
    private static class RecordingPool extends AbstractExecutorService {
        private final ExecutorService delegate;
        private int shutdownCalls;
        private int shutdownNowCalls;

        RecordingPool(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void shutdown() {
            shutdownCalls++;
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdownNowCalls++;
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(command);
        }
    }
}
