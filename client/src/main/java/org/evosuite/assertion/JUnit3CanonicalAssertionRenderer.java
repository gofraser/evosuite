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
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.assertion;


final class JUnit3CanonicalAssertionRenderer extends AbstractCanonicalAssertionRenderer {

    static final JUnit3CanonicalAssertionRenderer INSTANCE = new JUnit3CanonicalAssertionRenderer();

    private JUnit3CanonicalAssertionRenderer() {
    }

    @Override
    protected String renderArrayEquals(String expected, String actual) {
        return "assertTrue(java.util.Arrays.equals(" + expected + ", " + actual + "));";
    }

    @Override
    protected String renderNotEquals(String expected, String actual, String delta) {
        if (delta == null) {
            return "assertFalse(java.util.Objects.equals(" + expected + ", " + actual + "));";
        }
        return "assertTrue(Double.compare((double)(" + expected + "), (double)(" + actual + ")) != 0"
                + " && !(Math.abs((double)(" + expected + ") - (double)(" + actual + ")) <= "
                + delta + "));";
    }
}
