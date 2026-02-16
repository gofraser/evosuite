/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.lang;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.StaticReplacementMock;
import org.evosuite.runtime.mock.java.io.MockIOException;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic replacement for process creation through {@link ProcessBuilder}.
 */
public class MockProcessBuilder implements StaticReplacementMock {

    @Override
    public String getMockedClassName() {
        return ProcessBuilder.class.getName();
    }

    public static Process start(ProcessBuilder builder) throws IOException {
        Objects.requireNonNull(builder);
        if (!MockFramework.isEnabled()) {
            return builder.start();
        }
        throw new MockIOException("Cannot start processes in a unit test");
    }

    public static List<Process> startPipeline(List<ProcessBuilder> builders) throws IOException {
        if (!MockFramework.isEnabled()) {
            return ProcessBuilder.startPipeline(builders);
        }
        if (builders == null) {
            throw new NullPointerException("builders");
        }
        if (builders.isEmpty()) {
            throw new IllegalArgumentException("builders must not be empty");
        }
        for (ProcessBuilder ignored : builders) {
            Objects.requireNonNull(ignored, "builders must not contain null elements");
        }

        int size = builders.size();
        List<Process> processes = new ArrayList<>(size);

        // Build from last stage to first stage so stage N stdin can forward into stage N+1 stdin.
        OutputStream nextStageInput = null;
        long pidBase = 5000L;
        for (int i = size - 1; i >= 0; i--) {
            PipedInputStream stageStdout = new PipedInputStream();
            PipedOutputStream stageStdoutWriter = new PipedOutputStream(stageStdout);

            OutputStream stageStdin = new ForwardingOutputStream(stageStdoutWriter, nextStageInput);
            Process stage = MockProcess.deterministicProcess(pidBase + i, stageStdin, stageStdout);
            processes.add(0, stage);
            nextStageInput = stageStdin;
        }
        return processes;
    }

    private static final class ForwardingOutputStream extends OutputStream {
        private final OutputStream current;
        private final OutputStream next;

        private ForwardingOutputStream(OutputStream current, OutputStream next) {
            this.current = current;
            this.next = next;
        }

        @Override
        public void write(int b) throws IOException {
            current.write(b);
            if (next != null) {
                next.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            current.write(b, off, len);
            if (next != null) {
                next.write(b, off, len);
            }
        }

        @Override
        public void flush() throws IOException {
            current.flush();
            if (next != null) {
                next.flush();
            }
        }

        @Override
        public void close() throws IOException {
            IOException first = null;
            try {
                current.close();
            } catch (IOException e) {
                first = e;
            }
            if (next != null) {
                try {
                    next.close();
                } catch (IOException e) {
                    if (first == null) {
                        first = e;
                    }
                }
            }
            if (first != null) {
                throw first;
            }
        }
    }
}
