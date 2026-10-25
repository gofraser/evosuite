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
package org.evosuite.runtime.mock.java.lang;

import org.evosuite.runtime.mock.EvoSuiteMock;
import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.OverrideMock;
import java.io.PrintStream;
import java.io.PrintWriter;

/**
 * Custom mock implementation of {@link java.lang.Throwable}.
 */
public class MockThrowable extends Throwable implements OverrideMock {

    private static final long serialVersionUID = 4078375023919805371L;

    private volatile StackTraceElement[] mockTrace;

    // ------ constructors -------------

    /**
     * Constructs a new throwable with {@code null} as its detail message.
     */
    public MockThrowable() {
        super();
    }

    /**
     * Constructs a new throwable with the specified detail message.
     *
     * @param message the detail message
     */
    public MockThrowable(String message) {
        super(message);
    }

    /**
     * Constructs a new throwable with the specified cause.
     *
     * @param cause the cause
     */
    public MockThrowable(Throwable cause) {
        super(cause);
    }

    /**
     * Constructs a new throwable with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause   the cause
     */
    public MockThrowable(String message, Throwable cause) {
        super(message, cause);
    }

    protected MockThrowable(String message, Throwable cause,
            boolean enableSuppression,
            boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

    /*
     *  ------ special replacements static methods ------------
     *
     *  WARN: don't modify the name of these methods, as they are used by reflection in instrumentator
     */

    /**
     * Returns a default stack trace for EvoSuite mocks.
     *
     * @return an array of stack trace elements
     */
    public static StackTraceElement[] getDefaultStackTrace() {
        StackTraceElement[] v = new StackTraceElement[3];
        v[0] = new StackTraceElement("<evosuite>", "<evosuite>", "<evosuite>", -1);
        v[1] = new StackTraceElement("<evosuite>", "<evosuite>", "<evosuite>", -1);
        v[2] = new StackTraceElement("<evosuite>", "<evosuite>", "<evosuite>", -1);
        return v;
    }

    /**
     * Replacement for {@link Throwable#getStackTrace()}.
     *
     * @param source the throwable
     * @return the stack trace
     */
    public static StackTraceElement[] replacement_getStackTrace(Throwable source) {
        if (!MockFramework.isEnabled() || source instanceof EvoSuiteMock) {
            return source.getStackTrace();
        }

        return getDefaultStackTrace();
    }

    /**
     * Replacement for {@link Throwable#printStackTrace(PrintWriter)}.
     *
     * @param source the throwable
     * @param p      the writer
     */
    public static void replacement_printStackTrace(Throwable source, PrintWriter p) {
        if (!MockFramework.isEnabled() || source instanceof EvoSuiteMock) {
            source.printStackTrace(p);
            return;
        }
        p.append(ThrowableText.render(getDefaultStackTrace()));
    }

    /**
     * Replacement for {@link Throwable#printStackTrace(PrintStream)}.
     *
     * @param source the throwable
     * @param p      the stream
     */
    public static void replacement_printStackTrace(Throwable source, PrintStream p) {
        if (!MockFramework.isEnabled() || source instanceof EvoSuiteMock) {
            source.printStackTrace(p);
            return;
        }
        p.append(ThrowableText.render(getDefaultStackTrace()));
    }

    // ------  mocked public methods --------

    @Override
    public synchronized Throwable initCause(Throwable cause) {
        if (!MockFramework.isEnabled()) {
            return super.initCause(cause);
        }
        try {
            return super.initCause(cause);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw ThrowableText.initCauseFailure(e);
        }
    }

    @Override
    public void printStackTrace(PrintStream p) {
        if (!MockFramework.isEnabled()) {
            super.printStackTrace(p);
            return;
        }
        p.append(ThrowableText.render(getStackTrace()));
    }

    @Override
    public void printStackTrace(PrintWriter p) {
        if (!MockFramework.isEnabled()) {
            super.printStackTrace(p);
            return;
        }
        p.append(ThrowableText.render(getStackTrace()));
    }

    @Override
    public StackTraceElement[] getStackTrace() {
        if (!MockFramework.isEnabled()) {
            return super.getStackTrace();
        }
        // Taken on first use, as the trace the JVM recorded is only complete once the
        // constructors have run.
        StackTraceElement[] trace = mockTrace;
        if (trace == null) {
            trace = ThrowableText.mockTrace(super.getStackTrace());
            mockTrace = trace;
        }
        return trace;
    }

    @Override
    public void setStackTrace(StackTraceElement[] stackTrace) {
        if (!MockFramework.isEnabled()) {
            super.setStackTrace(stackTrace);
            return;
        }
        mockTrace = ThrowableText.checkedCopy(stackTrace);
    }

    /*
     *  getSuppressed() and addSuppressed() are final, and likely not
     *  needed to be mocked anyway
     *
     */
}
