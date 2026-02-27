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
package org.evosuite.junit.naming.methods;

import org.junit.Test;
import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.util.Map;
import org.evosuite.testcase.TestCase;

import org.objectweb.asm.Type;

public class CoverageGoalTestNameGenerationStrategyTest {

    @Test
    public void testGetTypeArgumentsDescription_SingleOccurrence() throws Exception {
        CoverageGoalTestNameGenerationStrategy strategy = new CoverageGoalTestNameGenerationStrategy(java.util.Collections.emptyList(), java.util.Collections.emptyList());
        
        Method method = CoverageGoalTestNameGenerationStrategy.class.getDeclaredMethod("getTypeArgumentsDescription", Type[].class);
        method.setAccessible(true);
        
        Type[] args = new Type[]{Type.getType(String.class), Type.getType(int.class)};
        String result = (String) method.invoke(strategy, new Object[]{args});
        
        assertEquals("StringAndInt", result);
    }

    @Test
    public void testFixAmbiguousTestNames_CollisionWithExisting() throws Exception {
        CoverageGoalTestNameGenerationStrategy strategy = new CoverageGoalTestNameGenerationStrategy(java.util.Collections.emptyList(), java.util.Collections.emptyList());
        
        java.lang.reflect.Field testToNameField = CoverageGoalTestNameGenerationStrategy.class.getDeclaredField("testToName");
        testToNameField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<TestCase, String> testToName = (Map<TestCase, String>) testToNameField.get(strategy);
        
        TestCase t1 = org.mockito.Mockito.mock(TestCase.class);
        TestCase t2 = org.mockito.Mockito.mock(TestCase.class);
        TestCase t3 = org.mockito.Mockito.mock(TestCase.class);
        
        testToName.put(t1, "testP");
        testToName.put(t2, "testP");
        testToName.put(t3, "testP0");
        
        Method method = CoverageGoalTestNameGenerationStrategy.class.getDeclaredMethod("fixAmbiguousTestNames");
        method.setAccessible(true);
        method.invoke(strategy);
        
        long distinctNames = testToName.values().stream().distinct().count();
        assertEquals("All names should be unique", 3, distinctNames);
        
        assertTrue(testToName.containsValue("testP2"));
        assertTrue(testToName.containsValue("testP0"));
        assertTrue(testToName.containsValue("testP1"));
    }
}
