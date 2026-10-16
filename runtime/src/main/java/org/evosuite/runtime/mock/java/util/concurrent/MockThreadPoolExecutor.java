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
package org.evosuite.runtime.mock.java.util.concurrent;

import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.OverrideMock;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Mock for {@link ThreadPoolExecutor} that clamps an outrageous core/maximum pool size
 * to a sane bound before it ever reaches the real constructor.
 *
 * <p>A generated test constructs a {@code ThreadPoolExecutor} with a pool size taken
 * straight from the search (an {@code int} parameter, unconstrained), which has been
 * observed up to 2,145,463,437. The worker threads {@code ThreadPoolExecutor} spawns to
 * fill such a pool are created by its own {@link ThreadFactory} deep inside
 * java.util.concurrent, i.e. by uninstrumented JDK code that never calls
 * {@code Thread.start()} the way our instrumentation expects, so
 * {@link org.evosuite.runtime.thread.ThreadCounter} (which enforces
 * {@link RuntimeSettings#maxNumberOfThreads}) never gets a chance to reject them. The
 * result is a test that can exhaust the JVM's native threads ("unable to create native
 * thread") and, since core threads do not time out by default, leaves them running after
 * the test if the pool is never shut down.
 *
 * <p>The clamp reuses {@link RuntimeSettings#maxNumberOfThreads} -- the very same budget
 * {@code ThreadCounter} enforces for directly-started threads -- rather than a second,
 * unrelated constant: a pool should not be able to promise more worker capacity than a
 * test is otherwise allowed to start.
 *
 * <p>Only the pool size is touched, and only once the requested sizes are confirmed to be
 * a relation the real constructor would accept. A negative {@code corePoolSize}, a
 * {@code maximumPoolSize <= 0}, or a {@code corePoolSize > maximumPoolSize} is passed
 * through unchanged, so the real constructor's own precondition check still throws
 * {@code IllegalArgumentException} exactly as the JDK does and with the original numbers --
 * clamping each bound independently first (eg {@code core=10, max=5} under a cap of 4) would
 * otherwise turn two values that violate that relation into two equal, individually legal
 * ones and silently swallow the exception the SUT is entitled to see. Because the clamp is
 * the monotonic {@code min(x, cap)}, it can never turn an already-legal call (where
 * {@code corePoolSize <= maximumPoolSize}) into an illegal one.
 *
 * <p>The generated test source still shows the original, unclamped literal -- the clamp
 * only takes effect at runtime -- so the test remains a faithful record of what the
 * search produced.
 */
public class MockThreadPoolExecutor extends ThreadPoolExecutor implements OverrideMock {

    public MockThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
                                  BlockingQueue<Runnable> workQueue) {
        super(clampCore(corePoolSize, maximumPoolSize), clampMax(corePoolSize, maximumPoolSize),
                keepAliveTime, unit, workQueue);
    }

    public MockThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
                                  BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory) {
        super(clampCore(corePoolSize, maximumPoolSize), clampMax(corePoolSize, maximumPoolSize),
                keepAliveTime, unit, workQueue, threadFactory);
    }

    public MockThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
                                  BlockingQueue<Runnable> workQueue, RejectedExecutionHandler handler) {
        super(clampCore(corePoolSize, maximumPoolSize), clampMax(corePoolSize, maximumPoolSize),
                keepAliveTime, unit, workQueue, handler);
    }

    public MockThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
                                  BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory,
                                  RejectedExecutionHandler handler) {
        super(clampCore(corePoolSize, maximumPoolSize), clampMax(corePoolSize, maximumPoolSize),
                keepAliveTime, unit, workQueue, threadFactory, handler);
    }

    /**
     * Whether the real constructor would accept this core/maximum relation on its own terms
     * (ignoring {@code keepAliveTime}, which this mock never touches). Mirrors
     * {@code ThreadPoolExecutor}'s own precondition: {@code corePoolSize >= 0},
     * {@code maximumPoolSize > 0} and {@code corePoolSize <= maximumPoolSize}.
     */
    private static boolean isValidPoolSizeRelation(int corePoolSize, int maximumPoolSize) {
        return corePoolSize >= 0 && maximumPoolSize > 0 && corePoolSize <= maximumPoolSize;
    }

    /**
     * The core pool size to actually construct with: clamped to
     * {@link RuntimeSettings#maxNumberOfThreads} when the requested sizes are a relation the
     * real constructor would accept, otherwise the original, unclamped value so the real
     * constructor's own check throws on the numbers the SUT actually passed.
     */
    static int clampCore(int corePoolSize, int maximumPoolSize) {
        if (!isValidPoolSizeRelation(corePoolSize, maximumPoolSize)) {
            return corePoolSize;
        }
        return clamp(corePoolSize);
    }

    /**
     * The maximum pool size to actually construct with; see {@link #clampCore}.
     */
    static int clampMax(int corePoolSize, int maximumPoolSize) {
        if (!isValidPoolSizeRelation(corePoolSize, maximumPoolSize)) {
            return maximumPoolSize;
        }
        return clamp(maximumPoolSize);
    }

    /**
     * Clamps a requested pool size to {@link RuntimeSettings#maxNumberOfThreads}, leaving
     * anything that is not a "too large" value (in particular, negative or zero) untouched
     * so the real constructor's own validation still fires on it.
     *
     * @param poolSize the requested core or maximum pool size.
     * @return the clamped pool size.
     */
    static int clamp(int poolSize) {
        if (poolSize < 0) {
            return poolSize;
        }
        int cap = Math.max(1, RuntimeSettings.maxNumberOfThreads);
        return Math.min(poolSize, cap);
    }
}
