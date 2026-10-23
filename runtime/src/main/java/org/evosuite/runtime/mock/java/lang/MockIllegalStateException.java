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
 * Custom mock implementation of {@link java.lang.IllegalStateException}.
 */
public class MockIllegalStateException extends IllegalStateException implements OverrideMock {

    /*
     * "Exception" class only defines constructors, like all (?) its subclasses.
     * So, just need to override constructors, and delegate methods.
     *
     *  All subclasses will have same code, albeit with different class names.
     *  Unfortunately, we end up with copy&amp;paste, which cannot be avoided, as
     *  we cannot have multi-inheritance.
     *
     *  WARN: any change would likely end up in having to redo the copy&amp;paste :(
     */

    private static final long serialVersionUID = 8001149552489118355L;

    /**
     * Instead of copy&amp;paste functionalities from MockThrowable, use a delegate.
     */
    private volatile MockThrowable delegate;

    /*
     * This is needed for when super constructors call overridden methods,
     * and proper delegate method (right inputs) is not instantiated yet
     */
    private MockThrowable getDelegate() {
        if (delegate == null) {
            delegate = new MockThrowable(); //placeholder
            if (super.getStackTrace().length > 0) {
                // stack trace may be empty
                delegate.setOriginForDelegate(super.getStackTrace()[0]);
            }
        }
        return delegate;
    }

    // ----- constructor --------

    /**
     * Constructs a new exception with {@code null} as its detail message.
     */
    public MockIllegalStateException() {
        super();
        delegate = new MockThrowable();
        if (super.getStackTrace().length > 0) {
            // stack trace may be empty
            delegate.setOriginForDelegate(super.getStackTrace()[0]);
        }
    }

    /**
     * Constructs a new exception with the specified detail message.
     *
     * @param message the detail message
     */
    public MockIllegalStateException(String message) {
        super(message);
        delegate = new MockThrowable(message);
        if (super.getStackTrace().length > 0) {
            // stack trace may be empty
            delegate.setOriginForDelegate(super.getStackTrace()[0]);
        }
    }

    /**
     * Constructs a new exception with the specified cause.
     *
     * @param cause the cause
     */
    public MockIllegalStateException(Throwable cause) {
        super(cause);
        delegate = new MockThrowable(cause);
        if (super.getStackTrace().length > 0) {
            // stack trace may be empty
            delegate.setOriginForDelegate(super.getStackTrace()[0]);
        }
    }

    /**
     * Constructs a new exception with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause   the cause
     */
    public MockIllegalStateException(String message, Throwable cause) {
        super(message, cause);
        delegate = new MockThrowable(message, cause);
        if (super.getStackTrace().length > 0) {
            // stack trace may be empty
            delegate.setOriginForDelegate(super.getStackTrace()[0]);
        }
    }

    // ----- delegation methods -------

    @Override
    public String getMessage() {
        if (!MockFramework.isEnabled()) {
            return super.getMessage();
        }
        return getDelegate().getMessage();
    }

    @Override
    public String getLocalizedMessage() {
        if (!MockFramework.isEnabled()) {
            return super.getLocalizedMessage();
        }
        return getDelegate().getLocalizedMessage();
    }

    @Override
    public synchronized Throwable getCause() {
        if (!MockFramework.isEnabled()) {
            return super.getCause();
        }
        return getDelegate().getCause();
    }

    @Override
    public String toString() {
        if (!MockFramework.isEnabled()) {
            return super.toString();
        }
        return getDelegate().toString();
    }

    @Override
    public void printStackTrace() {
        if (!MockFramework.isEnabled()) {
            super.printStackTrace();
            return;
        }
        getDelegate().printStackTrace();
    }

    @Override
    public synchronized Throwable initCause(Throwable cause) {
        if (!MockFramework.isEnabled()) {
            return super.initCause(cause);
        }
        getDelegate().initCause(cause);
        // The delegate answers while mocking is on, but the cause the exception itself keeps is
        // what getCause() reports once mocking is off, for instance to the JUnit rule that looks
        // for a wrapped resource limit after the test, so set it there too.
        try {
            super.initCause(cause);
        } catch (IllegalStateException | IllegalArgumentException alreadyInitialised) {
            // The delegate has accepted the cause, so this only says that it is set already.
        }
        return this;
    }

    @Override
    public void printStackTrace(PrintStream p) {
        if (!MockFramework.isEnabled()) {
            super.printStackTrace(p);
            return;
        }
        getDelegate().printStackTrace(p);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void printStackTrace(PrintWriter p) {
        if (!MockFramework.isEnabled()) {
            super.printStackTrace(p);
            return;
        }
        getDelegate().printStackTrace(p);
    }

    /*
    @Override
    public synchronized Throwable fillInStackTrace() {
        if (!MockFramework.isEnabled()) {
            return super.fillInStackTrace();
        }
        return getDelegate().fillInStackTrace();
    }
*/
    @Override
    public StackTraceElement[] getStackTrace() {
        if (!MockFramework.isEnabled()) {
            return super.getStackTrace();
        }
        return getDelegate().getStackTrace();
    }

    @Override
    public void setStackTrace(StackTraceElement[] stackTrace) {
        if (!MockFramework.isEnabled()) {
            super.setStackTrace(stackTrace);
            return;
        }
        getDelegate().setStackTrace(stackTrace);
    }
}
