/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.lang;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.StaticReplacementMock;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Deterministic replacement for {@link ProcessHandle}.
 */
public class MockProcessHandle implements StaticReplacementMock {

    private static final ProcessHandle DETERMINISTIC = new DeterministicProcessHandle();

    @Override
    public String getMockedClassName() {
        return ProcessHandle.class.getName();
    }

    public static ProcessHandle current() {
        if (!MockFramework.isEnabled()) {
            return ProcessHandle.current();
        }
        return DETERMINISTIC;
    }

    public static Optional<ProcessHandle> of(long pid) {
        if (pid <= 0) {
            throw new IllegalArgumentException("pid must be positive");
        }
        if (!MockFramework.isEnabled()) {
            return ProcessHandle.of(pid);
        }
        return Optional.of(DETERMINISTIC);
    }

    public static Stream<ProcessHandle> allProcesses() {
        if (!MockFramework.isEnabled()) {
            return ProcessHandle.allProcesses();
        }
        return Stream.of(DETERMINISTIC);
    }

    public static long pid(ProcessHandle handle) {
        Objects.requireNonNull(handle);
        if (!MockFramework.isEnabled()) {
            return handle.pid();
        }
        return DETERMINISTIC.pid();
    }

    public static ProcessHandle.Info info(ProcessHandle handle) {
        Objects.requireNonNull(handle);
        if (!MockFramework.isEnabled()) {
            return handle.info();
        }
        return DETERMINISTIC.info();
    }

    public static CompletableFuture<ProcessHandle> onExit(ProcessHandle handle) {
        Objects.requireNonNull(handle);
        if (!MockFramework.isEnabled()) {
            return handle.onExit();
        }
        return CompletableFuture.completedFuture(DETERMINISTIC);
    }

    public static Stream<ProcessHandle> children(ProcessHandle handle) {
        Objects.requireNonNull(handle);
        if (!MockFramework.isEnabled()) {
            return handle.children();
        }
        return Stream.empty();
    }

    public static Stream<ProcessHandle> descendants(ProcessHandle handle) {
        Objects.requireNonNull(handle);
        if (!MockFramework.isEnabled()) {
            return handle.descendants();
        }
        return Stream.empty();
    }

    public static Optional<ProcessHandle> parent(ProcessHandle handle) {
        Objects.requireNonNull(handle);
        if (!MockFramework.isEnabled()) {
            return handle.parent();
        }
        return Optional.empty();
    }

    public static boolean destroy(ProcessHandle handle) {
        Objects.requireNonNull(handle);
        if (!MockFramework.isEnabled()) {
            return handle.destroy();
        }
        return false;
    }

    public static boolean destroyForcibly(ProcessHandle handle) {
        Objects.requireNonNull(handle);
        if (!MockFramework.isEnabled()) {
            return handle.destroyForcibly();
        }
        return false;
    }

    public static boolean isAlive(ProcessHandle handle) {
        Objects.requireNonNull(handle);
        if (!MockFramework.isEnabled()) {
            return handle.isAlive();
        }
        return true;
    }

    public static boolean supportsNormalTermination(ProcessHandle handle) {
        Objects.requireNonNull(handle);
        if (!MockFramework.isEnabled()) {
            return handle.supportsNormalTermination();
        }
        return true;
    }

    private static final class DeterministicProcessHandle implements ProcessHandle {

        @Override
        public long pid() {
            return 4242L;
        }

        @Override
        public Optional<ProcessHandle> parent() {
            return Optional.empty();
        }

        @Override
        public Stream<ProcessHandle> children() {
            return Stream.empty();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        @Override
        public Info info() {
            return new DeterministicInfo();
        }

        @Override
        public CompletableFuture<ProcessHandle> onExit() {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public boolean supportsNormalTermination() {
            return true;
        }

        @Override
        public boolean destroy() {
            return false;
        }

        @Override
        public boolean destroyForcibly() {
            return false;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public int compareTo(ProcessHandle other) {
            return Long.compare(pid(), other.pid());
        }
    }

    private static final class DeterministicInfo implements ProcessHandle.Info {

        @Override
        public Optional<String> command() {
            return Optional.empty();
        }

        @Override
        public Optional<String> commandLine() {
            return Optional.empty();
        }

        @Override
        public Optional<String[]> arguments() {
            return Optional.empty();
        }

        @Override
        public Optional<Instant> startInstant() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> totalCpuDuration() {
            return Optional.empty();
        }

        @Override
        public Optional<String> user() {
            return Optional.empty();
        }
    }
}
