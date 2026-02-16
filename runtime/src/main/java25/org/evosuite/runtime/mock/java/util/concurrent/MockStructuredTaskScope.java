/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.util.concurrent;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.StaticReplacementMock;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;

/**
 * Static replacement hooks for Java 25 {@link StructuredTaskScope}.
 *
 * <p>Both {@code StructuredTaskScope} and {@code Subtask} are sealed JDK APIs,
 * so EvoSuite cannot provide custom implementations. In mocked mode, we enforce
 * a deterministic inline thread factory while still using real JDK scope
 * instances.</p>
 */
public class MockStructuredTaskScope implements StaticReplacementMock {

    private static final ThreadFactory INLINE_THREAD_FACTORY = runnable ->
            new InlineStartThread(runnable, "evosuite-structured");

    @Override
    public String getMockedClassName() {
        return StructuredTaskScope.class.getName();
    }

    public static <T, R> StructuredTaskScope<T, R> open(
            StructuredTaskScope.Joiner<? super T, ? extends R> joiner,
            Function<StructuredTaskScope.Configuration, StructuredTaskScope.Configuration> configFunction) {
        Objects.requireNonNull(joiner);
        Objects.requireNonNull(configFunction);
        if (!MockFramework.isEnabled()) {
            return StructuredTaskScope.open(joiner, configFunction);
        }
        return StructuredTaskScope.open(joiner, cfg -> {
            StructuredTaskScope.Configuration configured = Objects.requireNonNull(configFunction.apply(cfg));
            return configured.withThreadFactory(INLINE_THREAD_FACTORY);
        });
    }

    public static <T, R> StructuredTaskScope<T, R> open(StructuredTaskScope.Joiner<? super T, ? extends R> joiner) {
        Objects.requireNonNull(joiner);
        if (!MockFramework.isEnabled()) {
            return StructuredTaskScope.open(joiner);
        }
        return StructuredTaskScope.open(joiner, cfg -> cfg.withThreadFactory(INLINE_THREAD_FACTORY));
    }

    public static <T> StructuredTaskScope<T, Void> open() {
        if (!MockFramework.isEnabled()) {
            return StructuredTaskScope.open();
        }
        return StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAll(),
                cfg -> cfg.withThreadFactory(INLINE_THREAD_FACTORY));
    }

    public static <T, R, U extends T> StructuredTaskScope.Subtask<U> fork(
            StructuredTaskScope<T, R> scope,
            Callable<? extends U> task) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(task);
        return scope.fork(task);
    }

    public static <T, R, U extends T> StructuredTaskScope.Subtask<U> fork(
            StructuredTaskScope<T, R> scope,
            Runnable task) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(task);
        return scope.fork(task);
    }

    public static <T, R> R join(StructuredTaskScope<T, R> scope) throws InterruptedException {
        Objects.requireNonNull(scope);
        return scope.join();
    }

    public static <T, R> boolean isCancelled(StructuredTaskScope<T, R> scope) {
        Objects.requireNonNull(scope);
        return scope.isCancelled();
    }

    public static <T, R> void close(StructuredTaskScope<T, R> scope) {
        Objects.requireNonNull(scope);
        scope.close();
    }

    /**
     * Utility used by tests to validate config function behavior.
     */
    public static StructuredTaskScope.Configuration validateConfiguration(StructuredTaskScope.Configuration configuration) {
        Objects.requireNonNull(configuration);
        configuration = configuration.withThreadFactory(INLINE_THREAD_FACTORY);
        configuration = configuration.withName("evosuite");
        return configuration.withTimeout(Duration.ofSeconds(1));
    }

    private static final class InlineStartThread extends Thread {
        private InlineStartThread(Runnable target, String name) {
            super(target, name);
        }

        @Override
        public synchronized void start() {
            run();
        }
    }
}
