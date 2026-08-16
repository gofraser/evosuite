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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the sandbox execution lock of {@link MSecurityManager}, i.e. the
 * mutual exclusion around {@link MSecurityManager#goingToExecuteTestCase()} /
 * {@link MSecurityManager#goingToEndTestCase()}.
 *
 * <p>The interesting property is not plain mutual exclusion but what happens
 * when the owner of the lock is killed asynchronously (EvoSuite force-stops
 * stuck test threads with {@code Thread.stop()} and abandons wedged JUnit check
 * runners): the lock must be reclaimable by somebody else, and reclaiming must
 * never hand back more permits than were taken.
 *
 * <p>Note: the security manager is deliberately never {@code apply()}'d here —
 * these tests exercise pure bookkeeping and must not install a JVM-wide
 * SecurityManager.
 */
public class MSecurityManagerSandboxLockTest {

    /**
     * Short acquisition timeout so the reclaim paths, which by construction only
     * trigger after a full timeout, are reachable in milliseconds rather than in
     * the production five minutes.
     */
    private static final long TIMEOUT_MS = 500;

    /**
     * Window used to check that a contender is kept out. Comfortably shorter
     * than {@link #TIMEOUT_MS}, so a contender that is still waiting cannot yet
     * have reclaimed the permit from the thread legitimately holding it.
     */
    private static final long BLOCKED_PROBE_MS = 150;

    private MSecurityManager securityManager;
    private long originalTimeoutMs;

    @BeforeEach
    public void initTest() {
        // A fresh instance per test: the permit and its ownership are instance
        // state, so no test can leak them into the next one.
        securityManager = new MSecurityManager();
        originalTimeoutMs = MSecurityManager.getSandboxLockAcquireTimeoutMs();
        MSecurityManager.setSandboxLockAcquireTimeoutMs(TIMEOUT_MS);
    }

    @AfterEach
    public void restoreState() {
        // The timeout is process-global, so it has to be put back.
        MSecurityManager.setSandboxLockAcquireTimeoutMs(originalTimeoutMs);
        if (securityManager != null) {
            securityManager.forceUnlockSandboxLockIfHeld();
            securityManager = null;
        }
    }

    @Test
    public void normalRoundTripsLeaveNoPermitLeak() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertFalse(securityManager.isExecutingTestCase());
            assertFalse(securityManager.isSandboxLockHeldByCurrentThread());

            securityManager.goingToExecuteTestCase();
            assertTrue(securityManager.isExecutingTestCase());
            assertTrue(securityManager.isSandboxLockHeldByCurrentThread());

            securityManager.goingToEndTestCase();
            assertFalse(securityManager.isExecutingTestCase());
            assertFalse(securityManager.isSandboxLockHeldByCurrentThread());
        }

        // Nothing was leaked: another thread can take the lock straight away,
        // and a contender is still properly excluded while it does.
        assertStillAMutex();
    }

    @Test
    public void reentrantEntryIsRejectedWithoutTakingASecondPermit() throws Exception {
        securityManager.goingToExecuteTestCase();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> securityManager.goingToExecuteTestCase());
        assertTrue(failure.getMessage().contains("while executing a test case"), failure.getMessage());

        // The rejected re-entry must not have consumed anything, so the single
        // matching end call is enough to free the lock again.
        assertTrue(securityManager.isSandboxLockHeldByCurrentThread());
        securityManager.goingToEndTestCase();
        assertFalse(securityManager.isSandboxLockHeldByCurrentThread());
        assertFalse(securityManager.isExecutingTestCase());

        assertStillAMutex();
    }

    @Test
    public void deadOwnerDoesNotBlockTheNextAcquirer() throws Exception {
        Thread deadOwner = startLeakingOwner();
        assertFalse(deadOwner.isAlive());

        // With a ReentrantLock this would have blocked forever: only the (now
        // dead) owner could ever have unlocked it.
        long start = System.currentTimeMillis();
        securityManager.goingToExecuteTestCase();
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(securityManager.isSandboxLockHeldByCurrentThread());
        assertTrue(securityManager.isExecutingTestCase());
        // One timeout window to notice the leak, then a reclaim and a retry.
        assertTrue(elapsed < TIMEOUT_MS * 8,
                "Acquiring after a leak took " + elapsed + "ms, expected roughly one timeout window");

        securityManager.goingToEndTestCase();
        assertFalse(securityManager.isSandboxLockHeldByCurrentThread());
        assertFalse(securityManager.isExecutingTestCase());
    }

    @Test
    public void concurrentReclaimsHandBackExactlyOnePermit() throws Exception {
        Thread deadOwner = startLeakingOwner();

        // The classic Semaphore hazard: several threads notice the same lost
        // owner and all call release(), inflating the permit count above 1 and
        // silently destroying mutual exclusion.
        final int reclaimers = 4;
        CyclicBarrier allAtOnce = new CyclicBarrier(reclaimers);
        AtomicInteger reclaimed = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[reclaimers];
        for (int i = 0; i < reclaimers; i++) {
            threads[i] = new Thread(() -> {
                try {
                    allAtOnce.await(TIMEOUT_MS * 10, TimeUnit.MILLISECONDS);
                    if (securityManager.reclaimSandboxLockOwnedBy(deadOwner)) {
                        reclaimed.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "sandbox-lock-reclaimer-" + i);
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join(TIMEOUT_MS * 20);
            assertFalse(t.isAlive());
        }

        assertNull(failure.get(), String.valueOf(failure.get()));
        assertEquals(1, reclaimed.get(), "exactly one thread may hand the lost permit back");
        // The reclaim also clears the flag the dead owner will never clear.
        assertFalse(securityManager.isExecutingTestCase());

        assertStillAMutex();
    }

    @Test
    public void proactiveReclaimIsANoOpForAThreadThatDoesNotOwnTheLock() throws Exception {
        Thread deadOwner = startLeakingOwner();

        assertFalse(securityManager.reclaimSandboxLockOwnedBy(Thread.currentThread()),
                "reclaiming for a non-owner must not release anything");
        assertFalse(securityManager.reclaimSandboxLockOwnedBy(null));
        // Still leaked, so the real owner's permit is untouched and reclaimable.
        assertTrue(securityManager.isExecutingTestCase());

        assertTrue(securityManager.reclaimSandboxLockOwnedBy(deadOwner));
        assertFalse(securityManager.reclaimSandboxLockOwnedBy(deadOwner),
                "a second reclaim must not hand back a second permit");

        assertStillAMutex();
    }

    @Test
    public void forceUnlockFromANonOwnerDoesNotReleaseSomebodyElsesPermit() throws Exception {
        Thread deadOwner = startLeakingOwner();

        // This thread does not own the permit, so the safety net may only clear
        // the global flag — releasing here would be a release of a permit we
        // never took.
        securityManager.forceUnlockSandboxLockIfHeld();
        assertFalse(securityManager.isExecutingTestCase());
        assertTrue(securityManager.reclaimSandboxLockOwnedBy(deadOwner),
                "the permit must still belong to the dead owner");

        assertStillAMutex();
    }

    @Test
    public void endingATestCaseOwnedByAnotherThreadIsRejected() throws Exception {
        Thread deadOwner = startLeakingOwner();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> securityManager.goingToEndTestCase());
        assertTrue(failure.getMessage().contains("owned by"), failure.getMessage());
        // Rejected, and nothing was changed behind the caller's back.
        assertTrue(securityManager.isExecutingTestCase());

        assertTrue(securityManager.reclaimSandboxLockOwnedBy(deadOwner));
        assertStillAMutex();
    }

    @Test
    public void endingATestCaseThatWasNeverStartedIsRejected() throws Exception {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> securityManager.goingToEndTestCase());
        assertTrue(failure.getMessage().contains("not test case was run"), failure.getMessage());

        assertStillAMutex();
    }

    /**
     * Starts a thread that enters the sandbox and then terminates without ever
     * calling {@link MSecurityManager#goingToEndTestCase()} — i.e. exactly what
     * a force-stopped test execution thread leaves behind.
     *
     * @return the (already terminated) leaking owner
     */
    private Thread startLeakingOwner() throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread leaker = new Thread(() -> {
            try {
                securityManager.goingToExecuteTestCase();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "sandbox-lock-leaker");
        leaker.start();
        leaker.join(TIMEOUT_MS * 20);

        assertNull(failure.get(), String.valueOf(failure.get()));
        assertFalse(leaker.isAlive(), "the leaking owner should have terminated");
        assertTrue(securityManager.isExecutingTestCase(), "the leaking owner should have entered the sandbox");
        return leaker;
    }

    /**
     * Asserts that the lock is still a mutex, i.e. that no path has inflated the
     * permit count above 1: while this thread is inside the sandbox no other
     * thread may get in, and once this thread leaves the contender does.
     *
     * <p>Leaves the lock free.
     */
    private void assertStillAMutex() throws Exception {
        securityManager.goingToExecuteTestCase();

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch mayLeave = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread contender = new Thread(() -> {
            try {
                securityManager.goingToExecuteTestCase();
                entered.countDown();
                mayLeave.await(TIMEOUT_MS * 20, TimeUnit.MILLISECONDS);
                securityManager.goingToEndTestCase();
            } catch (Throwable t) {
                failure.set(t);
                entered.countDown();
            }
        }, "sandbox-lock-contender");
        contender.start();

        assertFalse(entered.await(BLOCKED_PROBE_MS, TimeUnit.MILLISECONDS),
                "a second thread got into the sandbox while this one was still inside");

        securityManager.goingToEndTestCase();
        assertTrue(entered.await(TIMEOUT_MS * 20, TimeUnit.MILLISECONDS),
                "the contender never got the lock after it was handed back");

        mayLeave.countDown();
        contender.join(TIMEOUT_MS * 20);
        assertFalse(contender.isAlive());
        assertNull(failure.get(), String.valueOf(failure.get()));
        assertFalse(securityManager.isExecutingTestCase());
        assertFalse(securityManager.isSandboxLockHeldByCurrentThread());
    }
}
