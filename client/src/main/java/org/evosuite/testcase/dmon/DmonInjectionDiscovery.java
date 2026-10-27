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
package org.evosuite.testcase.dmon;

import org.evosuite.Properties;
import org.evosuite.setup.TestClusterUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Shared discovery logic for DMoN injection targets.
 */
public final class DmonInjectionDiscovery {

    private DmonInjectionDiscovery() {
    }

    /**
     * Extracts the class name prefix from the null expression in a promotion plan.
     * For example, given {@code "jaw.Main.janelaPrincipal"}, returns {@code "jaw.Main"}.
     * This is useful when the null field belongs to a different class than the one
     * where the constructor failure occurred.
     *
     * @param plan the promotion plan
     * @return the class name prefix, or null if not extractable
     */
    public static String resolveFieldOwnerClassName(DmonPromotionPlan plan) {
        if (plan == null) {
            return null;
        }
        Optional<String> nullExpression = plan.getNullExpression();
        if (!nullExpression.isPresent()) {
            return null;
        }
        String expr = nullExpression.get().trim();
        int dot = expr.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String prefix = expr.substring(0, dot);
        // Sanity check: must look like a qualified class name (contains at least one dot
        // or starts with an uppercase letter suggesting a class in the default package).
        if (prefix.contains(".") || Character.isUpperCase(prefix.charAt(0))) {
            return prefix;
        }
        return null;
    }

