/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.runtime.mock.java.util.concurrent;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.StaticReplacementMock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.Objects;

/**
 * Deterministic replacement for {@link Executors} static factories.
 */
public class MockExecutors implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return Executors.class.getName();
    }

    public static ExecutorService newSingleThreadExecutor() {
        if (!MockFramework.isEnabled()) {
            return Executors.newSingleThreadExecutor();
        }
        return DeterministicExecutors.newExecutorService();
    }

    public static ExecutorService newSingleThreadExecutor(ThreadFactory threadFactory) {
        Objects.requireNonNull(threadFactory);
        if (!MockFramework.isEnabled()) {
            return Executors.newSingleThreadExecutor(threadFactory);
        }
        return DeterministicExecutors.newExecutorService();
    }

    public static ExecutorService newFixedThreadPool(int nThreads) {
        if (nThreads <= 0) {
            throw new IllegalArgumentException();
        }
        if (!MockFramework.isEnabled()) {
            return Executors.newFixedThreadPool(nThreads);
        }
        return DeterministicExecutors.newExecutorService();
    }

    public static ExecutorService newFixedThreadPool(int nThreads, ThreadFactory threadFactory) {
        if (nThreads <= 0) {
            throw new IllegalArgumentException();
        }
        Objects.requireNonNull(threadFactory);
        if (!MockFramework.isEnabled()) {
            return Executors.newFixedThreadPool(nThreads, threadFactory);
        }
        return DeterministicExecutors.newExecutorService();
    }

    public static ExecutorService newCachedThreadPool() {
        if (!MockFramework.isEnabled()) {
            return Executors.newCachedThreadPool();
        }
        return DeterministicExecutors.newExecutorService();
    }

    public static ExecutorService newCachedThreadPool(ThreadFactory threadFactory) {
        Objects.requireNonNull(threadFactory);
        if (!MockFramework.isEnabled()) {
            return Executors.newCachedThreadPool(threadFactory);
        }
        return DeterministicExecutors.newExecutorService();
    }

    public static ExecutorService newWorkStealingPool() {
        if (!MockFramework.isEnabled()) {
            return Executors.newWorkStealingPool();
        }
        return DeterministicExecutors.newExecutorService();
    }

    public static ExecutorService newWorkStealingPool(int parallelism) {
        if (parallelism <= 0) {
            throw new IllegalArgumentException();
        }
        if (!MockFramework.isEnabled()) {
            return Executors.newWorkStealingPool(parallelism);
        }
        return DeterministicExecutors.newExecutorService();
    }

    public static ScheduledExecutorService newSingleThreadScheduledExecutor() {
        if (!MockFramework.isEnabled()) {
            return Executors.newSingleThreadScheduledExecutor();
        }
        return DeterministicExecutors.newScheduledExecutorService();
    }

    public static ScheduledExecutorService newSingleThreadScheduledExecutor(ThreadFactory threadFactory) {
        Objects.requireNonNull(threadFactory);
        if (!MockFramework.isEnabled()) {
            return Executors.newSingleThreadScheduledExecutor(threadFactory);
        }
        return DeterministicExecutors.newScheduledExecutorService();
    }

    public static ScheduledExecutorService newScheduledThreadPool(int corePoolSize) {
        if (corePoolSize < 0) {
            throw new IllegalArgumentException();
        }
        if (!MockFramework.isEnabled()) {
            return Executors.newScheduledThreadPool(corePoolSize);
        }
        return DeterministicExecutors.newScheduledExecutorService();
    }

    public static ScheduledExecutorService newScheduledThreadPool(int corePoolSize, ThreadFactory threadFactory) {
        if (corePoolSize < 0) {
            throw new IllegalArgumentException();
        }
        Objects.requireNonNull(threadFactory);
        if (!MockFramework.isEnabled()) {
            return Executors.newScheduledThreadPool(corePoolSize, threadFactory);
        }
        return DeterministicExecutors.newScheduledExecutorService();
    }
}
