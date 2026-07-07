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
package org.evosuite.testcase.synthesizer;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.utils.MethodUtil;

/**
 * The class/method the object-construction synthesis is currently working against. In the
 * default method-level mode this is fixed by {@code -Dtarget_method}/{@code -Dtarget_class}; in
 * whole-class mode ({@code object_rule_whole_class}) it is derived per branch from the branch's own
 * containing method.
 *
 * <p>Rather than threading a context object through every synthesis method signature, the context is
 * published for the current synthesis via {@link #setActive(TargetContext)} and consulted at the few
 * points that previously read {@link Properties#TARGET_CLASS}/{@link Properties#TARGET_METHOD}
 * through {@link #activeClassName()}/{@link #activeMethodSignature()}. When the properties are set
 * (method-level mode) those accessors return them verbatim, so M1–M5 behaviour is unchanged.
 */
public class TargetContext {

    private static final ThreadLocal<TargetContext> ACTIVE = new ThreadLocal<>();

    private static int rejectedConstructorEntries = 0;
    private static int rejectedNonPublicEntries = 0;
    private static int rejectedOtherEntries = 0;

    private final String className;
    private final String methodSignature;
    private final Branch branch;
    private final boolean constructorEntry;

    public TargetContext(String className, String methodSignature, Branch branch) {
        this(className, methodSignature, branch, false);
    }

    public TargetContext(String className, String methodSignature, Branch branch, boolean constructorEntry) {
        this.className = className;
        this.methodSignature = methodSignature;
        this.branch = branch;
        this.constructorEntry = constructorEntry;
    }

    public String getClassName() {
        return className;
    }

    public String getMethodSignature() {
        return methodSignature;
    }

    public Branch getBranch() {
        return branch;
    }

    public boolean isConstructorEntry() {
        return constructorEntry;
    }

    /**
     * Resolves the context for a branch in whole-class mode: its own containing method, if that
     * method is public or protected and neither a class nor an instance initializer. Otherwise the
     * branch is ineligible for object-rule synthesis (no call-graph search for now) and
     * {@code null} is returned.
     */
    public static TargetContext forBranch(Branch branch) {
        if (branch == null || branch.getInstruction() == null) {
            countOtherRejection();
            return null;
        }
        String className = branch.getInstruction().getClassName();
        String methodSignature = branch.getInstruction().getMethodName();
        if (className == null || methodSignature == null) {
            countOtherRejection();
            return null;
        }
        if (methodSignature.startsWith("<clinit>")) {
            countConstructorRejection();
            return null;
        }
        if (methodSignature.startsWith("<init>")) {
            if (Properties.OBJECT_RULE_EXPANDED_ENTRIES) {
                return new TargetContext(className, methodSignature, branch, true);
            }
            countConstructorRejection();
            return null;
        }
        try {
            Class<?> clazz = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(className);
            for (Method m : clazz.getDeclaredMethods()) {
                if ((m.getName() + MethodUtil.getSignature(m)).equals(methodSignature)) {
                    int modifiers = m.getModifiers();
                    if (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)) {
                        return new TargetContext(className, methodSignature, branch);
                    }
                    countNonPublicRejection();
                    return null;
                }
            }
        } catch (Throwable t) {
            countOtherRejection();
            return null;
        }
        countOtherRejection();
        return null;
    }

    private static void countConstructorRejection() {
        if (Properties.APPLY_OBJECT_RULE) {
            rejectedConstructorEntries++;
        }
    }

    private static void countNonPublicRejection() {
        if (Properties.APPLY_OBJECT_RULE) {
            rejectedNonPublicEntries++;
        }
    }

    private static void countOtherRejection() {
        if (Properties.APPLY_OBJECT_RULE) {
            rejectedOtherEntries++;
        }
    }

    public static int getRejectedConstructorEntries() {
        return rejectedConstructorEntries;
    }

    public static int getRejectedNonPublicEntries() {
        return rejectedNonPublicEntries;
    }

    public static int getRejectedOtherEntries() {
        return rejectedOtherEntries;
    }

    public static void resetStatistics() {
        rejectedConstructorEntries = 0;
        rejectedNonPublicEntries = 0;
        rejectedOtherEntries = 0;
    }

    /** The active context for the current synthesis, or {@code null} when none is set. */
    public static TargetContext getActive() {
        return ACTIVE.get();
    }

    public static void setActive(TargetContext context) {
        ACTIVE.set(context);
    }

    public static void clearActive() {
        ACTIVE.remove();
    }

    /**
     * The class name synthesis should treat as the target: the explicit {@code -Dtarget_class} if
     * set, otherwise the active whole-class context's class, otherwise the empty string (object rule
     * inert, as before).
     */
    public static String activeClassName() {
        if (Properties.TARGET_CLASS != null && !Properties.TARGET_CLASS.isEmpty()) {
            return Properties.TARGET_CLASS;
        }
        TargetContext context = ACTIVE.get();
        return context == null ? Properties.TARGET_CLASS : context.className;
    }

    /**
     * The method signature synthesis should treat as the target: the explicit {@code -Dtarget_method}
     * if set, otherwise the active whole-class context's method, otherwise the empty string.
     */
    public static String activeMethodSignature() {
        if (Properties.TARGET_METHOD != null && !Properties.TARGET_METHOD.isEmpty()) {
            return Properties.TARGET_METHOD;
        }
        TargetContext context = ACTIVE.get();
        return context == null ? Properties.TARGET_METHOD : context.methodSignature;
    }

    public static boolean activeConstructorEntry() {
        TargetContext context = ACTIVE.get();
        return context != null && context.constructorEntry
                && (Properties.TARGET_METHOD == null || Properties.TARGET_METHOD.isEmpty());
    }
}
