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
package org.evosuite.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ArrayUtilTest {

    @Test
    public void testContains() {
        Integer[] array = new Integer[] {1, 2, 3};
        assertTrue(ArrayUtil.contains(array, 1));
        assertFalse(ArrayUtil.contains(array, 4));
    }

    @Test
    public void testContainsWithNulls() {
        Integer[] array = new Integer[] {1, null, 3};
        assertTrue(ArrayUtil.contains(array, null));
        assertTrue(ArrayUtil.contains(array, 1));
    }

    @Test
    public void testContainsStrictType() {
        // This test asserts the DESIRED behavior (strict checking).
        // It currently fails because of the bug/loose check in ArrayUtil.contains.
        Integer[] array = new Integer[] {1, 2, 3};
        assertFalse(ArrayUtil.contains(array, "1"), "Should not match String '1' to Integer 1");
    }
}
