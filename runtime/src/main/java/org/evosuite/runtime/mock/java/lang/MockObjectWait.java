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

import java.util.concurrent.atomic.AtomicLong;

/**
 * Mock replacements for {@link Object#wait()}, {@link Object#notify()}, and
 * {@link Object#notifyAll()}.
 *
 * <p>When the mock framework is enabled, {@code wait()} calls are capped to a
 * maximum of 50ms per invocation (matching the {@link MockThread#sleep(long)}
 * strategy) and a cumulative time budget is enforced.  When the budget is
 * exceeded, {@link TooManyResourcesException} is thrown to break infinite
 * wait-loops in the SUT that would otherwise hang test generation.
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
     * throw {@link TooManyResourcesException}.  Matches the budget used
     * by {@link MockThread} for sleep calls.
     */
    private static final long MAX_CUMULATIVE_WAIT_MS = 10_000L;

    private static final AtomicLong cumulativeWaitRequested = new AtomicLong(0L);

    /**
     * Resets the cumulative wait budget.  Called between test executions
     * from {@link org.evosuite.runtime.Runtime#resetRuntime()}.
     */
    public static void reset() {
        cumulativeWaitRequested.set(0L);
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

    private static void doMockedWait(Object obj, long requestedMillis) throws InterruptedException {
        // Unlike MockThread.sleep (which does a real capped sleep), we do NOT
        // perform a real obj.wait() here.  Object.wait() is almost always called
        // inside a while(condition) loop; with the mock the condition never
        // changes, so the loop spins until the budget fires.  A real 50ms wait
        // per iteration would accumulate to seconds of wall-clock time and
        // trigger EvoSuite's test-execution timeout before our budget kicks in.
        //
        // Instead we return immediately and only track cumulative "virtual"
        // wait time.  The budget fires after ~200 loop iterations (10s virtual
        // time), but in near-zero real time.
        long effectiveMillis = (requestedMillis <= 0)
                ? MAX_WAIT_PER_CALL_MS
                : Math.min(requestedMillis, MAX_WAIT_PER_CALL_MS);

        long cumulative = cumulativeWaitRequested.addAndGet(effectiveMillis);
        if (cumulative > MAX_CUMULATIVE_WAIT_MS) {
            throw new TooManyResourcesException(
                    "MockObjectWait: cumulative wait request exceeded "
                            + MAX_CUMULATIVE_WAIT_MS + "ms");
        }
    }
}
