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
package org.evosuite.llm.search;

import org.evosuite.runtime.sandbox.Sandbox;
import org.evosuite.runtime.thread.EvoSuiteOwnedThreads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers background LLM worker threads with the EvoSuite sandbox so their
 * test-execution path (via
 * {@link org.evosuite.testcase.execution.TestCaseExecutor}) runs with the
 * same privileges as the main search thread.
 *
 * <p>Without privileged registration, sandbox teardown (e.g. property
 * restore) can throw a {@link SecurityException} on the worker thread and
 * leave {@code MSecurityManager.executingTestCase} stuck at {@code true} —
 * which then breaks every subsequent {@code execute()} on any thread.
 */
public final class LlmPrivilegedThreads {

    private static final Logger logger = LoggerFactory.getLogger(LlmPrivilegedThreads.class);

    /**
     * Every LLM background thread is named with this prefix, purely as a
     * naming convention for diagnostics (thread dumps, logs). It carries no
     * special meaning to {@code ThreadStopper}: exemption from post-test
     * cleanup is granted by identity, via {@link EvoSuiteOwnedThreads},
     * precisely so that a SUT-created thread cannot opt out of cleanup by
     * picking a name that happens to match.
     */
    public static final String THREAD_NAME_PREFIX = "evosuite-llm-";

    private LlmPrivilegedThreads() {
    }

    /** The name for an LLM background thread with the given role, e.g. {@code operator-1}. */
    public static String threadName(String role) {
        return THREAD_NAME_PREFIX + role;
    }

    /**
     * Creates (but does not start) an EvoSuite-owned background thread for
     * LLM work: named {@code threadName(role)}, marked daemon, registered
     * with {@link EvoSuiteOwnedThreads} so {@code ThreadStopper} never treats
     * it as a leaked SUT thread no matter when it is born relative to a test
     * execution, and best-effort registered with the sandbox as privileged
     * (see {@link #registerAsPrivileged(Thread, String)}).
     *
     * @param r    the thread's body
     * @param role short human-readable label, e.g. {@code "operator-1"}
     * @return the new, not-yet-started thread
     */
    public static Thread newThread(Runnable r, String role) {
        Thread t = new Thread(r, threadName(role));
        t.setDaemon(true);
        EvoSuiteOwnedThreads.register(t);
        registerAsPrivileged(t, role);
        return t;
    }

    /**
     * Best-effort: no-op when no sandbox is active; logs at debug level on
     * any registration failure.
     *
     * @param t    the worker thread to register
     * @param role short human-readable label for the worker (used in debug logs)
     */
    public static void registerAsPrivileged(Thread t, String role) {
        if (!Sandbox.isSecurityManagerInitialized()) {
            return;
        }
        try {
            Sandbox.addPrivilegedThread(t);
        } catch (SecurityException se) {
            logger.debug("Could not register {} thread as privileged from '{}': {}",
                    role, Thread.currentThread().getName(), se.getMessage());
        } catch (Throwable ex) {
            logger.debug("Could not register {} thread as privileged: {}",
                    role, ex.toString());
        }
    }
}
