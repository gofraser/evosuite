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
package org.evosuite.runtime.mock.java.util.logging;

import org.evosuite.runtime.mock.OverrideMock;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Mock of {@link java.util.logging.Logger} that disables all logging.
 *
 * <p>This prevents an infinite loop in JDK's {@code Logger.getEffectiveLoggerBundle()}
 * which walks the logger parent chain ({@code l = l.parent}) — if a cycle exists
 * in the parent hierarchy (possible under EvoSuite's classloading), the loop
 * never terminates and the thread becomes permanently stuck in JDK code that
 * cannot be interrupted or stopped.</p>
 *
 * <p>By overriding {@link #isLoggable(Level)} to always return {@code false},
 * all logging methods short-circuit before reaching {@code doLog()} and
 * the problematic {@code getEffectiveLoggerBundle()} is never called.</p>
 */
public class MockLogger extends Logger implements OverrideMock {

    protected MockLogger(String name, String resourceBundleName) {
        super(name, resourceBundleName);
    }

    /**
     * Replacement for {@link Logger#getLogger(String)}.
     * Returns a new MockLogger instance rather than delegating to LogManager.
     */
    public static MockLogger getLogger(String name) {
        return new MockLogger(name, null);
    }

    /**
     * Replacement for {@link Logger#getLogger(String, String)}.
     */
    public static MockLogger getLogger(String name, String resourceBundleName) {
        return new MockLogger(name, resourceBundleName);
    }

    /**
     * Replacement for {@link Logger#getAnonymousLogger()}.
     */
    public static MockLogger getAnonymousLogger() {
        return new MockLogger(null, null);
    }

    /**
     * Replacement for {@link Logger#getAnonymousLogger(String)}.
     */
    public static MockLogger getAnonymousLogger(String resourceBundleName) {
        return new MockLogger(null, resourceBundleName);
    }

    /**
     * Always returns {@code false} to prevent all logging operations from
     * reaching {@code doLog()} and {@code getEffectiveLoggerBundle()}.
     */
    @Override
    public boolean isLoggable(Level level) {
        return false;
    }
}
