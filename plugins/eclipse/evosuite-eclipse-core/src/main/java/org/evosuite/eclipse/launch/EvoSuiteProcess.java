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
package org.evosuite.eclipse.launch;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.CharBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Runs one EvoSuite command to completion or cancellation, feeding its output through a
 * {@link ProgressParser}. The listener is called from a separate thread.
 */
public final class EvoSuiteProcess {

    /** Returned by {@link #run} when the run was cancelled. */
    public static final int CANCELLED = Integer.MIN_VALUE;

    private static final long POLL_MILLIS = 200;

    private EvoSuiteProcess() {
    }

    public static int run(List<String> command, File workingDirectory, ProgressParser.Listener listener,
                          BooleanSupplier cancelled) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory)
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().close();
        Thread pump = new Thread(() -> pump(process, listener), "EvoSuite output");
        pump.setDaemon(true);
        pump.start();
        // Remembered while the master runs: once it has exited, its children are no longer
        // listed as its descendants, and a client JVM it failed to stop would run on unnoticed.
        Set<ProcessHandle> children = new HashSet<>();
        try {
            while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(children::add);
                if (cancelled.getAsBoolean()) {
                    destroy(process, children);
                    pump.join(TimeUnit.SECONDS.toMillis(2));
                    return CANCELLED;
                }
            }
        } catch (InterruptedException e) {
            destroy(process, children);
            throw e;
        }
        destroy(process, children);
        pump.join(TimeUnit.SECONDS.toMillis(5));
        return process.exitValue();
    }

    private static void pump(Process process, ProgressParser.Listener listener) {
        ProgressParser parser = new ProgressParser(listener);
        char[] buffer = new char[8192];
        try (Reader in = new InputStreamReader(process.getInputStream(), EvoSuiteCommand.nativeCharset())) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                parser.accept(CharBuffer.wrap(buffer, 0, n));
            }
        } catch (IOException e) {
            // The process was destroyed while we were reading; nothing left to show.
        }
        parser.close();
    }

    /**
     * Ends the process and everything it started. The EvoSuite master runs the search in a client
     * JVM of its own, which would otherwise outlive a cancelled master.
     */
    static void destroy(Process process, Set<ProcessHandle> knownChildren) throws InterruptedException {
        process.descendants().forEach(knownChildren::add);
        List<ProcessHandle> alive = knownChildren.stream().filter(ProcessHandle::isAlive).collect(Collectors.toList());
        alive.forEach(ProcessHandle::destroy);
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
        for (ProcessHandle child : alive) {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }
}
