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
package org.evosuite.testparser;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

class OverloadResolver {

    static Method findByNameLoose(Class<?> clazz, String name) {
        Method noArgs = null;
        Method first = null;
        for (Method method : clazz.getMethods()) {
            if (!method.getName().equals(name)) {
                continue;
            }
            if (first == null) {
                first = method;
            }
            if (method.getParameterCount() == 0) {
                noArgs = method;
            }
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (!method.getName().equals(name)) {
                continue;
            }
            if (first == null) {
                first = method;
            }
            if (method.getParameterCount() == 0 && noArgs == null) {
                noArgs = method;
            }
        }
        return noArgs != null ? noArgs : first;
    }

    Constructor<?> resolveConstructor(Class<?> clazz, Class<?>[] argTypes)
            throws NoSuchMethodException {
        try {
            return clazz.getDeclaredConstructor(argTypes);
        } catch (NoSuchMethodException ignored) {
            // Ignore and try compatibility match
        }

        Constructor<?> best = null;
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            if (!isCompatible(c.getParameterTypes(), c.isVarArgs(), argTypes)) {
                continue;
            }
            if (best == null || isBetterExecutableMatch(c.getParameterTypes(), c.isVarArgs(),
                    best.getParameterTypes(), best.isVarArgs(), argTypes)) {
                best = c;
            }
        }
        if (best != null) {
            return best;
        }

