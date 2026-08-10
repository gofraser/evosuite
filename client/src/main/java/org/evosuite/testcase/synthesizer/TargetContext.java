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

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.TestUsageChecker;
import org.evosuite.setup.callgraph.CallGraph;
import org.evosuite.setup.callgraph.CallGraphEntry;
import org.evosuite.utils.MethodUtil;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * The class/method the object-construction synthesis is currently working against. It is derived
 * per branch from the branch's own containing method, with an explicit target method retained only
 * for setup compatibility in focused analyses.
 *
 * <p>Rather than threading a context object through every synthesis method signature, the context
 * is published for the current run via the run-scoped {@code ObjectRuleRuntime} and consulted at
 * the few points that previously read {@link Properties#TARGET_CLASS}/{@link
 * Properties#TARGET_METHOD} through {@link #activeClassName()}/{@link #activeMethodSignature()}.
 */
public class TargetContext {

    private final String className;
    private final String methodSignature;
    private final Branch branch;
    private final boolean constructorEntry;
    private final String branchMethodSignature;

    public TargetContext(String className, String methodSignature, Branch branch) {
        this(className, methodSignature, branch, false);
    }

    public TargetContext(String className, String methodSignature, Branch branch, boolean constructorEntry) {
        this.className = className;
        this.methodSignature = methodSignature;
        this.branch = branch;
        this.constructorEntry = constructorEntry;
        this.branchMethodSignature = branch == null || branch.getInstruction() == null
                ? methodSignature
                : branch.getInstruction().getMethodName();
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

    public String getBranchMethodSignature() {
        return branchMethodSignature;
    }

    /**
     * Resolves the callable entry context for a branch, using the branch's own method when it is
     * accessible and otherwise the nearest accessible caller. Initializers and unresolved entries
     * are rejected.
     */
    public static TargetContext forBranch(Branch branch) {
        return resolveForBranch(branch);
    }

    private static TargetContext resolveForBranch(Branch branch) {
        if (branch == null || branch.getInstruction() == null) {
            return null;
        }
        String className = branch.getInstruction().getClassName();
        String methodSignature = branch.getInstruction().getMethodName();
        if (className == null || methodSignature == null) {
            return null;
        }
        if (methodSignature.startsWith("<clinit>")) {
            return null;
        }
        if (methodSignature.startsWith("<init>")) {
            return new TargetContext(className, methodSignature, branch, true);
        }
        try {
            Class<?> clazz =
                    TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(className);
            for (Method m : clazz.getDeclaredMethods()) {
                if ((m.getName() + MethodUtil.getSignature(m)).equals(methodSignature)) {
                    if (isDirectlyAccessibleEntry(m, clazz)) {
                        return new TargetContext(className, methodSignature, branch);
                    }
                    String caller = nearestAccessibleCaller(className, methodSignature, clazz);
                    if (caller != null) {
                        return new TargetContext(className, caller, branch);
                    }
                    return null;
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    private static String nearestAccessibleCaller(
            String className, String methodSignature, Class<?> clazz) {
        CallGraph callGraph = DependencyAnalysis.getCallGraph();
        if (callGraph == null) {
            return null;
        }
        ArrayDeque<CallGraphEntry> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        Set<CallGraphEntry> visited = new HashSet<>();
        CallGraphEntry root = new CallGraphEntry(className, methodSignature);
        queue.add(root);
        depths.add(0);
        visited.add(root);

        while (!queue.isEmpty()) {
            CallGraphEntry current = queue.remove();
            int depth = depths.remove();
            if (depth >= 3) {
                continue;
            }
            for (CallGraphEntry caller : callGraph.getGraph().getNeighbors(current)) {
                if (!visited.add(caller)) {
                    continue;
                }
                if (!className.equals(caller.getClassName())) {
                    continue;
                }
                String callerMethod = caller.getMethodName();
                if (isAccessibleDeclaredMethod(clazz, callerMethod)) {
                    return callerMethod;
                }
                queue.add(caller);
                depths.add(depth + 1);
            }
        }
        return null;
    }

    private static boolean isAccessibleDeclaredMethod(Class<?> clazz, String methodSignature) {
        if (methodSignature == null || methodSignature.startsWith("<")) {
            return false;
        }
        for (Method m : clazz.getDeclaredMethods()) {
            if ((m.getName() + MethodUtil.getSignature(m)).equals(methodSignature)) {
                return TestUsageChecker.canUse(m, clazz);
            }
        }
        return false;
    }

    private static boolean isDirectlyAccessibleEntry(Method method, Class<?> owner) {
        int modifiers = method.getModifiers();
        return Modifier.isPublic(modifiers)
                || Modifier.isProtected(modifiers)
                || TestUsageChecker.canUse(method, owner);
    }

    /**
     * The active context for the current run, or {@code null} when none is set.
     */
    public static TargetContext getActive() {
        return TestGenerationContext.getInstance().getObjectRuleRuntime().getActiveTargetContext();
    }

    public static void setActive(TargetContext context) {

        TestGenerationContext.getInstance().getObjectRuleRuntime().setActiveTargetContext(context);
    }

    public static void clearActive() {

        TestGenerationContext.getInstance().getObjectRuleRuntime().setActiveTargetContext(null);
    }

    /**
     * The class name synthesis should treat as the target: the explicit {@code -Dtarget_class} if
     * set, otherwise the active whole-class context's class, otherwise the empty string (object
     * rule inert, as before).
     */
    public static String activeClassName() {
        if (Properties.TARGET_CLASS != null && !Properties.TARGET_CLASS.isEmpty()) {
            return Properties.TARGET_CLASS;
        }
        TargetContext context = getActive();
        return context == null ? Properties.TARGET_CLASS : context.className;
    }

    /**
     * The method signature synthesis should treat as the target: the explicit {@code
     * -Dtarget_method} if set, otherwise the active whole-class context's method, otherwise the
     * empty string.
     */
    public static String activeMethodSignature() {
        if (Properties.TARGET_METHOD != null && !Properties.TARGET_METHOD.isEmpty()) {
            return Properties.TARGET_METHOD;
        }
        TargetContext context = getActive();
        return context == null ? Properties.TARGET_METHOD : context.methodSignature;
    }

    public static boolean activeConstructorEntry() {
        TargetContext context = getActive();
        return context != null && context.constructorEntry
                && (Properties.TARGET_METHOD == null || Properties.TARGET_METHOD.isEmpty());
    }
}
