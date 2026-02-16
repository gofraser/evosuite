/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.lang;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.StaticReplacementMock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.Objects;

/**
 * Deterministic replacement for {@link Process} instance behavior.
 */
public class MockProcess implements StaticReplacementMock {

    private static final Process DETERMINISTIC =
            new DeterministicProcess(4343L, new ByteArrayOutputStream(), new ByteArrayInputStream(new byte[0]));

    @Override
    public String getMockedClassName() {
        return Process.class.getName();
    }

    public static Process deterministicProcess() {
        return DETERMINISTIC;
    }

    static Process deterministicProcess(long pid, OutputStream stdin, InputStream stdout) {
        return new DeterministicProcess(pid, stdin, stdout);
    }

    public static OutputStream getOutputStream(Process process) {
        Objects.requireNonNull(process);
        if (!MockFramework.isEnabled()) {
            return process.getOutputStream();
        }
        if (process instanceof DeterministicProcess) {
            return process.getOutputStream();
        }
        return DETERMINISTIC.getOutputStream();
    }

    public static InputStream getInputStream(Process process) {
        Objects.requireNonNull(process);
        if (!MockFramework.isEnabled()) {
            return process.getInputStream();
        }
        if (process instanceof DeterministicProcess) {
            return process.getInputStream();
        }
        return DETERMINISTIC.getInputStream();
    }

    public static InputStream getErrorStream(Process process) {
        Objects.requireNonNull(process);
        if (!MockFramework.isEnabled()) {
            return process.getErrorStream();
        }
        if (process instanceof DeterministicProcess) {
            return process.getErrorStream();
        }
        return DETERMINISTIC.getErrorStream();
    }

    public static int waitFor(Process process) throws InterruptedException {
        Objects.requireNonNull(process);
        if (!MockFramework.isEnabled()) {
            return process.waitFor();
        }
        if (process instanceof DeterministicProcess) {
            return process.waitFor();
        }
        return DETERMINISTIC.waitFor();
    }

    public static boolean waitFor(Process process, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(process);
        Objects.requireNonNull(unit);
        if (!MockFramework.isEnabled()) {
            return process.waitFor(timeout, unit);
        }
        if (process instanceof DeterministicProcess) {
            return process.waitFor(timeout, unit);
        }
        return DETERMINISTIC.waitFor(timeout, unit);
    }

    public static int exitValue(Process process) {
        Objects.requireNonNull(process);
        if (!MockFramework.isEnabled()) {
            return process.exitValue();
        }
        if (process instanceof DeterministicProcess) {
            return process.exitValue();
        }
        return DETERMINISTIC.exitValue();
    }

    public static void destroy(Process process) {
        Objects.requireNonNull(process);
        if (!MockFramework.isEnabled()) {
            process.destroy();
            return;
        }
        if (process instanceof DeterministicProcess) {
            process.destroy();
            return;
        }
        DETERMINISTIC.destroy();
    }

    public static Process destroyForcibly(Process process) {
        Objects.requireNonNull(process);
        if (!MockFramework.isEnabled()) {
            return process.destroyForcibly();
        }
        if (process instanceof DeterministicProcess) {
            return process.destroyForcibly();
        }
        return DETERMINISTIC;
    }

    public static boolean isAlive(Process process) {
        Objects.requireNonNull(process);
        if (!MockFramework.isEnabled()) {
            return process.isAlive();
        }
        if (process instanceof DeterministicProcess) {
            return process.isAlive();
        }
        return DETERMINISTIC.isAlive();
    }

    public static long pid(Process process) {
        Objects.requireNonNull(process);
        if (!MockFramework.isEnabled()) {
            return process.pid();
        }
        if (process instanceof DeterministicProcess) {
            return process.pid();
        }
        return DETERMINISTIC.pid();
    }

    public static ProcessHandle.Info info(Process process) {
        Objects.requireNonNull(process);
        if (!MockFramework.isEnabled()) {
            return process.info();
        }
        if (process instanceof DeterministicProcess) {
            return process.info();
        }
        return DETERMINISTIC.info();
    }

    public static ProcessHandle toHandle(Process process) {
        Objects.requireNonNull(process);
        if (!MockFramework.isEnabled()) {
            return process.toHandle();
        }
        if (process instanceof DeterministicProcess) {
            return process.toHandle();
        }
        return DETERMINISTIC.toHandle();
    }

    public static CompletableFuture<Process> onExit(Process process) {
        Objects.requireNonNull(process);
        if (!MockFramework.isEnabled()) {
            return process.onExit();
        }
        if (process instanceof DeterministicProcess) {
            return process.onExit();
        }
        return DETERMINISTIC.onExit();
    }

    public static boolean supportsNormalTermination(Process process) {
        Objects.requireNonNull(process);
        if (!MockFramework.isEnabled()) {
            return process.supportsNormalTermination();
        }
        if (process instanceof DeterministicProcess) {
            return process.supportsNormalTermination();
        }
        return DETERMINISTIC.supportsNormalTermination();
    }

    private static final class DeterministicProcess extends Process {

        private final long pid;
        private final OutputStream out;
        private final InputStream in;
        private final InputStream err = new ByteArrayInputStream(new byte[0]);
        private volatile boolean alive;

        private DeterministicProcess(long pid, OutputStream out, InputStream in) {
            this.pid = pid;
            this.out = out;
            this.in = in;
            this.alive = true;
        }

        @Override
        public OutputStream getOutputStream() {
            return out;
        }

        @Override
        public InputStream getInputStream() {
            return in;
        }

        @Override
        public InputStream getErrorStream() {
            return err;
        }

        @Override
        public int waitFor() {
            alive = false;
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            alive = false;
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            alive = false;
        }

        @Override
        public Process destroyForcibly() {
            alive = false;
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public ProcessHandle toHandle() {
            return MockProcessHandle.current();
        }

        @Override
        public ProcessHandle.Info info() {
            return toHandle().info();
        }

        @Override
        public CompletableFuture<Process> onExit() {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public boolean supportsNormalTermination() {
            return true;
        }
    }
}
