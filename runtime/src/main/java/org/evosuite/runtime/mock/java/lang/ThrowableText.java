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
package org.evosuite.runtime.mock.java.lang;

/**
 * Text of mocked exceptions. This is not a mock class, so the test cluster does not offer its
 * methods to generated tests.
 */
public final class ThrowableText {

    private ThrowableText() {
    }

    /**
     * Describes a mocked exception the way {@link Throwable#toString()} does, but names the
     * class the application sees: the mock classes stand in for a JDK exception, which is
     * their superclass, so the mock's own name is replaced by it.
     *
     * @param exception the mocked exception
     * @return the class name, followed by the message if there is one
     */
    public static String describe(Throwable exception) {
        Class<?> clazz = exception.getClass();
        if (clazz.getSimpleName().startsWith("Mock")
                && clazz.getName().startsWith("org.evosuite.runtime.mock.")) {
            clazz = clazz.getSuperclass();
        }
        String message = exception.getLocalizedMessage();
        return message != null ? clazz.getName() + ": " + message : clazz.getName();
    }
}
