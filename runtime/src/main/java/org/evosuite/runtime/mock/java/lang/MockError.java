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

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.OverrideMock;
import java.io.PrintStream;
import java.io.PrintWriter;

/**
 * Custom mock implementation of {@link java.lang.Error}.
 */
public class MockError extends Error implements OverrideMock {

    /*
     * Only the constructors differ between the mock exceptions; the stack trace handling is
     * the same in each of them, with the text and trace helpers in ThrowableText, because
     * they cannot share a superclass.
     */

    private static final long serialVersionUID = 8001149552489118355L;

    private volatile StackTraceElement[] mockTrace;

    // ----- constructors --------

    /**
     * Constructs a new error with {@code null} as its detail message.
     */
    public MockError() {
        super();
    }

    /**
     * Constructs a new error with the specified detail message.
     *
     * @param message the detail message
     */
    public MockError(String message) {
        super(message);
    }

    /**
     * Constructs a new error with the specified cause.
     *
     * @param cause the cause
     */
    public MockError(Throwable cause) {
        super(cause);
    }

    /**
     * Constructs a new error with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause   the cause
     */
    public MockError(String message, Throwable cause) {
        super(message, cause);
    }

    protected MockError(String message, Throwable cause,
            boolean enableSuppression,
            boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

    // ----- mocked methods -------

    @Override
    public String toString() {
        if (!MockFramework.isEnabled()) {
            return super.toString();
        }
        return ThrowableText.describe(this);
    }

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
}
