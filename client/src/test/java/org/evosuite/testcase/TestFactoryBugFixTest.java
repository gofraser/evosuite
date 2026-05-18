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
package org.evosuite.testcase;

import org.evosuite.testcase.variable.VariableReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

public class TestFactoryBugFixTest {

    private TestCase testCase;

    @BeforeEach
    public void setup() {
        testCase = Mockito.mock(TestCase.class);
    }

    @Test
    public void testDependenciesSatisfiedWithSubtypes() throws Exception {
        Set<Type> dependencies = new HashSet<>();
        dependencies.add(List.class);

        VariableReference var = Mockito.mock(VariableReference.class);
        when(testCase.getObjects(List.class, 10)).thenReturn(Collections.singletonList(var));

        boolean result = invokeDependenciesSatisfied(dependencies, testCase, 10);
        
        assertTrue(result, "ArrayList should satisfy List dependency");
    }

    @Test
    public void testDependenciesSatisfiedWithExactType() throws Exception {
        Set<Type> dependencies = new HashSet<>();
        dependencies.add(List.class);

        VariableReference var = Mockito.mock(VariableReference.class);
        when(testCase.getObjects(List.class, 10)).thenReturn(Collections.singletonList(var));

        boolean result = invokeDependenciesSatisfied(dependencies, testCase, 10);

        assertTrue(result, "Exact type should satisfy dependency");
    }

    @Test
    public void testDependenciesSatisfiedRejectsWrapperForPrimitive() throws Exception {
        Set<Type> dependencies = new HashSet<>();
        dependencies.add(float.class);

        when(testCase.getObjects(float.class, 10)).thenReturn(Collections.emptyList());

        boolean result = invokeDependenciesSatisfied(dependencies, testCase, 10);

        assertFalse(result, "Wrapper Float should not satisfy primitive float dependency");
    }

    private boolean invokeDependenciesSatisfied(Set<Type> dependencies, TestCase testCase, int position) throws Exception {
        Method method = TestMutator.class.getDeclaredMethod("dependenciesSatisfied", Set.class, TestCase.class, int.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, dependencies, testCase, position);
    }
}
