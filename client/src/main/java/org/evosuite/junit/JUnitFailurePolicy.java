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
package org.evosuite.junit;

import org.evosuite.runtime.TooManyResourcesException;
import org.evosuite.runtime.annotation.EvoSuiteEndsOnResourceLimit;
import org.evosuite.testcase.TestCase;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What a failure of a JUnit check says about the test that failed: whether it is the expected end
 * of a test, a consequence of the check itself, a deterministic problem of the environment, or
 * evidence that the test is unstable.
 */
final class JUnitFailurePolicy {

    private JUnitFailurePolicy() {
    }

    /**
     * Whether the failure is a test that hung, as the watchdog of the check reports it.
     */
    static boolean isHungTest(JUnitFailure failure) {
        return JUnitCheckProgress.HUNG_TEST_EXCEPTION.equals(failure.getExceptionClassName());
    }

    /**
     * Whether the failure is the one the search recorded for the test. A test that hung is not
     * one whose failure was expected: a test recorded as failing during the search may well be
     * the one that was killed there.
     *
     * @param test    the test the failure was attributed to
     * @param failure the failure to judge
     */
    static boolean isExpectedFailure(TestCase test, JUnitFailure failure) {
        return test.isFailing() && !isHungTest(failure);
    }

    /**
     * Whether the failure is the JUnit runner failing to attach the Java agent, which says
     * nothing about the tests.
     */
    static boolean isAgentAttachFailure(String testName, JUnitFailure failure) {
        return "initializationError".equals(testName) && failure.getMessage() != null
                && failure.getMessage().contains("Failed to attach Java Agent");
    }

    /**
     * Whether the failure is the SUT hitting the per-loop iteration cap of the runtime, or an
     * equivalent resource limit. During search the cap is active too, so any test that ran to
     * completion during generation also terminated this way: the rerun reproduces the same
     * termination rather than exposing a new problem.
     *
     * @param failure     the failure to judge
     * @param testClasses the test classes that were run
     */
    static boolean isResourceLimitFailure(JUnitFailure failure, Class<?>[] testClasses) {
        return "org.evosuite.runtime.TooManyResourcesException".equals(failure.getExceptionClassName())
                || isMockInvocationLimitFailure(failure)
                || isWrappedTooManyResourcesFailure(failure)
                || isStackOverflowOfTestEndingOnLimit(failure, testClasses);
    }

    /**
     * Whether the failure is a watchdog timeout. In a JUnit re-check these race with the loop cap
     * and are equivalent to it, so a test is not dropped for the phase-specific scheduling of the
     * watchdog.
     */
    static boolean isTimeoutException(JUnitFailure failure) {
        return "java.util.concurrent.TimeoutException".equals(failure.getExceptionClassName());
    }

    /**
     * A mock that exceeded the invocation limit of the runtime. Mockito wraps what an invocation
     * listener throws, so the TooManyResourcesException reaches the test inside a MockitoException
     * whose message names it.
     */
    static boolean isMockInvocationLimitFailure(JUnitFailure failure) {
        if (failure == null) {
            return false;
        }
        String cls = normalizeFailureClassName(failure.getExceptionClassName());
        String message = failure.getMessage();
        // the class name carries the shading prefix when Mockito is the relocated copy
        return cls.endsWith("org.mockito.exceptions.base.MockitoException")
                && message != null && message.contains("MockInvocationLimit")
                && message.contains("TooManyResourcesException");
    }

    /**
     * Whether a timeout in a run the watchdog stopped says nothing about the test. The watchdog
     * raises the kill switch when the run exceeds its budget, and from then on every test that
     * reaches instrumented code fails at once; only the first such failure, the test that hung,
     * is evidence about a test.
     *
     * @param result          the result the failure belongs to
     * @param failure         the failure to judge
     * @param culpritSeen     whether the test that hung has been seen already; set by the first
     *                        timeout of a stopped run
     * @return true if the failure is a casualty of the stopped run and its test should be kept
     */
    static boolean isWatchdogCasualty(JUnitResult result, JUnitFailure failure,
                                      AtomicBoolean culpritSeen) {
        if (!result.wasWatchdogFired() || !isTimeoutExceeded(failure)) {
            return false;
        }
        return culpritSeen.getAndSet(true);
    }

    /**
     * A test stopped by the test executor's kill switch, whose time budget had run out.
     */
    static boolean isTimeoutExceeded(JUnitFailure failure) {
        return failure != null
                && normalizeFailureClassName(failure.getExceptionClassName())
                .endsWith("org.evosuite.testcase.execution.TestCaseExecutor$TimeoutExceeded");
    }

