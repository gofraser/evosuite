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
package org.evosuite.runtime.sandbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

@SuppressWarnings("removal")
public class SandboxResilienceTest {

    @AfterEach
    public void cleanupSandboxState() throws Exception {
        setCounter(0);
        setManager(null);
        try {
            System.setSecurityManager(null);
        } catch (SecurityException | UnsupportedOperationException ignored) {
            // If a foreign manager denies changes or SecurityManager is not supported
            // (Java 24+), tests should still leave Sandbox static state clean.
        }
    }

    @Test
    public void abandonedThreadCannotDisturbSandboxStateOfTheCurrentExecution() throws Exception {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported());
        Assumptions.assumeFalse(Sandbox.isSecurityManagerInitialized());

        Sandbox.initializeSecurityManagerForSUT();
        MSecurityManager manager = getManager();
        int counterBefore = getCounter();

        java.util.concurrent.CountDownLatch inSandbox = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch resume = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread orphan = new Thread(() -> {
            try {
                Sandbox.goingToExecuteSUTCode();
                inSandbox.countDown();
                resume.await();
                // the teardown a JUnit check runner performs when it finally unwinds
                Sandbox.doneWithExecutingSUTCode();
                Sandbox.resetDefaultSecurityManager();
                Sandbox.initializeSecurityManagerForSUT();
                Sandbox.goingToExecuteSUTCode();
                Sandbox.doneWithExecutingSUTCode();
                Sandbox.resetDefaultSecurityManager();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "orphan-runner");
        Sandbox.addPrivilegedThread(orphan);
        orphan.start();
        try {
            Assertions.assertTrue(inSandbox.await(10, java.util.concurrent.TimeUnit.SECONDS));
            Assertions.assertTrue(Sandbox.abandonThread(orphan));
            Assertions.assertFalse(Sandbox.isOnAndExecutingSUTCode());

            // this thread now runs a test case
            Sandbox.goingToExecuteSUTCode();
            Assertions.assertTrue(Sandbox.isOnAndExecutingSUTCode());

            resume.countDown();
            orphan.join(10_000);
            Assertions.assertFalse(orphan.isAlive());
            Assertions.assertNull(failure.get());

            Assertions.assertTrue(Sandbox.isSecurityManagerInitialized());
            Assertions.assertSame(manager, getManager());
            Assertions.assertEquals(counterBefore, getCounter());
            Assertions.assertTrue(Sandbox.isOnAndExecutingSUTCode());
            Assertions.assertDoesNotThrow(() -> org.evosuite.runtime.jvm.ShutdownHookHandler
                    .getInstance().safeExecuteAddedHooks());

            Sandbox.doneWithExecutingSUTCode();
            Assertions.assertFalse(Sandbox.isOnAndExecutingSUTCode());
        } finally {
            resume.countDown();
        }
    }

    /**
     * SUT code that clears the thread locals of all threads (through reflection)
     * must not leave the privileged thread that ran it stripped of its privilege, or the
     * generated tests can no longer be written.
     */
    @Test
    public void privilegedThreadIsRestoredAlthoughTheSutClearedItsThreadLocals() throws Exception {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported());
        Assumptions.assumeFalse(Sandbox.isSecurityManagerInitialized());

        Sandbox.initializeSecurityManagerForSUT();
        MSecurityManager manager = getManager();
        Assertions.assertFalse(manager.isSafeToExecuteSUTCode(), "the test thread starts privileged");

        Sandbox.goingToExecuteSUTCode();
        Assertions.assertTrue(manager.isSafeToExecuteSUTCode(), "SUT code runs sandboxed");

        Field threadLocals = Thread.class.getDeclaredField("threadLocals");
        threadLocals.setAccessible(true);
        threadLocals.set(Thread.currentThread(), null);

        Sandbox.doneWithExecutingSUTCode();
        Assertions.assertFalse(manager.isSafeToExecuteSUTCode(),
                "the thread is privileged again after the SUT code");
        Sandbox.resetDefaultSecurityManager();
    }

    /** What Inspector.getValue does around an inspector call. */
    private static void inspectorLikeEnterAndExit() {
        boolean needsSandbox = !Sandbox.isOnAndExecutingSUTCode();
        if (needsSandbox) {
            Sandbox.goingToExecuteSUTCode();
        }
        if (needsSandbox) {
            Sandbox.doneWithExecutingSUTCode();
        }
    }

    @Test
    public void abandonedWorkerCannotClearTheFlagOfTheNextTestExecution() throws Exception {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported());
        Assumptions.assumeFalse(Sandbox.isSecurityManagerInitialized());

        Sandbox.initializeSecurityManagerForSUT();
        MSecurityManager manager = getManager();

