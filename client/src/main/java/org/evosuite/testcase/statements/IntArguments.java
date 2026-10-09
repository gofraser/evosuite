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
package org.evosuite.testcase.statements;

/**
 * Reads the value an {@code int} parameter receives from a reflective argument.
 *
 * <p>A test passes a {@code short}, {@code byte} or {@code char} variable to an {@code int}
 * parameter, and reflection widens it, so the boxed argument is a {@code Short}, {@code Byte}
 * or {@code Character} rather than an {@code Integer}. The allocation guards read the value from
 * here so that such an argument is not mistaken for a non-dimension.</p>
 */
final class IntArguments {

    private IntArguments() {
    }

    /**
     * Returns the {@code int} value the argument widens to, or {@code null} if it does not widen
     * to {@code int}.
     */
    static Integer widened(Object argument) {
        if (argument instanceof Integer) {
            return (Integer) argument;
        }
        if (argument instanceof Short || argument instanceof Byte) {
            return ((Number) argument).intValue();
        }
        if (argument instanceof Character) {
            return (int) (Character) argument;
        }
        return null;
    }
}
