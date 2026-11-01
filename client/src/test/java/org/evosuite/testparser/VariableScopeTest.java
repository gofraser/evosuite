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
