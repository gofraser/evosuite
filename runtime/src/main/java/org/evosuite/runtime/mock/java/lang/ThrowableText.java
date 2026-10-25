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

/**
 * Text and stack traces of mocked exceptions, shared by the mock exception classes. This is not
 * a mock class, so the test cluster does not offer its methods to generated tests.
 */
public final class ThrowableText {

    private ThrowableText() {
    }

    /**
     * Describes a mocked exception the way {@link Throwable#toString()} does, but names the
     * class the application sees: the mock classes stand in for a JDK exception, which is
     * their superclass, so the mock's own name is replaced by it.
     *
     * @param exception the mocked exception
     * @return the class name, followed by the message if there is one
     */
    public static String describe(Throwable exception) {
        Class<?> clazz = exception.getClass();
        if (clazz.getSimpleName().startsWith("Mock")
                && clazz.getName().startsWith("org.evosuite.runtime.mock.")) {
            clazz = clazz.getSuperclass();
        }
        String message = exception.getLocalizedMessage();
        return message != null ? clazz.getName() + ": " + message : clazz.getName();
    }

    /**
     * The stack trace a mocked exception reports while mocking is on: the fixed trace of
     * {@link MockThrowable#getDefaultStackTrace()}, whose first element is replaced by the
     * one the exception was created at.
     *
     * @param real the stack trace the JVM recorded for the exception, possibly empty
     * @return the stack trace to report
     */
    public static StackTraceElement[] mockTrace(StackTraceElement[] real) {
        StackTraceElement[] trace = MockThrowable.getDefaultStackTrace();
        if (real.length > 0) {
            trace[0] = real[0];
        }
        return trace;
    }

    /**
     * Validates and copies a stack trace set on a mocked exception, as
     * {@link Throwable#setStackTrace(StackTraceElement[])} does.
     *
     * @param stackTrace the stack trace to set
     * @return a defensive copy
     * @throws MockNullPointerException if an element is {@code null}
     */
    public static StackTraceElement[] checkedCopy(StackTraceElement[] stackTrace) {
        StackTraceElement[] copy = stackTrace.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == null) {
                throw new MockNullPointerException("stackTrace[" + i + "]");
            }
        }
        return copy;
    }

    /**
     * Renders a stack trace the way mocked exceptions print it: one element per line.
     *
     * @param trace the stack trace
     * @return the text to print
     */
    public static String render(StackTraceElement[] trace) {
        StringBuilder text = new StringBuilder();
        for (StackTraceElement element : trace) {
            text.append(element).append('\n');
        }
        return text.toString();
    }

    /**
     * The mock counterpart of the exception {@link Throwable#initCause(Throwable)} rejects a
     * cause with.
     *
     * @param failure the {@link IllegalStateException} or {@link IllegalArgumentException} thrown
     * @return the mock exception to throw instead
     */
    public static RuntimeException initCauseFailure(RuntimeException failure) {
        if (failure instanceof IllegalStateException) {
            return new MockIllegalStateException(failure.getMessage());
        }
        return new MockIllegalArgumentException(failure.getMessage());
    }
}
