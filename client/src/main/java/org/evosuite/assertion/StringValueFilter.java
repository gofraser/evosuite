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
package org.evosuite.assertion;

import org.evosuite.Properties;
import org.evosuite.testcase.execution.Scope;

import java.net.URL;
import java.util.regex.Pattern;

/**
 * Shared utility for filtering string values in assertions to avoid brittleness
 * and non-determinism.
 */
public final class StringValueFilter {

    // Matches Java's default Object.toString() format:
    // "com.example.Foo@1a2b3c4d", "com.example.Outer$Inner@2", "Foo@A".
    // Also catches nested occurrences like "[Foo@abc, Bar@def]".
    private static final Pattern ADDRESS_PATTERN =
            Pattern.compile("(?:[A-Za-z_$][\\w$]*\\.)*[A-Za-z_$][\\w$]*@[A-Fa-f\\d]+(?![A-Za-z\\d_])",
                    Pattern.MULTILINE);

    // AWT components that have not been named get a default name built lazily from a
    // static per-class counter (java.awt.Component#constructComponentName), e.g.
    // "dialog771", "frame3", "panel0". The number depends on how many components of that
    // class the JVM has created so far, i.e. on global, test-order dependent state that
    // EvoSuite cannot reset because JDK classes are not instrumented.
    private static final String AWT_DEFAULT_NAMES =
            "(?:dialog|frame|panel|canvas|button|label|checkbox|choice|list|"
                    + "scrollbar|scrollpane|textfield|textarea|text|menu|menubar|menuitem|win|"
                    + "filedlg|popup|checkboxmenuitem)\\d+";

    private static final Pattern AWT_DEFAULT_NAME_PATTERN = Pattern.compile(
            "(?<![A-Za-z\\d_])" + AWT_DEFAULT_NAMES + "(?![A-Za-z\\d_])");

    // The two shapes a default name takes once a class that is not itself a component hands it
    // on: the bare getName() result ("dialog549") and the name field of Component#paramString
    // inside a toString() ("...JDialogSwingWindow[dialog540,0,0,0x0,...").
    private static final Pattern AWT_DEFAULT_NAME_RELAYED_PATTERN = Pattern.compile(
            "^" + AWT_DEFAULT_NAMES + "$|\\[" + AWT_DEFAULT_NAMES + ",");

    private StringValueFilter() {
        // Utility class
    }

    /**
     * Returns true if the string value should be excluded from assertions.
     *
     * @param value the string value to check
     * @return true if it should be filtered
     */
    public static boolean shouldFilter(String value) {
        return shouldFilter(value, null);
    }

    /**
     * Returns true if the string value should be excluded from assertions.
     *
     * @param value  the string value to check
     * @param target the object that produced the string (optional, for contextual filtering)
     * @return true if it should be filtered
     */
    public static boolean shouldFilter(String value, Object target) {
        if (value == null) {
            return false;
        }

        int length;
        try {
            length = value.length();
        } catch (NullPointerException e) {
            // Corrupted String with null internal value array (e.g. from unsafe deserialization).
            return true;
        }

        // Maximum length of strings we look at
        if (length > Properties.MAX_STRING) {
            return true;
        }

        // String literals may not be longer than 32767
        if (length >= 32767) {
            return true;
        }

        // Avoid asserting anything on values referring to mockito proxy objects
        String lowerValue = value.toLowerCase();
        if (lowerValue.contains("enhancerbymockito") || lowerValue.contains("$mockitomock$")) {
            return true;
        }

        // Check if there is an object identity reference (e.g. ClassName@hex)
        // that would make the test nondeterministic across JVM runs
        if (ADDRESS_PATTERN.matcher(value).find()) {
            return true;
        }

        // Default AWT component names depend on global creation counters
        if (target != null && isAwtComponentClass(target.getClass())
                && AWT_DEFAULT_NAME_PATTERN.matcher(value).find()) {
            return true;
        }

        // Contextual filtering for URLs
        if (target instanceof URL) {
            if (value.startsWith("/") || value.startsWith("file:/")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns true if the string, produced by a method declared in (or called on) the given class,
     * contains an AWT default component name such as "dialog771" or "panel3" and the class is an AWT
     * component. Such values come from a JDK-wide static counter and differ between runs and test
     * orders. Trade-off: a name that the SUT assigned explicitly (setName("dialog1")) matches too
     * and is not asserted on either.
     *
     * @param value    the string value
     * @param producer the class of the component the string was obtained from (may be null)
     * @return true if the value should not be asserted on
     */
    public static boolean isAwtDefaultName(String value, Class<?> producer) {
        return value != null && producer != null && isAwtComponentClass(producer)
                && AWT_DEFAULT_NAME_PATTERN.matcher(value).find();
    }

    /**
     * Like {@link #isAwtDefaultName(String, Class)} for a string a class that is not an AWT
     * component hands on, e.g. a window helper whose getName() or toString() delegates to the
     * dialog it wraps. The producer then says nothing, so the test's own objects decide: the
     * value is filtered when it is exactly a default name or carries one as the name field of a
     * component's toString(), and some AWT component is in scope. Trade-off: in a test that
     * touches an AWT component, a string such as "panel1" the SUT computes itself is not asserted
     * on either; strings that only contain such a word somewhere are still asserted on.
     *
     * @param value the string value
     * @param scope the objects of the test execution
     * @return true if the value should not be asserted on
     */
    public static boolean isRelayedAwtDefaultName(String value, Scope scope) {
        if (value == null || scope == null
                || !AWT_DEFAULT_NAME_RELAYED_PATTERN.matcher(value).find()) {
            return false;
        }
        try {
            for (Object object : new java.util.ArrayList<>(scope.getObjects())) {
                if (object instanceof java.awt.Component || object instanceof java.awt.MenuComponent) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    private static boolean isAwtComponentClass(Class<?> clazz) {
        return java.awt.Component.class.isAssignableFrom(clazz)
                || java.awt.MenuComponent.class.isAssignableFrom(clazz);
    }

    /**
     * Returns true if the target object is a Mockito proxy.
     *
     * @param target the object to check
     * @return true if it is a Mockito proxy
     */
    public static boolean isMockitoProxy(Object target) {
        if (target == null) {
            return false;
        }
        String canonicalName = target.getClass().getCanonicalName();
        return canonicalName != null && canonicalName.contains("EnhancerByMockito");
    }
}