        StringBuilder msg = new StringBuilder();
        msg.append("No matching constructor found for ").append(clazz.getName())
                .append(" with args ").append(formatTypes(argTypes));
        Constructor<?>[] declared = clazz.getDeclaredConstructors();
        if (declared.length > 0) {
            msg.append(". Available constructors: ");
            for (int i = 0; i < declared.length; i++) {
                if (i > 0) {
                    msg.append("; ");
                }
                msg.append(clazz.getSimpleName()).append(formatTypes(declared[i].getParameterTypes()));
                if (declared[i].isVarArgs()) {
                    msg.append(" varargs");
                }
            }
        }
        throw new NoSuchMethodException(msg.toString());
    }

    Method resolveMethod(Class<?> clazz, String name, Class<?>[] argTypes)
            throws NoSuchMethodException {
        try {
            return clazz.getMethod(name, argTypes);
        } catch (NoSuchMethodException ignored) {
            // Ignore and try compatibility match
        }

        Method bestPublic = findMostSpecificMethod(clazz.getMethods(), name, argTypes);
        if (bestPublic != null) {
            return bestPublic;
        }

        Method bestDeclared = findMostSpecificMethod(clazz.getDeclaredMethods(), name, argTypes);
        if (bestDeclared != null) {
            return bestDeclared;
        }

        throw new NoSuchMethodException("No matching method found: " + clazz.getName()
                + "." + name + " with args " + formatTypes(argTypes));
    }

    boolean hasMethodNamed(Class<?> clazz, String name, boolean requireStatic) {
        for (Method method : clazz.getMethods()) {
            if (!method.getName().equals(name)) {
                continue;
            }
            if (requireStatic && !Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            return true;
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (!method.getName().equals(name)) {
                continue;
            }
            if (requireStatic && !Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            return true;
        }
        return false;
    }

    private Method findMostSpecificMethod(Method[] methods, String name, Class<?>[] argTypes) {
        Method best = null;
        for (Method m : methods) {
            if (m.isBridge() || !m.getName().equals(name)
                    || !isCompatible(m.getParameterTypes(), m.isVarArgs(), argTypes)) {
                continue;
            }
            if (best == null || isBetterExecutableMatch(m.getParameterTypes(), m.isVarArgs(),
                    best.getParameterTypes(), best.isVarArgs(), argTypes)) {
                best = m;
            }
        }
        return best;
    }

    private boolean isMoreSpecific(Class<?>[] a, Class<?>[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!b[i].isAssignableFrom(a[i])) {
                return false;
            }
        }
        return true;
    }

    private boolean isCompatible(Class<?>[] formalTypes, boolean isVarArgs, Class<?>[] actualTypes) {
        if (!isVarArgs) {
            if (formalTypes.length != actualTypes.length) {
                return false;
            }
            for (int i = 0; i < formalTypes.length; i++) {
                if (!isAssignableFrom(formalTypes[i], actualTypes[i])) {
                    return false;
                }
            }
            return true;
        }

        if (formalTypes.length == 0) {
            return actualTypes.length == 0;
        }

        int fixedCount = formalTypes.length - 1;
        if (actualTypes.length < fixedCount) {
            return false;
        }

        for (int i = 0; i < fixedCount; i++) {
            if (!isAssignableFrom(formalTypes[i], actualTypes[i])) {
                return false;
            }
        }

        Class<?> varArgArrayType = formalTypes[formalTypes.length - 1];
        Class<?> componentType = varArgArrayType.getComponentType();
        if (componentType == null) {
            return false;
        }

        if (usesExplicitVarArgsArray(formalTypes, true, actualTypes)) {
            return true;
        }

        for (int i = fixedCount; i < actualTypes.length; i++) {
            if (!isAssignableFrom(componentType, actualTypes[i])) {
                return false;
            }
        }
        return true;
    }

    static boolean usesExplicitVarArgsArray(Class<?>[] formalTypes, boolean isVarArgs, Class<?>[] actualTypes) {
        if (!isVarArgs || formalTypes.length == 0 || actualTypes.length != formalTypes.length) {
            return false;
        }
        Class<?> varArgArrayType = formalTypes[formalTypes.length - 1];
        Class<?> lastActual = actualTypes[actualTypes.length - 1];
        return isAssignableFrom(varArgArrayType, lastActual);
    }

    static Class<?> getFormalTypeForArgument(Class<?>[] formalTypes,
                                             boolean isVarArgs,
                                             int argIndex,
                                             int actualCount,
                                             boolean explicitVarArgArray) {
        if (formalTypes == null || formalTypes.length == 0) {
            return null;
        }
        if (!isVarArgs) {
            return argIndex < formalTypes.length ? formalTypes[argIndex] : null;
        }

        int fixedCount = formalTypes.length - 1;
        if (argIndex < fixedCount) {
            return formalTypes[argIndex];
        }
        if (argIndex >= actualCount) {
            return null;
        }
        Class<?> varArgArrayType = formalTypes[formalTypes.length - 1];
        if (explicitVarArgArray && argIndex == formalTypes.length - 1) {
            return varArgArrayType;
        }
        Class<?> componentType = varArgArrayType.getComponentType();
        return componentType != null ? componentType : varArgArrayType;
    }

    private boolean isBetterExecutableMatch(Class<?>[] candidateFormalTypes,
                                            boolean candidateVarArgs,
                                            Class<?>[] bestFormalTypes,
                                            boolean bestVarArgs,
                                            Class<?>[] actualTypes) {
        if (candidateVarArgs != bestVarArgs) {
            return !candidateVarArgs;
        }
        int candidateScore = compatibilityScore(candidateFormalTypes, candidateVarArgs, actualTypes);
        int bestScore = compatibilityScore(bestFormalTypes, bestVarArgs, actualTypes);
        if (candidateScore != bestScore) {
            return candidateScore < bestScore;
        }
        return candidateFormalTypes.length == bestFormalTypes.length
                && isMoreSpecific(candidateFormalTypes, bestFormalTypes);
    }

    private int compatibilityScore(Class<?>[] formalTypes, boolean isVarArgs, Class<?>[] actualTypes) {
        if (!isCompatible(formalTypes, isVarArgs, actualTypes)) {
            return Integer.MAX_VALUE;
        }

        boolean explicitVarArgArray = usesExplicitVarArgsArray(formalTypes, isVarArgs, actualTypes);
        int score = isVarArgs ? 10 : 0;
        for (int i = 0; i < actualTypes.length; i++) {
            Class<?> formal = getFormalTypeForArgument(formalTypes, isVarArgs, i, actualTypes.length, explicitVarArgArray);
            if (formal == null) {
                score += 100;
                continue;
            }
            Class<?> actual = actualTypes[i];
            if (formal.equals(actual)) {
                continue;
            }
            if (actual == Void.class && !formal.isPrimitive()) {
                score += 1;
                continue;
            }
            if (formal.isPrimitive() && box(formal) == actual) {
                score += 1;
                continue;
            }
            if (actual.isPrimitive() && box(actual) == formal) {
                score += 1;
                continue;
            }
            if (formal.isAssignableFrom(actual)) {
                score += 2;
                continue;
            }
            score += 3;
        }
        return score;
    }

    static boolean isAssignableFrom(Class<?> formal, Class<?> actual) {
        if (formal.isAssignableFrom(actual)) {
            return true;
        }

        if (actual.isPrimitive()) {
            Class<?> boxed = box(actual);
            if (boxed != null && formal.isAssignableFrom(boxed)) {
                return true;
            }
        }
        if (formal.isPrimitive()) {
            Class<?> actualUnboxed = unbox(actual);
            if (actualUnboxed != null && formal == actualUnboxed) {
                return true;
            }
        }

        Class<?> formalUnboxed = unbox(formal);
        Class<?> actualUnboxed = unbox(actual);
        if (formalUnboxed != null && actualUnboxed != null) {
            if (formalUnboxed == actualUnboxed) {
                return true;
            }
            if (isWidenable(formalUnboxed, actualUnboxed)) {
                return true;
            }
        }

        if (actual == Void.class && !formal.isPrimitive()) {
            return true;
        }

        return false;
    }

    static Class<?> box(Class<?> clazz) {
        if (clazz == int.class) {
            return Integer.class;
        }
        if (clazz == long.class) {
            return Long.class;
        }
        if (clazz == double.class) {
            return Double.class;
        }
        if (clazz == float.class) {
            return Float.class;
        }
        if (clazz == boolean.class) {
            return Boolean.class;
        }
        if (clazz == char.class) {
            return Character.class;
        }
        if (clazz == byte.class) {
            return Byte.class;
        }
        if (clazz == short.class) {
            return Short.class;
        }
        return null;
    }

    static Class<?> unbox(Class<?> clazz) {
        if (clazz == Integer.class) {
            return int.class;
        }
        if (clazz == Long.class) {
            return long.class;
        }
        if (clazz == Double.class) {
            return double.class;
        }
        if (clazz == Float.class) {
            return float.class;
        }
        if (clazz == Boolean.class) {
            return boolean.class;
        }
        if (clazz == Character.class) {
            return char.class;
        }
        if (clazz == Byte.class) {
            return byte.class;
        }
        if (clazz == Short.class) {
            return short.class;
        }
        if (clazz.isPrimitive()) {
            return clazz;
        }
        return null;
    }

    static boolean isWidenable(Class<?> target, Class<?> source) {
        if (target == short.class) {
            return source == byte.class;
        }
        if (target == int.class) {
            return source == byte.class || source == short.class || source == char.class;
        }
        if (target == long.class) {
            return source == byte.class || source == short.class
                    || source == char.class || source == int.class;
        }
        if (target == float.class) {
            return source == byte.class || source == short.class
                    || source == char.class || source == int.class || source == long.class;
        }
        if (target == double.class) {
            return source == byte.class || source == short.class
                    || source == char.class || source == int.class || source == long.class
                    || source == float.class;
        }
        return false;
    }

    private static String formatTypes(Class<?>[] types) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(types[i].getSimpleName());
        }
        return sb.append(")").toString();
    }
}