    /**
     * The JUnit check reads the failures itself instead of running the tests through the
     * non-functional requirement rule, so it applies the rule's allowance here: a stack overflow
     * is the expected end of a test that carries
     * {@link EvoSuiteEndsOnResourceLimit}, because unbounded recursion ends on the stack when
     * that runs out before a resource limit fires (see
     * {@link TooManyResourcesException#isExpectedLimitFailure(Throwable, boolean)}).
     */
    static boolean isStackOverflowOfTestEndingOnLimit(JUnitFailure failure, Class<?>[] testClasses) {
        if (failure == null || testClasses == null || failure.getDescriptionMethodName() == null) {
            return false;
        }
        String overflow = StackOverflowError.class.getName();
        String trace = failure.getTrace();
        boolean isOverflow = overflow.equals(failure.getExceptionClassName())
                || (trace != null && trace.contains("Caused by: " + overflow));
        if (!isOverflow) {
            return false;
        }
        for (Class<?> testClass : testClasses) {
            if (failure.getDescriptionClassName() != null
                    && !failure.getDescriptionClassName().equals(testClass.getName())) {
                continue;
            }
            for (java.lang.reflect.Method method : testClass.getDeclaredMethods()) {
                if (!method.getName().equals(failure.getDescriptionMethodName())) {
                    continue;
                }
                // by name: the tests may have been loaded by a loader with its own copy of the runtime
                for (java.lang.annotation.Annotation annotation : method.getAnnotations()) {
                    if (annotation.annotationType().getName().equals(EvoSuiteEndsOnResourceLimit.class.getName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * A failure that is a wrapper around the runtime's resource limit. The code under test can
     * catch the TooManyResourcesException and throw an exception of its own, as a persistence
     * layer does with {@code new PersistenceException(e.toString())}. Whether the wrapper is
     * thrown depends on which handler the limit happens to meet, so its type and message are as
     * little reproducible as the limit itself: it is the same outcome as the limit.
     */
    static boolean isWrappedTooManyResourcesFailure(JUnitFailure failure) {
        if (failure == null) {
            return false;
        }
        String limit = TooManyResourcesException.class.getName();
        String message = failure.getMessage();
        String trace = failure.getTrace();
        return (message != null && message.contains(limit)) || (trace != null && trace.contains(limit));
    }

    static boolean isClassInitializerFailure(JUnitFailure failure) {
        if (failure == null) {
            return false;
        }
        String cls = normalizeFailureClassName(failure.getExceptionClassName());
        if (!"java.lang.ExceptionInInitializerError".equals(cls)
                && !"java.lang.NoClassDefFoundError".equals(cls)) {
            return false;
        }
        List<String> trace = failure.getExceptionStackTrace();
        if (trace == null || trace.isEmpty()) {
            return true;
        }
        for (String frame : trace) {
            if (frame != null && frame.contains("<clinit>")) {
                return true;
            }
        }
        return "java.lang.ExceptionInInitializerError".equals(cls);
    }

    static boolean isDeterministicGuiEnvironmentFailure(JUnitFailure failure) {
        if (failure == null) {
            return false;
        }
        String failureClass = normalizeFailureClassName(failure.getExceptionClassName());
        String message = failure.getMessage();
        List<String> trace = failure.getExceptionStackTrace();

        if ("java.awt.AWTError".equals(failureClass)
                && isGraphicsEnvironmentNullFailure(message, trace)) {
            return true;
        }

        if (!"java.awt.HeadlessException".equals(failureClass)) {
            return false;
        }

        if (message != null && message.toLowerCase(Locale.ROOT).contains("headless")) {
            return true;
        }
        if (trace == null || trace.isEmpty()) {
            return true;
        }
        return containsAny(trace,
                "java.awt.GraphicsEnvironment.checkHeadless",
                "java.awt.Window.<init>",
                "javax.swing.JFrame.<init>",
                "javax.swing.JDialog.<init>",
                "javax.swing.JWindow.<init>");
    }

    static boolean shouldRetryWithInstrumentingAfterFailure(JUnitResult result) {
        for (JUnitFailure failure : result.getFailures()) {
            String failureClass = normalizeFailureClassName(failure.getExceptionClassName());
            String message = failure.getMessage();
            List<String> trace = failure.getExceptionStackTrace();
            if ("java.awt.HeadlessException".equals(failureClass)) {
                return true;
            }
            if ("java.awt.AWTError".equals(failureClass)
                    && isGraphicsEnvironmentNullFailure(message, trace)) {
                return true;
            }
            if ("java.lang.ClassCastException".equals(failureClass)) {
                if (trace != null
                        && (containsAny(trace, "org.evosuite.instrumentation.InstrumentingClassLoader")
                        || containsAny(trace, "org.evosuite.runtime.GuiSupport$StubGraphicsConfiguration"))) {
                    return true;
                }
                if (message != null
                        && (message.contains("InstrumentingClassLoader")
                        || message.contains("StubGraphicsConfiguration"))) {
                    return true;
                }
            }
        }
        return false;
    }

    static String normalizeFailureClassName(String rawClassName) {
        if (rawClassName == null) {
            return "";
        }
        String trimmed = rawClassName.trim();
        if (trimmed.startsWith("class ")) {
            return trimmed.substring("class ".length()).trim();
        }
        return trimmed;
    }

    static boolean isGraphicsEnvironmentNullFailure(String message, List<String> trace) {
        if (message != null
                && message.toLowerCase(Locale.ROOT).contains("local graphicsenvironment must not be null")) {
            return true;
        }
        return trace != null && containsAny(trace, "GraphicsEnvironment.getLocalGraphicsEnvironment");
    }

    static boolean containsAny(List<String> trace, String... needles) {
        for (String frame : trace) {
            for (String needle : needles) {
                if (frame != null && frame.contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }
}
