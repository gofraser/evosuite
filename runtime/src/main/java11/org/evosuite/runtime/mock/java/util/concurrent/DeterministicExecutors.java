/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.util.concurrent;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.Objects;

final class DeterministicExecutors {

    private DeterministicExecutors() {
    }

    static ExecutorService newExecutorService() {
        return new DeterministicScheduledExecutorService();
    }

    static ScheduledExecutorService newScheduledExecutorService() {
        return new DeterministicScheduledExecutorService();
    }

    private static final class DeterministicScheduledExecutorService extends AbstractExecutorService
            implements ScheduledExecutorService {

        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            Objects.requireNonNull(command);
            ensureOpen();
            command.run();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            Objects.requireNonNull(command);
            Objects.requireNonNull(unit);
            execute(command);
            return new CompletedScheduledFuture<>(null, null);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            Objects.requireNonNull(callable);
            Objects.requireNonNull(unit);
            ensureOpen();
            try {
                return new CompletedScheduledFuture<>(callable.call(), null);
            } catch (Throwable t) {
                return new CompletedScheduledFuture<>(null, t);
            }
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            Objects.requireNonNull(command);
            Objects.requireNonNull(unit);
            if (period <= 0L) {
                throw new IllegalArgumentException("period must be > 0");
            }
            execute(command);
            return new CompletedScheduledFuture<>(null, null);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            Objects.requireNonNull(command);
            Objects.requireNonNull(unit);
            if (delay <= 0L) {
                throw new IllegalArgumentException("delay must be > 0");
            }
            execute(command);
            return new CompletedScheduledFuture<>(null, null);
        }

        private void ensureOpen() {
            if (shutdown) {
                throw new RejectedExecutionException("Executor already shutdown");
            }
        }
    }

    private static final class CompletedScheduledFuture<V> implements ScheduledFuture<V> {

        private final V value;
        private final Throwable failure;

        private CompletedScheduledFuture(V value, Throwable failure) {
            this.value = value;
            this.failure = failure;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed o) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public V get() throws ExecutionException {
            if (failure != null) {
                throw new ExecutionException(failure);
            }
            return value;
        }

        @Override
        public V get(long timeout, TimeUnit unit) throws ExecutionException {
            return get();
        }
    }
}
