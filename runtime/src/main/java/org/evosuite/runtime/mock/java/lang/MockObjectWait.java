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
package org.evosuite.runtime.mock.java.lang;

import org.evosuite.runtime.TooManyResourcesException;
import org.evosuite.runtime.mock.MockFramework;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mock replacements for {@link Object#wait()}, {@link Object#notify()}, and
 * {@link Object#notifyAll()}.
 *
 * <p>When the mock framework is enabled, {@code wait()} calls advance the
 * mocked clock (see {@link org.evosuite.runtime.System#advanceCurrentTimeMillis(long)})
 * by at most 50ms per invocation, and a cumulative virtual-time budget is
 * enforced.  When the budget is exceeded, {@link TooManyResourcesException}
 * is thrown to break infinite wait-loops in the SUT that would otherwise hang
 * test generation.
 *
 * <p>When the mock framework is disabled, all methods delegate directly to
 * the real {@link Object} monitor methods.
 *
 * <p>{@code notify()} and {@code notifyAll()} always delegate to the real
 * implementation — they are non-blocking and harmless.
 */
public class MockObjectWait {

    private static final long MAX_WAIT_PER_CALL_MS = 50L;

    /**
     * Maximum cumulative wait time (in effective milliseconds) before we
     * throw {@link TooManyResourcesException}.  Each wait counts for at most
     * {@link #MAX_WAIT_PER_CALL_MS}, so a loop is ended after about 200 calls.
     * {@link MockThread#sleep(long)} limits the number of calls instead (10,000 per test).
     */
    private static final long MAX_CUMULATIVE_WAIT_MS = 10_000L;

    /**
     * The real, tiny timed wait each mocked {@code wait()} performs — see
     * {@link #doMockedWait(Object, long)}.
     */
    private static final long REAL_WAIT_MILLIS = 1L;

    /**
     * How many real waits of {@link #REAL_WAIT_MILLIS} the test's own thread performs per
     * test, see {@link #doMockedWait(Object, long)}.
     */
    private static final int MAX_TEST_THREAD_REAL_WAITS = 3;

    private static final int REAL_WAIT_STRIDE = 64;

    private static final AtomicInteger testThreadRealWaits = new AtomicInteger(0);

    private static final AtomicLong cumulativeWaitRequested = new AtomicLong(0L);

    /**
     * Resets the cumulative wait budget.  Called between test executions
     * from {@link org.evosuite.runtime.Runtime#resetRuntime()}.
     */
    public static void reset() {
        cumulativeWaitRequested.set(0L);
        testThreadRealWaits.set(0);
    }

    /**
     * Replacement for {@link Object#wait()}.
     *
     * @param obj the object whose monitor the current thread holds
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public static void wait(Object obj) throws InterruptedException {
        if (!MockFramework.isEnabled()) {
            obj.wait();
            return;
        }
        doMockedWait(obj, 0);
    }

    /**
     * Replacement for {@link Object#wait(long)}.
     *
     * @param obj    the object whose monitor the current thread holds
     * @param millis the maximum time to wait in milliseconds
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public static void wait(Object obj, long millis) throws InterruptedException {
        if (!MockFramework.isEnabled()) {
            obj.wait(millis);
            return;
        }
        doMockedWait(obj, millis);
    }

    /**
     * Replacement for {@link Object#wait(long, int)}.
     *
     * @param obj    the object whose monitor the current thread holds
     * @param millis the maximum time to wait in milliseconds
     * @param nanos  additional time in nanoseconds (0–999999)
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public static void wait(Object obj, long millis, int nanos) throws InterruptedException {
        if (!MockFramework.isEnabled()) {
            obj.wait(millis, nanos);
            return;
        }
        doMockedWait(obj, millis);
    }

    /**
     * Replacement for {@link Object#notify()}.  Always delegates to the real
     * method — it is non-blocking and safe.
     *
     * @param obj the object whose monitor the current thread holds
     */
    public static void notify(Object obj) {
        obj.notify();
    }

    /**
     * Replacement for {@link Object#notifyAll()}.  Always delegates to the
     * real method — it is non-blocking and safe.
     *
     * @param obj the object whose monitor the current thread holds
     */
    public static void notifyAll(Object obj) {
        obj.notifyAll();
    }

    /**
     * The test thread's first few waits of a test are real. After those a wait only is
     * while another thread is blocked on a monitor: it may be one this thread holds, and
     * a wait that never gave it up would leave a thread that needs it to notify blocked
     * for ever, so the waiting loop would run into the budget instead of seeing the
     * notification. A wait loop that nothing ever notifies (it makes about 200 calls)
     * must not pay for real waits, nor let the threads the SUT started run on, which
     * costs the search a lot.
     * Finding out is not free (listing the threads on every wait halved the search's
     * generations), so it is done on every {@link #REAL_WAIT_STRIDE}th wait only; a thread
     * that is blocked stays so until the monitor is given up.
     */
    private static boolean shouldWaitForReal(int waitNumber) {
        return waitNumber <= MAX_TEST_THREAD_REAL_WAITS
                || (waitNumber % REAL_WAIT_STRIDE == 0 && isAnotherThreadBlocked());
    }

    private static boolean isAnotherThreadBlocked() {
        try {
            ThreadGroup root = Thread.currentThread().getThreadGroup();
            while (root.getParent() != null) {
                root = root.getParent();
            }
            Thread[] threads = new Thread[root.activeCount() + 8];
            int n = root.enumerate(threads, true);
            Thread self = Thread.currentThread();
            for (int i = 0; i < n; i++) {
                if (threads[i] != self && threads[i].getState() == Thread.State.BLOCKED) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            // No way to tell: giving the monitor up is the safe answer.
            return true;
        }
    }

    private static void doMockedWait(Object obj, long requestedMillis) throws InterruptedException {
        // Object.wait() is almost always called inside a while(condition)
        // loop; with the mock the condition never changes, so the loop spins
        // until the budget fires.  A real 50ms wait per iteration (the old
        // per-call cap) would accumulate to seconds of wall-clock time and
        // trigger EvoSuite's test-execution timeout before our budget kicks
        // in, so we only track cumulative "virtual" wait time for that
        // purpose: the budget fires after ~200 loop iterations (10s virtual
        // time).
        long effectiveMillis = (requestedMillis <= 0)
                ? MAX_WAIT_PER_CALL_MS
                : Math.min(requestedMillis, MAX_WAIT_PER_CALL_MS);

        // Advance the mocked clock as if the wait had actually elapsed, so that
        // SUT code comparing timestamps taken before/after the wait observes
        // time having passed (see org.evosuite.runtime.System#advanceCurrentTimeMillis).
        org.evosuite.runtime.System.advanceCurrentTimeMillis(effectiveMillis);

        long cumulative = cumulativeWaitRequested.addAndGet(effectiveMillis);

        // Perform a real (but tiny, bounded) timed wait: obj.wait(millis) releases obj's monitor
        // for the duration and re-acquires it before returning, exactly like a real wait() would.
        // Skipping this entirely would leave the calling thread holding obj's *intrinsic* lock for
        // the whole (virtual) wait, which starves other threads in code that calls wait() from
        // inside its own synchronized block in a retry loop. Bounding it at REAL_WAIT_MILLIS keeps
        // it cheap while restoring the "wait() releases the monitor" contract the SUT relies on.
        // A SUT thread can afford the full capped wait: it runs alongside the test,
        // and releasing the monitor for longer is what lets the test thread in (see
        // MockThread.sleep). The test's own thread waits for real only for its first
        // few waits of a test, enough to let a thread the SUT started take the monitor
        // and act: a wait loop that never ends would
        // otherwise pay ~200 real milliseconds on every evaluation, and the search
        // saw a handful of generations.
        if (Thread.currentThread() instanceof MockThread) {
            // Once the test has ended and this leftover thread is being asked to stop, it waits
            // only briefly for real, so the monitor is released and a loop that swallows the
            // exception is no hot spin; see MockThread.sleep and MockThread.STOPPING_REAL_PAUSE_MS.
            MockThread.pauseOnSutThread(() -> obj.wait(MockThread.STOPPING_REAL_PAUSE_MS),
                    () -> obj.wait(effectiveMillis),
                    "MockObjectWait: thread kept waiting after its test ended");
        } else if (shouldWaitForReal(testThreadRealWaits.incrementAndGet())) {
            obj.wait(REAL_WAIT_MILLIS);
        } else {
            // What a real wait would still have done.
            if (!Thread.holdsLock(obj)) {
                throw new IllegalMonitorStateException("current thread is not owner");
            }
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }

        // Only after really giving the monitor up: a wait() loop that swallows
        // this exception must never turn into a busy loop holding the monitor.
        if (cumulative > MAX_CUMULATIVE_WAIT_MS) {
            throw new TooManyResourcesException(
                    "MockObjectWait: cumulative wait request exceeded "
                            + MAX_CUMULATIVE_WAIT_MS + "ms");
        }
    }
}
