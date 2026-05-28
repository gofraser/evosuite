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
final class LlmPrivilegedThreads {

    private static final Logger logger = LoggerFactory.getLogger(LlmPrivilegedThreads.class);

    private LlmPrivilegedThreads() {
    }

    /**
     * Best-effort: no-op when no sandbox is active; logs at debug level on
     * any registration failure.
     *
     * @param t    the worker thread to register
     * @param role short human-readable label for the worker (used in debug logs)
     */
    static void registerAsPrivileged(Thread t, String role) {
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