        java.util.concurrent.CountDownLatch timedOut = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch nextTestRunning = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread workerA = new Thread(() -> {
            try {
                timedOut.await();
                nextTestRunning.await();
                // observers of the timed-out test, running during the next test
                inspectorLikeEnterAndExit();
                inspectorLikeEnterAndExit();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "worker-A");
        workerA.start();
        try {
            // worker A times out: the waiting thread gives up on it
            Sandbox.abandonThread(workerA);
            timedOut.countDown();

            // the next test execution (worker B, here the test thread) starts
            Sandbox.goingToExecuteSUTCode();
            Assertions.assertTrue(Sandbox.isOnAndExecutingSUTCode());
            nextTestRunning.countDown();
            workerA.join(10_000);
            Assertions.assertFalse(workerA.isAlive());
            Assertions.assertNull(failure.get());

            Assertions.assertSame(manager, getManager());
            Assertions.assertTrue(Sandbox.isOnAndExecutingSUTCode());
            Sandbox.doneWithExecutingSUTCode();
            Assertions.assertFalse(Sandbox.isOnAndExecutingSUTCode());
        } finally {
            timedOut.countDown();
            nextTestRunning.countDown();
        }
    }

    @Test
    public void reinstatedPooledThreadIsNotMuted() throws Exception {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported());
        Assumptions.assumeFalse(Sandbox.isSecurityManagerInitialized());

        Sandbox.initializeSecurityManagerForSUT();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            Thread[] worker = new Thread[1];
            pool.submit(() -> worker[0] = Thread.currentThread()).get(10, java.util.concurrent.TimeUnit.SECONDS);

            // the pooled thread times out on a test and is abandoned...
            Sandbox.abandonThread(worker[0]);
            boolean mutedEntered = pool.submit(() -> {
                Sandbox.goingToExecuteSUTCode();
                return Sandbox.isOnAndExecutingSUTCode();
            }).get(10, java.util.concurrent.TimeUnit.SECONDS);
            Assertions.assertFalse(mutedEntered);

            // ...and is then handed the next test, which reinstates it first
            boolean enteredAfterReuse = pool.submit(() -> {
                Sandbox.reinstateThread(Thread.currentThread());
                Sandbox.goingToExecuteSUTCode();
                boolean on = Sandbox.isOnAndExecutingSUTCode();
                Sandbox.doneWithExecutingSUTCode();
                return on;
            }).get(10, java.util.concurrent.TimeUnit.SECONDS);
            Assertions.assertTrue(enteredAfterReuse);
            Assertions.assertFalse(Sandbox.isOnAndExecutingSUTCode());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void resetShouldRetainInternalStateWhenRestoreIsDenied() throws Exception {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported());

        setManager(new ThrowingRestoreManager());
        setCounter(1);

        Assertions.assertDoesNotThrow(Sandbox::resetDefaultSecurityManager);
        Assertions.assertTrue(Sandbox.isSecurityManagerInitialized());
        Assertions.assertEquals(1, getCounter());
    }

    @Test
    public void deniedResetKeepsInstalledManagerAndPrivilegedThreadsUsable() throws Exception {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported());
        Assumptions.assumeFalse(Sandbox.isSecurityManagerInitialized());

        SecurityManager originalManager = System.getSecurityManager();
        MSecurityManager manager = null;
        boolean downgraded = false;
        try {
            Sandbox.initializeSecurityManagerForSUT();
            manager = getManager();
            Thread currentThread = Thread.currentThread();
            manager.goingToExecuteUnsafeCodeOnSameThread();
            downgraded = true;

            Assertions.assertDoesNotThrow(Sandbox::resetDefaultSecurityManager);

            Assertions.assertTrue(Sandbox.isSecurityManagerInitialized());
            Assertions.assertSame(manager, System.getSecurityManager());
            Assertions.assertDoesNotThrow(() -> Sandbox.addPrivilegedThread(currentThread));
            Thread additionalThread = new Thread(() -> { }, "sandbox-regression-privileged");
            Assertions.assertDoesNotThrow(() -> Sandbox.addPrivilegedThread(additionalThread));
            Assertions.assertTrue(manager.getPrivilegedThreads().contains(additionalThread));

            manager.doneWithExecutingUnsafeCodeOnSameThread();
            downgraded = false;
            Sandbox.resetDefaultSecurityManager();

            Assertions.assertFalse(Sandbox.isSecurityManagerInitialized());
            Assertions.assertSame(originalManager, System.getSecurityManager());
        } finally {
            if (downgraded && manager != null) {
                manager.doneWithExecutingUnsafeCodeOnSameThread();
            }
            if (Sandbox.isSecurityManagerInitialized()) {
                Sandbox.resetDefaultSecurityManager();
            }
            if (System.getSecurityManager() != originalManager) {
                System.setSecurityManager(originalManager);
            }
        }
    }

    @Test
    public void initializeShouldRecoverFromDesynchronizedState() throws Exception {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported());

        setManager(new MSecurityManager());
        setCounter(1);

        Sandbox.initializeSecurityManagerForSUT();

        Assertions.assertTrue(Sandbox.isSecurityManagerInitialized());
        Assertions.assertNotNull(System.getSecurityManager());
        Assertions.assertSame(getManager(), System.getSecurityManager());

        Sandbox.resetDefaultSecurityManager();
        Assertions.assertFalse(Sandbox.isSecurityManagerInitialized());
    }

    @Test
    public void privilegedThreadShouldBeSandboxedDuringSutExecution() {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported());

        Sandbox.initializeSecurityManagerForSUT();
        try {
            Sandbox.goingToExecuteSUTCode();
            Assertions.assertThrows(SecurityException.class,
                    () -> System.setSecurityManager(null),
                    "SUT execution on privileged thread must still deny setSecurityManager");
        } finally {
            Sandbox.doneWithExecutingSUTCode();
            Sandbox.resetDefaultSecurityManager();
        }
    }

    private static MSecurityManager getManager() throws Exception {
        Field field = Sandbox.class.getDeclaredField("manager");
        field.setAccessible(true);
        return (MSecurityManager) field.get(null);
    }

    private static void setManager(MSecurityManager value) throws Exception {
        Field field = Sandbox.class.getDeclaredField("manager");
        field.setAccessible(true);
        field.set(null, value);
    }

    private static int getCounter() throws Exception {
        Field field = Sandbox.class.getDeclaredField("counter");
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static void setCounter(int value) throws Exception {
        Field field = Sandbox.class.getDeclaredField("counter");
        field.setAccessible(true);
        field.setInt(null, value);
    }

    private static class ThrowingRestoreManager extends MSecurityManager {
        @Override
        public void restoreDefaultManager() throws SecurityException {
            throw new SecurityException("Permission Denied");
        }
    }
}
