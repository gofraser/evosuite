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
package org.evosuite.utils;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A single worker thread for work that may hang, such as the Java compiler, the decompiler or
 * RMI calls. A task that does not finish within its timeout is cancelled, and since it may
 * ignore the interrupt, the worker is replaced: the next task then runs on a fresh thread
 * instead of queueing behind the stuck one.
 *
 * <p>The caller blocks while the task runs, so a worker serves one task at a time. The thread
 * factory is the place to name the thread and register it as privileged with the sandbox.
 */
public final class ReplaceableWorker {

    private final ThreadFactory threadFactory;
    private final Object lock = new Object();
    private volatile ExecutorService executor;
    private volatile Thread thread;

    public ReplaceableWorker(ThreadFactory threadFactory) {
        this.threadFactory = threadFactory;
        this.executor = newExecutor();
    }

    private ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread created = threadFactory.newThread(r);
            thread = created;
            return created;
        });
    }

    /** The thread of the current executor, or {@code null} if it has not started one yet. */
    public Thread thread() {
        return thread;
    }

    /**
     * Runs {@code task} on the worker and waits for it.
     *
     * <p>On a timeout or an interrupt of the waiting thread the task is cancelled and the
     * worker replaced before the exception is rethrown (the interrupt flag is not restored).
     * A failure of the task itself is rethrown as {@link ExecutionException}, and the worker
     * stays in use. A {@link java.util.concurrent.RejectedExecutionException} from a worker
     * that has been shut down is rethrown as is.
     */
    public <T> T call(Callable<T> task, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        ExecutorService used = executor;
        Future<T> future = used.submit(task);
        try {
            return future.get(timeout, unit);
        } catch (TimeoutException | InterruptedException e) {
            future.cancel(true);
            replace(used);
            throw e;
        }
    }

    /** Cancels the work of the current worker and provisions a fresh one. */
    public void reset() {
        replace(executor);
    }

    /**
     * Shuts down {@code expected} and provisions a fresh worker, unless another caller has
     * replaced it already.
     */
    public void replace(ExecutorService expected) {
        synchronized (lock) {
            if (executor != expected) {
                return;
            }
            try {
                expected.shutdownNow();
            } finally {
                thread = null;
                executor = newExecutor();
            }
        }
    }

    /** Installs {@code replacement} as the worker and shuts the previous one down. */
    public void setForTesting(ExecutorService replacement) {
        if (replacement == null) {
            throw new IllegalArgumentException("Worker executor must not be null");
        }
        synchronized (lock) {
            ExecutorService previous = executor;
            executor = replacement;
            thread = null;
            previous.shutdownNow();
        }
    }
}
