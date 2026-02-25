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
package org.evosuite.runtime.mock;

/**
 * Class used to keep track of whether instrumented mock
 * class should use their mocked functionalities, or rather
 * roll back to the original behavior.
 *
 * <p>This is not really needed during the search.
 * But it is extremely important for the generated JUnit tests.
 * A typical problem happens when "manual" tests are executed
 * after EvoSuite generated ones: we do not want those manual
 * tests to use the mocked versions of the already loaded SUT
 * classes.
 *
 * @author arcuri
 *
 */
public class MockFramework {

    private static final String MOCK_ENABLED_PROPERTY = "evosuite.mock.enabled";
    private static volatile boolean active = false;

    /**
     * If classes are mocked, then use the mock versions
     * instead of the original.
     */
    public static void enable() {
        active = true;
        try {
            java.lang.System.setProperty(MOCK_ENABLED_PROPERTY, Boolean.TRUE.toString());
        } catch (RuntimeException ignored) {
            // Keep local state even if system properties are restricted.
        }
    }

    public static void disable() {
        active = false;
        try {
            java.lang.System.setProperty(MOCK_ENABLED_PROPERTY, Boolean.FALSE.toString());
        } catch (RuntimeException ignored) {
            // Keep local state even if system properties are restricted.
        }
    }

    public static boolean isEnabled() {
        try {
            String property = java.lang.System.getProperty(MOCK_ENABLED_PROPERTY);
            if (property != null) {
                return Boolean.parseBoolean(property);
            }
        } catch (RuntimeException ignored) {
            // Fall back to local state below.
        }
        return active;
    }
}
