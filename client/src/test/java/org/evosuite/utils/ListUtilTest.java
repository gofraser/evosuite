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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ListUtilTest {

    @Test
    public void testAnyEquals() {
        List<String> list = Arrays.asList("a", "b", "c");
        assertTrue(ListUtil.anyEquals(list, "b"));
        assertFalse(ListUtil.anyEquals(list, "d"));
    }

    @Test
    public void testAnyEqualsWithNulls() {
        List<String> list = Arrays.asList("a", null, "c");
        assertTrue(ListUtil.anyEquals(list, null));
        assertTrue(ListUtil.anyEquals(list, "a"));
        assertFalse(ListUtil.anyEquals(list, "b"));
    }

    @Test
    public void testAnyEqualsEmpty() {
        assertFalse(ListUtil.anyEquals(Collections.emptyList(), "a"));
    }
}
