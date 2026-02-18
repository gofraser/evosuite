/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.assertion;

import org.evosuite.Properties;

import java.net.URL;
import java.util.regex.Pattern;

/**
 * Shared utility for filtering string values in assertions to avoid brittleness
 * and non-determinism.
 */
public final class StringValueFilter {

    // Matches Java's default Object.toString() format: e.g., "com.example.Foo@1a2b3c4d"
    // or nested occurrences like "[Foo@abc, Bar@def]". Requires at least 2 hex chars after @.
    private static final Pattern ADDRESS_PATTERN =
            Pattern.compile("[A-Za-z_$][\\w.]*@[a-f\\d]{2,}", Pattern.MULTILINE);

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

        int length = value.length();

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

        // Contextual filtering for URLs
        if (target instanceof URL) {
            if (value.startsWith("/") || value.startsWith("file:/")) {
                return true;
            }
        }

        return false;
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
