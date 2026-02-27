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
package org.evosuite.graphs.cfg;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class CFGMethodAdapterTest {

    @Test
    public void testClear() throws Exception {
        ClassLoader cl = new ClassLoader() {};

        // Populate methods via reflection to be robust against visibility changes
        Field methodsField = CFGMethodAdapter.class.getDeclaredField("methods");
        methodsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<ClassLoader, Map<String, Set<String>>> methods = (Map<ClassLoader, Map<String, Set<String>>>) methodsField.get(null);

        Map<String, Set<String>> classMap = new HashMap<>();
        Set<String> methodSet = new HashSet<>();
        methodSet.add("method1");
        classMap.put("Class1", methodSet);
        methods.put(cl, classMap);

        assertTrue(CFGMethodAdapter.getNumMethods(cl) > 0, "Should have methods before clear");

        CFGMethodAdapter.clear();

        assertEquals(0, CFGMethodAdapter.getNumMethods(cl), "Should have 0 methods after clear");
        assertTrue(methods.isEmpty(), "Map should be empty");
    }
}
