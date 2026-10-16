/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.llm.postprocess;

import org.evosuite.Properties;
import org.evosuite.setup.TestUsageChecker;

import java.lang.reflect.Modifier;

/** Read-only source-accessibility predicate shared by prompt filtering and validation. */
public final class OracleTypeAccessibility {

    private OracleTypeAccessibility() {
        // Utility class.
    }

    public static boolean isAccessible(Class<?> type) {
        return isAccessible(type, Properties.TARGET_CLASS);
    }

    /**
     * Tells whether test code in the package of the target class can refer to a type: the type and all its enclosing
     * classes must be public or in that package, and the type must be usable in generated tests.
     *
     * @param type the type to check, may be null
     * @param targetClass the name of the class under test
     * @return true if the type is accessible; primitives and null are always accessible
     */
    public static boolean isAccessible(Class<?> type, String targetClass) {
        if (type == null || type.isPrimitive()) {
            return true;
        }
        if (type.isArray()) {
            return isAccessible(type.getComponentType(), targetClass);
        }
        String targetPackage = packageName(targetClass);
        for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
            if (!Modifier.isPublic(current.getModifiers())
                    && !packageName(current.getName()).equals(targetPackage)) {
                return false;
            }
        }
        return TestUsageChecker.canUse(type);
    }

    public static Class<?> accessibleView(Class<?> type) {
        return accessibleView(type, Properties.TARGET_CLASS);
    }

    /**
     * Finds the closest accessible supertype of a type: the type itself, else the nearest accessible superclass,
     * else the first accessible directly implemented interface.
     *
     * @param type the type to look at, may be null
     * @param targetClass the name of the class under test
     * @return the accessible type, or null if there is none
     */
    public static Class<?> accessibleView(Class<?> type, String targetClass) {
        if (isAccessible(type, targetClass)) {
            return type;
        }
        for (Class<?> current = type == null ? null : type.getSuperclass();
                current != null; current = current.getSuperclass()) {
            if (isAccessible(current, targetClass)) {
                return current;
            }
        }
        if (type != null) {
            for (Class<?> candidate : type.getInterfaces()) {
                if (isAccessible(candidate, targetClass)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String packageName(String className) {
        int separator = className == null ? -1 : className.lastIndexOf('.');
        return separator < 0 ? "" : className.substring(0, separator);
    }
}
