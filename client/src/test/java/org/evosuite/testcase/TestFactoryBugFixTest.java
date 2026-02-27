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
package org.evosuite.testcase;

import org.evosuite.testcase.variable.VariableReference;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

public class TestFactoryBugFixTest {

    @Test
    public void testDependenciesSatisfiedWithSubtypes() throws Exception {
        Set<Type> dependencies = new HashSet<>();
        dependencies.add(List.class);

        VariableReference var = Mockito.mock(VariableReference.class);
        when(var.getType()).thenReturn(ArrayList.class);
        // Stub isAssignableTo to return true for List.class
        when(var.isAssignableTo(List.class)).thenReturn(true);
        
        List<VariableReference> objects = Collections.singletonList(var);

        boolean result = invokeDependenciesSatisfied(dependencies, objects);
        
        assertTrue(result, "ArrayList should satisfy List dependency");
    }

    @Test
    public void testDependenciesSatisfiedWithExactType() throws Exception {
        Set<Type> dependencies = new HashSet<>();
        dependencies.add(List.class);

        VariableReference var = Mockito.mock(VariableReference.class);
        when(var.getType()).thenReturn(List.class);
        when(var.isAssignableTo(List.class)).thenReturn(true);
        
        List<VariableReference> objects = Collections.singletonList(var);

        boolean result = invokeDependenciesSatisfied(dependencies, objects);
        
        assertTrue(result, "Exact type should satisfy dependency");
    }

    private boolean invokeDependenciesSatisfied(Set<Type> dependencies, List<VariableReference> objects) throws Exception {
        Method method = TestMutator.class.getDeclaredMethod("dependenciesSatisfied", Set.class, List.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, dependencies, objects);
    }
}