    /**
     * Resolves the field name from a promotion plan.
     *
     * @param plan the promotion plan
     * @return the resolved field name, or null
     */
    public static String resolveFieldName(DmonPromotionPlan plan) {
        if (plan == null) {
            return null;
        }
        Optional<String> nullExpression = plan.getNullExpression();
        if (nullExpression.isPresent()) {
            String expr = nullExpression.get().trim();
            int dot = expr.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < expr.length()) {
                return expr.substring(dot + 1);
            }
            if (!expr.isEmpty()) {
                return expr;
            }
        }
        return plan.getMemberToken().orElse(null);
    }

    /**
     * Finds a non-final field of the given kind in the given class or its superclasses.
     *
     * @param ownerClass the class to search
     * @param fieldName the name of the field
     * @param wantStatic true for a static field, false for an instance field
     * @return the field if found and valid, or null
     */
    public static Field findField(Class<?> ownerClass, String fieldName, boolean wantStatic) {
        if (ownerClass == null || fieldName == null || fieldName.trim().isEmpty()) {
            return null;
        }
        for (Class<?> c = ownerClass; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                // TestClusterUtils.isFinalField, unlike a raw Modifier.isFinal check,
                // also catches a field whose ACC_FINAL was stripped by instrumentation
                // (CreateClassResetClassAdapter) for the bytecode-level static-reset
                // machinery: such a field reflects as non-final but must still never be
                // used as a generated-test assignment target.
                if (Modifier.isStatic(f.getModifiers()) == wantStatic && !TestClusterUtils.isFinalField(f)) {
                    return f;
                }
            } catch (NoSuchFieldException expected) {
            }
        }
        return null;
    }

    /**
     * Finds the public setter that best accepts a value of the dependency type, preferring one
     * whose name mentions the field. A static setter may also be named register*, init* or
     * configure*, and ranks higher the more often the target class calls it.
     *
     * @param ownerClass the class to search
     * @param dependencyType the type of the dependency
     * @param fieldNameHint an optional hint for the field name
     * @param wantStatic true for a static setter, false for an instance setter
     * @return the best matching setter method, or null
     */
    public static Method findSetter(Class<?> ownerClass, Class<?> dependencyType, String fieldNameHint,
                                    boolean wantStatic) {
        if (ownerClass == null || dependencyType == null) {
            return null;
        }
        Method best = null;
        int bestScore = Integer.MIN_VALUE;
        String normalizedFieldHint = fieldNameHint == null ? "" : fieldNameHint.toLowerCase(Locale.ROOT);
        Map<String, Integer> usage = wantStatic ? countStaticCallsFromTarget(ownerClass) : Collections.emptyMap();
        for (Method m : ownerClass.getMethods()) {
            int mods = m.getModifiers();
            if (Modifier.isStatic(mods) != wantStatic || !Modifier.isPublic(mods)) {
                continue;
            }
            if (m.getParameterTypes().length != 1) {
                continue;
            }
            Class<?> param = m.getParameterTypes()[0];
            if (!param.isAssignableFrom(dependencyType) && !dependencyType.isAssignableFrom(param)) {
                continue;
            }
            String name = m.getName().toLowerCase(Locale.ROOT);
            int score;
            if (name.startsWith("set")) {
                score = 20;
            } else if (wantStatic && name.startsWith("register")) {
                score = 15;
            } else if (wantStatic && (name.startsWith("init") || name.startsWith("configure"))) {
                score = 10;
            } else {
                continue;
            }
            if (!normalizedFieldHint.isEmpty() && name.contains(normalizedFieldHint)) {
                score += 30;
            }
            if (param.equals(dependencyType)) {
                score += 5;
            }
            Integer usageCount = usage.get(methodKey(m));
            if (usageCount != null) {
                score += usageCount * 100;
            }
            if (best == null || score > bestScore) {
                best = m;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Finds a static method that returns an instance of the class.
     *
     * @param ownerClass the class to search
     * @return the best matching accessor method, or null
     */
    public static Method findStaticInstanceAccessor(Class<?> ownerClass) {
        if (ownerClass == null) {
            return null;
        }
        Method best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Method m : ownerClass.getMethods()) {
            int mods = m.getModifiers();
            if (!Modifier.isStatic(mods) || !Modifier.isPublic(mods)) {
                continue;
            }
            if (m.getParameterTypes().length != 0) {
                continue;
            }
            Class<?> returnType = m.getReturnType();
            if (returnType == null || !ownerClass.isAssignableFrom(returnType)) {
                continue;
            }
            String name = m.getName().toLowerCase(Locale.ROOT);
            int score = 0;
            if ("getinstance".equals(name) || "instance".equals(name)) {
                score += 40;
            } else if (name.startsWith("get") || name.startsWith("instance") || name.startsWith("current")) {
                score += 20;
            } else {
                score += 5;
            }
            if (best == null || score > bestScore) {
                best = m;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Resolves the field that should be used for rollback after a setter call.
     *
     * @param ownerClass the class owning the field
     * @param setter the setter method
     * @param fieldNameHint an optional hint for the field name
     * @param wantStatic true if the setter is static
     * @return the resolved field, or null
     */
    public static Field resolveRollbackField(Class<?> ownerClass, Method setter, String fieldNameHint,
                                             boolean wantStatic) {
        if (ownerClass == null || setter == null) {
            return null;
        }
        if (fieldNameHint != null && !fieldNameHint.trim().isEmpty()) {
            Field direct = findField(ownerClass, fieldNameHint, wantStatic);
            if (direct != null && setter.getParameterTypes()[0].isAssignableFrom(direct.getType())) {
                return direct;
            }
        }
        String methodName = setter.getName();
        if (methodName.startsWith("set") && methodName.length() > 3) {
            String derived = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
            Field derivedField = findField(ownerClass, derived, wantStatic);
            if (derivedField != null && setter.getParameterTypes()[0].isAssignableFrom(derivedField.getType())) {
                return derivedField;
            }
        }
        return null;
    }

    /**
     * Finds a non-final field that can hold a value of the mock type: the named field if it
     * fits, otherwise the first such field in the class hierarchy.
     */
    private static Field findInjectionField(Class<?> ownerClass, Class<?> mockType, String preferredField,
                                            boolean wantStatic) {
        // findField already screens finality with the stripping-aware predicate, so this
        // fast path is covered too.
        Field preferred = findField(ownerClass, preferredField, wantStatic);
        if (preferred != null && preferred.getType().isAssignableFrom(mockType)) {
            return preferred;
        }
        for (Class<?> c = ownerClass; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) != wantStatic || TestClusterUtils.isFinalField(f)) {
                    continue;
                }
                if (f.getType().isAssignableFrom(mockType)) {
                    return f;
                }
            }
        }
        return null;
    }

    /**
     * Where a mock of a missing dependency can be put on the owner class: a static setter or
     * static field, or a setter or field of the instance a static accessor returns.
     */
    static final class InjectionTarget {
        final Method staticSetter;
        final Field staticField;
        final Method instanceAccessor;
        final Method instanceSetter;
        final Field instanceField;

        private InjectionTarget(Method staticSetter, Field staticField, Method instanceAccessor,
                                Method instanceSetter, Field instanceField) {
            this.staticSetter = staticSetter;
            this.staticField = staticField;
            this.instanceAccessor = instanceAccessor;
            this.instanceSetter = instanceSetter;
            this.instanceField = instanceField;
        }

        boolean isEmpty() {
            return staticSetter == null && staticField == null && instanceSetter == null && instanceField == null;
        }
    }

    /**
     * Looks for a place to inject a mock of the given type, preferring a static setter, then a
     * static field, then a setter, then a field of the instance a static accessor returns.
     * The field fallbacks are only tried if {@code allowReflection} is set.
     *
     * @return the target found, empty if the class offers none
     */
    static InjectionTarget resolveTarget(Class<?> ownerClass, Class<?> mockType, String fieldHint,
                                         boolean allowReflection) {
        Method staticSetter = findSetter(ownerClass, mockType, fieldHint, true);
        Field staticField = staticSetter == null && allowReflection
                ? findInjectionField(ownerClass, mockType, fieldHint, true)
                : null;
        Method accessor = null;
        Method instanceSetter = null;
        Field instanceField = null;
        if (staticSetter == null && staticField == null) {
            accessor = findStaticInstanceAccessor(ownerClass);
            if (accessor != null) {
                instanceSetter = findSetter(ownerClass, mockType, fieldHint, false);
                if (instanceSetter == null && allowReflection) {
                    instanceField = findInjectionField(ownerClass, mockType, fieldHint, false);
                }
            }
        }
        return new InjectionTarget(staticSetter, staticField, accessor, instanceSetter, instanceField);
    }

    static Class<?> loadClass(String className, ClassLoader loader) {
        if (className == null || className.trim().isEmpty()) {
            return null;
        }
        try {
            return Class.forName(className.trim(), false, loader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static String methodKey(Method m) {
        return m.getName() + "#" + m.getParameterTypes()[0].getName();
    }

    private static Map<String, Integer> countStaticCallsFromTarget(Class<?> ownerClass) {
        Map<String, Integer> result = new HashMap<>();
        String targetClass = Properties.TARGET_CLASS;
        if (targetClass == null || targetClass.trim().isEmpty() || ownerClass == null) {
            return result;
        }
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) {
                loader = ownerClass.getClassLoader();
            }
            if (loader == null) {
                return result;
            }
            String resource = targetClass.replace('.', '/') + ".class";
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in == null) {
                    return result;
                }
                ClassReader reader = new ClassReader(in);
                ClassNode node = new ClassNode();
                reader.accept(node, ClassReader.SKIP_FRAMES);
                for (Object mobj : node.methods) {
                    MethodNode mn = (MethodNode) mobj;
                    for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (!(insn instanceof MethodInsnNode)) {
                            continue;
                        }
                        MethodInsnNode mi = (MethodInsnNode) insn;
                        if (mi.getOpcode() != Opcodes.INVOKESTATIC) {
                            continue;
                        }
                        String owner = mi.owner == null ? "" : mi.owner.replace('/', '.');
                        if (!ownerClass.getName().equals(owner)) {
                            continue;
                        }
                        String methodKey = mi.name + "#" + descriptorFirstParamType(mi.desc);
                        result.put(methodKey, result.getOrDefault(methodKey, 0) + 1);
                    }
                }
            }
        } catch (IOException | RuntimeException expected) {
            // Best-effort ranking signal only.
        }
        return result;
    }

    private static String descriptorFirstParamType(String descriptor) {
        if (descriptor == null || !descriptor.startsWith("(")) {
            return "";
        }
        int end = descriptor.indexOf(')');
        if (end <= 1) {
            return "";
        }
        String first = descriptor.substring(1, end);
        if (first.startsWith("L") && first.endsWith(";")) {
            return first.substring(1, first.length() - 1).replace('/', '.');
        }
        return "";
    }
}
