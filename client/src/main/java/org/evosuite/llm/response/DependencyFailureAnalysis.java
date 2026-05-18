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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies an execution failure that arose outside the class under test (CUT).
 * Distinguishes three cases that need different repair guidance:
 * <ul>
 *   <li>{@link Kind#DEP_MEMBER_FAILURE} — a non-CUT instance method or constructor threw.
 *       Inlining that member's source/decompiled code is actionable: every fresh call
 *       re-runs the body, so the LLM can satisfy the missing precondition.</li>
 *   <li>{@link Kind#CLASS_INIT_FAILURE} — a non-CUT class's static initializer threw
 *       ({@code ExceptionInInitializerError}). The class is now permanently in the
 *       erroneous state for this ClassLoader; only "avoid all paths that touch this
 *       class" is a valid repair.</li>
 *   <li>{@link Kind#CLASS_INIT_AFTERSHOCK} — a follow-on {@code NoClassDefFoundError:
 *       Could not initialize class X}. Same treatment as {@link Kind#CLASS_INIT_FAILURE},
 *       but the class name comes from the error message instead of an initializer frame.</li>
 * </ul>
 * Anything that doesn't classify resolves to {@link Kind#NONE}.
 */
public final class DependencyFailureAnalysis {

    /** Pattern matching {@code NoClassDefFoundError: Could not initialize class &lt;FQCN&gt;}. */
    private static final Pattern COULD_NOT_INITIALIZE_PATTERN =
            Pattern.compile("Could not initialize class\\s+([A-Za-z_][A-Za-z0-9_$.]+)");

    public enum Kind {
        /** A non-CUT instance method/constructor body threw. Recoverable; inline dep code. */
        DEP_MEMBER_FAILURE,
        /** A non-CUT class's {@code <clinit>} threw; class permanently poisoned. */
        CLASS_INIT_FAILURE,
        /** A {@code NoClassDefFoundError} reporting an already-poisoned class. */
        CLASS_INIT_AFTERSHOCK,
        /** Throwable did not originate in classifiable non-CUT code. */
        NONE
    }

    /** Stack-frame coordinates of the offending non-CUT member. */
    public static final class Frame {
        private final String className;
        private final String methodName;
        private final int lineNumber;

        public Frame(String className, String methodName, int lineNumber) {
            this.className = className;
            this.methodName = methodName;
            this.lineNumber = lineNumber;
        }

        public String getClassName() {
            return className;
        }

        public String getMethodName() {
            return methodName;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public boolean isStaticInitializer() {
            return "<clinit>".equals(methodName);
        }

        public boolean isConstructor() {
            return "<init>".equals(methodName);
        }
    }

    private final Kind kind;
    private final Frame frame;
    private final String poisonedClass;

    private DependencyFailureAnalysis(Kind kind, Frame frame, String poisonedClass) {
        this.kind = kind;
        this.frame = frame;
        this.poisonedClass = poisonedClass;
    }

    public Kind getKind() {
        return kind;
    }

    /** Frame that points at the failing dep member, or {@code null} for {@link Kind#NONE}. */
    public Frame getFrame() {
        return frame;
    }

    /** FQCN of the class that failed to initialize, for the two {@code CLASS_INIT_*} kinds. */
    public String getPoisonedClass() {
        return poisonedClass;
    }

    public boolean isClassInitFailure() {
        return kind == Kind.CLASS_INIT_FAILURE || kind == Kind.CLASS_INIT_AFTERSHOCK;
    }

    public static DependencyFailureAnalysis none() {
        return new DependencyFailureAnalysis(Kind.NONE, null, null);
    }

    /**
     * Inspect a {@link Throwable} and classify its origin. The throwable is unwrapped
     * through diagnostic wrappers (InvocationTargetException, ExecutionException, etc.)
     * before stack-frame inspection so the originating non-CUT frame is found.
     */
    public static DependencyFailureAnalysis analyze(Throwable throwable,
                                                    String targetClass,
                                                    String targetPackage) {
        if (throwable == null) {
            return none();
        }

        // ExceptionInInitializerError directly identifies a poisoned class via its own
        // first stack frame (which is the failing <clinit>). Handle it before generic
        // unwrapping so the marker is not lost.
        Frame initFrame = findClinitFrame(throwable);
        if (initFrame != null && !isCutFrame(initFrame.getClassName(), targetClass, targetPackage)) {
            return new DependencyFailureAnalysis(Kind.CLASS_INIT_FAILURE, initFrame, initFrame.getClassName());
        }

        // NoClassDefFoundError after a previous <clinit> failure: the class name lives
        // in the message ("Could not initialize class X"), not in the stack.
        String poisoned = extractCouldNotInitializeClass(throwable);
        if (poisoned != null && !isCutClassName(poisoned, targetClass, targetPackage)) {
            return new DependencyFailureAnalysis(Kind.CLASS_INIT_AFTERSHOCK, null, poisoned);
        }

        // Fall through: walk the stack of the unwrapped throwable for the first
        // non-CUT, non-EvoSuite, non-framework frame.
        Throwable rootCause = unwrap(throwable);
        Frame depFrame = findDependencyFrame(rootCause, targetClass, targetPackage);
        if (depFrame != null) {
            return new DependencyFailureAnalysis(Kind.DEP_MEMBER_FAILURE, depFrame, null);
        }
        return none();
    }

    private static Frame findClinitFrame(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 6) {
            if (current instanceof ExceptionInInitializerError) {
                StackTraceElement[] trace = current.getStackTrace();
                if (trace != null) {
                    for (StackTraceElement frame : trace) {
                        if (frame == null) {
                            continue;
                        }
                        if ("<clinit>".equals(frame.getMethodName())) {
                            return toFrame(frame);
                        }
                    }
                }
                // Fall back to scanning the cause's own stack for a <clinit>.
                Throwable cause = current.getCause();
                if (cause != null) {
                    StackTraceElement[] causeTrace = cause.getStackTrace();
                    if (causeTrace != null) {
                        for (StackTraceElement frame : causeTrace) {
                            if (frame != null && "<clinit>".equals(frame.getMethodName())) {
                                return toFrame(frame);
                            }
                        }
                    }
                }
            }
            current = current.getCause();
            depth++;
        }
        return null;
    }

    private static String extractCouldNotInitializeClass(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 8) {
            if (current instanceof NoClassDefFoundError) {
                String message = current.getMessage();
                if (message != null) {
                    Matcher matcher = COULD_NOT_INITIALIZE_PATTERN.matcher(message);
                    if (matcher.find()) {
                        return matcher.group(1).replace('/', '.').trim();
                    }
                }
            }
            current = current.getCause();
            depth++;
        }
        return null;
    }

    private static Frame findDependencyFrame(Throwable throwable,
                                             String targetClass,
                                             String targetPackage) {
        if (throwable == null) {
            return null;
        }
        StackTraceElement[] trace = throwable.getStackTrace();
        if (trace == null) {
            return null;
        }
        for (StackTraceElement frame : trace) {
            if (frame == null) {
                continue;
            }
            String className = frame.getClassName();
            if (className == null) {
                continue;
            }
            if (isCutFrame(className, targetClass, targetPackage)) {
                // Once we descend into the CUT, stop — we want the frame the CUT called into,
                // not the CUT itself or anything above it on the stack.
                return null;
            }
            if (isEvoSuiteFrame(className) || isJdkOrFrameworkFrame(className)) {
                continue;
            }
            return toFrame(frame);
        }
        return null;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 12) {
            if (current instanceof java.lang.reflect.InvocationTargetException
                    || current instanceof java.util.concurrent.ExecutionException
                    || current.getClass().getName().endsWith(".CodeUnderTestException")
                    || current.getClass().getName().endsWith("UndeclaredThrowableException")) {
                if (current.getCause() != null) {
                    current = current.getCause();
                    depth++;
                    continue;
                }
            }
            return current;
        }
        return throwable;
    }

    static boolean isCutFrame(String className, String targetClass, String targetPackage) {
        return isCutClassName(className, targetClass, targetPackage);
    }

    static boolean isCutClassName(String className, String targetClass, String targetPackage) {
        if (className == null) {
            return false;
        }
        if (targetClass != null && !targetClass.isEmpty()) {
            if (className.equals(targetClass) || className.startsWith(targetClass + "$")) {
                return true;
            }
        }
        return targetPackage != null
                && !targetPackage.isEmpty()
                && className.startsWith(targetPackage + ".");
    }

    static boolean isEvoSuiteFrame(String className) {
        return className.startsWith("org.evosuite.") || className.startsWith("shaded.org.evosuite.");
    }

    static boolean isJdkOrFrameworkFrame(String className) {
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("com.sun.")
                || className.startsWith("org.junit.")
                || className.startsWith("junit.")
                || className.startsWith("org.mockito.")
                || className.startsWith("net.bytebuddy.")
                || className.startsWith("org.objenesis.");
    }

    private static Frame toFrame(StackTraceElement element) {
        return new Frame(element.getClassName(),
                element.getMethodName() == null ? "" : element.getMethodName(),
                element.getLineNumber());
    }
}
