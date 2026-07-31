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
        if (type == null || type.isPrimitive()) {
            return true;
        }
        if (type.isArray()) {
            return isAccessible(type.getComponentType());
        }
        String targetPackage = packageName(Properties.TARGET_CLASS);
        for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
            if (!Modifier.isPublic(current.getModifiers())
                    && !packageName(current.getName()).equals(targetPackage)) {
                return false;
            }
        }
        return TestUsageChecker.canUse(type);
    }

    public static Class<?> accessibleView(Class<?> type) {
        if (isAccessible(type)) {
            return type;
        }
        for (Class<?> current = type == null ? null : type.getSuperclass();
             current != null; current = current.getSuperclass()) {
            if (isAccessible(current)) {
                return current;
            }
        }
        if (type != null) {
            for (Class<?> candidate : type.getInterfaces()) {
                if (isAccessible(candidate)) {
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
