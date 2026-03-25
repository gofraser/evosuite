/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testcase.dmon;

import org.evosuite.Properties;
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

    public static Field findStaticField(Class<?> ownerClass, String fieldName) {
        if (ownerClass == null || fieldName == null || fieldName.trim().isEmpty()) {
            return null;
        }
        for (Class<?> c = ownerClass; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                int mods = f.getModifiers();
                if (Modifier.isStatic(mods) && !Modifier.isFinal(mods)) {
                    return f;
                }
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    public static Field findInstanceField(Class<?> ownerClass, String fieldName) {
        if (ownerClass == null || fieldName == null || fieldName.trim().isEmpty()) {
            return null;
        }
        for (Class<?> c = ownerClass; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                int mods = f.getModifiers();
                if (!Modifier.isStatic(mods) && !Modifier.isFinal(mods)) {
                    return f;
                }
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    public static Method findStaticSetter(Class<?> ownerClass, Class<?> dependencyType, String fieldNameHint) {
        if (ownerClass == null || dependencyType == null) {
            return null;
        }
        Method best = null;
        int bestScore = Integer.MIN_VALUE;
        String normalizedFieldHint = fieldNameHint == null ? "" : fieldNameHint.toLowerCase(Locale.ROOT);
        Map<String, Integer> usage = countStaticCallsFromTarget(ownerClass);
        for (Method m : ownerClass.getMethods()) {
            int mods = m.getModifiers();
            if (!Modifier.isStatic(mods) || !Modifier.isPublic(mods)) {
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
            if (!(name.startsWith("set") || name.startsWith("register")
                    || name.startsWith("init") || name.startsWith("configure"))) {
                continue;
            }
            int score = 0;
            if (name.startsWith("set")) {
                score += 20;
            } else if (name.startsWith("register")) {
                score += 15;
            } else if (name.startsWith("init") || name.startsWith("configure")) {
                score += 10;
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

    public static Field resolveRollbackFieldForSetter(Class<?> ownerClass, Method setter, String fieldNameHint) {
        if (ownerClass == null || setter == null) {
            return null;
        }
        if (fieldNameHint != null && !fieldNameHint.trim().isEmpty()) {
            Field direct = findStaticField(ownerClass, fieldNameHint);
            if (direct != null && setter.getParameterTypes()[0].isAssignableFrom(direct.getType())) {
                return direct;
            }
        }
        String methodName = setter.getName();
        if (methodName.startsWith("set") && methodName.length() > 3) {
            String derived = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
            Field derivedField = findStaticField(ownerClass, derived);
            if (derivedField != null && setter.getParameterTypes()[0].isAssignableFrom(derivedField.getType())) {
                return derivedField;
            }
        }
        return null;
    }

    public static Field resolveRollbackFieldForInstanceSetter(Class<?> ownerClass, Method setter, String fieldNameHint) {
        if (ownerClass == null || setter == null) {
            return null;
        }
        if (fieldNameHint != null && !fieldNameHint.trim().isEmpty()) {
            Field direct = findInstanceField(ownerClass, fieldNameHint);
            if (direct != null && setter.getParameterTypes()[0].isAssignableFrom(direct.getType())) {
                return direct;
            }
        }
        String methodName = setter.getName();
        if (methodName.startsWith("set") && methodName.length() > 3) {
            String derived = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
            Field derivedField = findInstanceField(ownerClass, derived);
            if (derivedField != null && setter.getParameterTypes()[0].isAssignableFrom(derivedField.getType())) {
                return derivedField;
            }
        }
        return null;
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
                for (Object mObj : node.methods) {
                    MethodNode mn = (MethodNode) mObj;
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
        } catch (IOException | RuntimeException ignored) {
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
