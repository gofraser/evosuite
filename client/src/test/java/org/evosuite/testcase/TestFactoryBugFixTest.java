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
