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
package org.evosuite.runtime;

/**
 * This exception is thrown by the EvoSuite framework when a test case uses too many resources.
 * These resources are for example number of threads and number of iterations in loops.
 * This is done to avoid very expensive test cases, although technically it does not represent
 * a bug in the class under test.
 *
 * <p>Created by Andrea Arcuri on 29/03/15.
 */
public class TooManyResourcesException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean repeated;

    public TooManyResourcesException() {
        super();
        this.repeated = false;
    }

    public TooManyResourcesException(String msg) {
        super(msg);
        this.repeated = false;
    }

    /**
     * Creates an exception that records whether a loop already threw one on this thread.
     *
     * @param msg      the message
     * @param repeated whether the same loop already threw such an exception on this thread in
     *                 the same test and the code under test went on running, so it must not be
     *                 swallowed again
     */
    public TooManyResourcesException(String msg, boolean repeated) {
        super(msg);
        this.repeated = repeated;
    }

    /**
     * Whether the code under test has already swallowed such an exception of the same loop in
     * this test and went on to exhaust it again. Catch handlers that EvoSuite instrumented let
     * this one through, see {@link Runtime#rethrowIfTimeout(Throwable)}.
     */
    public boolean isRepeated() {
        return repeated;
    }

    /**
     * Whether the throwable is a {@code TooManyResourcesException}, has one in its cause chain,
     * or reports one in a message.
     *
     * <p>Mockito wraps an exception thrown by an invocation listener (see
     * {@link MockInvocationLimit}) into a MockitoException, so the limit reaches the test wrapped.
     * Code under test can also wrap it without keeping the cause, by building its own exception
     * from the text of the one it caught (a persistence layer that rethrows
     * {@code new PersistenceException(e.toString())}); the exception that reaches the test then
     * only names the limit in its message.
     *
     * <p>{@code getMessage()} and {@code getCause()} of an exception of the code under test can
     * run code under test, and that code can throw (a message built by walking a cyclic reference
     * chain exhausts the loop limit). Such a failure counts as no match: it is not the exception
     * the test saw.
     */
    public static boolean isOrWraps(Throwable t) {
        String name = TooManyResourcesException.class.getName();
        int depth = 0;
        while (t != null && depth++ < 20) {
            if (t instanceof TooManyResourcesException) {
                return true;
            }
            String message;
            try {
                message = t.getMessage();
            } catch (Throwable failed) {
                message = null;
            }
            if (message != null && message.contains(name)) {
                return true;
            }
            try {
                t = t.getCause();
            } catch (Throwable failed) {
                return false;
            }
        }
        return false;
    }

    /**
     * Whether the throwable is a {@link StackOverflowError} or has one in its cause chain.
     * The chain walk is bounded like {@link #isOrWraps(Throwable)}, and a {@code getCause()}
     * that throws counts as no match.
     */
    public static boolean isOrWrapsStackOverflow(Throwable t) {
        int depth = 0;
        while (t != null && depth++ < 20) {
            if (t instanceof StackOverflowError) {
                return true;
            }
            try {
                t = t.getCause();
            } catch (Throwable failed) {
                return false;
            }
        }
        return false;
    }

    /**
     * Whether a test failure is the expected end of a test that hit a resource limit: the
     * limit itself, or, if the test is marked
     * {@link org.evosuite.runtime.annotation.EvoSuiteEndsOnResourceLimit}, the stack overflow
     * that the same unbounded recursion ends on when the stack runs out before the limit fires.
     *
     * @param t           the throwable that reached the JUnit runner
     * @param endsOnLimit whether the test method carries the annotation
     */
    public static boolean isExpectedLimitFailure(Throwable t, boolean endsOnLimit) {
        return isOrWraps(t) || (endsOnLimit && isOrWrapsStackOverflow(t));
    }
}
