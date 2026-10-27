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
package org.evosuite.llm.response;

import org.evosuite.runtime.mock.MockFramework;

/**
 * Exceptions of EvoSuite's mock runtime as the repair loop sees them: whether the
 * runtime rather than the SUT raised one, and the JDK exception a mock class stands for.
 */
final class MockRuntimeExceptions {

    /**
     * Package prefix of EvoSuite's mock runtime. Derived from a class literal
     * so that it is {@code shaded.org.evosuite.runtime.mock.} in the shaded
     * jar and {@code org.evosuite.runtime.mock.} in an unshaded build.
     */
    static final String MOCK_RUNTIME_PACKAGE_PREFIX = packagePrefixOf(MockFramework.class);

    private MockRuntimeExceptions() {
    }

    /**
     * Returns true when the throwable was raised by EvoSuite's runtime rather
     * than by the SUT: either the {@code System.exit} replacement, or an
     * exception whose throwing frame (ignoring JDK frames) is in the mock
     * runtime package. The exception class alone says nothing about its
     * origin, because instrumentation rewrites SUT code such as
     * {@code new IllegalArgumentException(..)} to the corresponding mock
     * class and re-parents SUT exception classes onto mock superclasses.
     */
    static boolean isRaisedByEvoSuiteRuntime(Throwable thrown) {
        if (thrown == null) {
            return false;
        }
        if (thrown instanceof org.evosuite.runtime.System.SystemExitException) {
            return true;
        }
        StackTraceElement[] trace = thrown.getStackTrace();
        if (trace == null) {
            return false;
        }
        for (StackTraceElement frame : trace) {
            String className = frame == null ? null : frame.getClassName();
            if (className == null || DependencyFailureAnalysis.isJdkClassName(className)) {
                continue;
            }
            return className.startsWith(MOCK_RUNTIME_PACKAGE_PREFIX);
        }
        return false;
    }

    /**
     * Maps a mock exception class (e.g. {@code MockIllegalArgumentException})
     * to the JDK class it replaces, so feedback names the exception the SUT
     * source actually throws. Other classes are returned unchanged.
     */
    static Class<?> unmockedExceptionClass(Class<?> exceptionClass) {
        Class<?> current = exceptionClass;
        while (current != null && current.getName().startsWith(MOCK_RUNTIME_PACKAGE_PREFIX)) {
            current = current.getSuperclass();
        }
        return current == null ? exceptionClass : current;
    }

    /**
     * Exception class name as reported to the LLM: mock replacements are
     * shown as the JDK exception the SUT source throws.
     */
    static String reportedExceptionName(Throwable throwable) {
        return unmockedExceptionClass(throwable.getClass()).getName();
    }

    /**
     * Returns true when the diagnostic error string contains a reference to
     * an exception under the mock runtime package, suggesting the
     * underlying failure is environment-induced rather than test-fixable.
     */
    static boolean errorMentionsMockRuntimeException(String error) {
        return error != null && error.contains(MOCK_RUNTIME_PACKAGE_PREFIX);
    }

    private static String packagePrefixOf(Class<?> type) {
        String name = type.getName();
        return name.substring(0, name.lastIndexOf('.') + 1);
    }
}
