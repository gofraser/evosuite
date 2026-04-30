/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.testparser;

import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class VariableScopeTest {

    @Test
    void findGenericTypeForRefReturnsTrackedGenericType() {
        VariableScope scope = new VariableScope();
        VariableReference ref = mock(VariableReference.class);
        GenericClass<?> genericType = GenericClassFactory.get(String.class);

        scope.register("value", ref, genericType);

        assertSame(genericType, scope.findGenericTypeForRef(ref));
    }

    @Test
    void findGenericTypeForRefTracksReboundReferenceForSameName() {
        VariableScope scope = new VariableScope();
        VariableReference originalRef = mock(VariableReference.class);
        VariableReference reboundRef = mock(VariableReference.class);
        GenericClass<?> genericType = GenericClassFactory.get(String.class);

        scope.register("value", originalRef, genericType);
        scope.register("value", reboundRef, null);

        assertNull(scope.findGenericTypeForRef(originalRef));
        assertSame(genericType, scope.findGenericTypeForRef(reboundRef));
    }
}
