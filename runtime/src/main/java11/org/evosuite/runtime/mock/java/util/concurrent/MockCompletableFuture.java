/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.util.concurrent;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.StaticReplacementMock;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Deterministic replacement for async entry points in {@link CompletableFuture}.
 */
public class MockCompletableFuture implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return CompletableFuture.class.getName();
    }

    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        Objects.requireNonNull(runnable);
        if (!MockFramework.isEnabled()) {
            return CompletableFuture.runAsync(runnable);
        }
        return runImmediately(runnable);
    }

    public static CompletableFuture<Void> runAsync(Runnable runnable, Executor executor) {
        Objects.requireNonNull(runnable);
        Objects.requireNonNull(executor);
        if (!MockFramework.isEnabled()) {
            return CompletableFuture.runAsync(runnable, executor);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                runnable.run();
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier) {
        Objects.requireNonNull(supplier);
        if (!MockFramework.isEnabled()) {
            return CompletableFuture.supplyAsync(supplier);
        }
        return supplyImmediately(supplier);
    }

    public static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier, Executor executor) {
        Objects.requireNonNull(supplier);
        Objects.requireNonNull(executor);
        if (!MockFramework.isEnabled()) {
            return CompletableFuture.supplyAsync(supplier, executor);
        }

        CompletableFuture<U> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public static Executor delayedExecutor(long delay, TimeUnit unit) {
        Objects.requireNonNull(unit);
        if (!MockFramework.isEnabled()) {
            return CompletableFuture.delayedExecutor(delay, unit);
        }
        return Runnable::run;
    }

    public static Executor delayedExecutor(long delay, TimeUnit unit, Executor executor) {
        Objects.requireNonNull(unit);
        Objects.requireNonNull(executor);
        if (!MockFramework.isEnabled()) {
            return CompletableFuture.delayedExecutor(delay, unit, executor);
        }
        return executor::execute;
    }

    public static <U> CompletableFuture<U> completeAsync(CompletableFuture<U> future, Supplier<? extends U> supplier) {
        Objects.requireNonNull(future);
        Objects.requireNonNull(supplier);
        if (!MockFramework.isEnabled()) {
            return future.completeAsync(supplier);
        }
        try {
            future.complete(supplier.get());
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
        return future;
    }

    public static <U> CompletableFuture<U> completeAsync(
            CompletableFuture<U> future,
            Supplier<? extends U> supplier,
            Executor executor) {
        Objects.requireNonNull(future);
        Objects.requireNonNull(supplier);
        Objects.requireNonNull(executor);
        if (!MockFramework.isEnabled()) {
            return future.completeAsync(supplier, executor);
        }
        executor.execute(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public static <U> CompletableFuture<U> orTimeout(CompletableFuture<U> future, long timeout, TimeUnit unit) {
        Objects.requireNonNull(future);
        Objects.requireNonNull(unit);
        if (!MockFramework.isEnabled()) {
            return future.orTimeout(timeout, unit);
        }
        // Deterministic mode does not wait for wall-clock time.
        return future;
    }

    public static <U> CompletableFuture<U> completeOnTimeout(
            CompletableFuture<U> future, U value, long timeout, TimeUnit unit) {
        Objects.requireNonNull(future);
        Objects.requireNonNull(unit);
        if (!MockFramework.isEnabled()) {
            return future.completeOnTimeout(value, timeout, unit);
        }
        if (!future.isDone()) {
            future.complete(value);
        }
        return future;
    }

    public static <U> CompletableFuture<U> failedFuture(Throwable ex) {
        Objects.requireNonNull(ex);
        if (!MockFramework.isEnabled()) {
            return CompletableFuture.failedFuture(ex);
        }
        CompletableFuture<U> future = new CompletableFuture<>();
        future.completeExceptionally(ex);
        return future;
    }

    public static <U> CompletionStage<U> failedStage(Throwable ex) {
        Objects.requireNonNull(ex);
        if (!MockFramework.isEnabled()) {
            return CompletableFuture.failedStage(ex);
        }
        return failedFuture(ex);
    }

    public static <U> CompletionStage<U> completedStage(U value) {
        if (!MockFramework.isEnabled()) {
            return CompletableFuture.completedStage(value);
        }
        return CompletableFuture.completedFuture(value);
    }

    private static CompletableFuture<Void> runImmediately(Runnable runnable) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            runnable.run();
            future.complete(null);
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
        return future;
    }

    private static <U> CompletableFuture<U> supplyImmediately(Supplier<U> supplier) {
        CompletableFuture<U> future = new CompletableFuture<>();
        try {
            future.complete(supplier.get());
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
        return future;
    }
}
