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
package org.evosuite.assertion;

import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class InspectorTraceObserverTest {

    static class DeclaredSwitchArgument {
        // intentionally empty
    }

    static class RuntimeSwitchArgument extends DeclaredSwitchArgument {
        public int getIndex() {
            return -1;
        }
    }

    public static DeclaredSwitchArgument buildDeclaredSwitchArgument() {
        return new RuntimeSwitchArgument();
    }

    private static class TestableInspectorTraceObserver extends InspectorTraceObserver {
        Class<?> observationType(VariableReference var, MethodStatement statement) throws Exception {
            Method method = InspectorTraceObserver.class.getDeclaredMethod(
                    "getObservationType", VariableReference.class, org.evosuite.testcase.statements.Statement.class);
            method.setAccessible(true);
            return (Class<?>) method.invoke(this, var, statement);
        }
    }

    @BeforeEach
    void setUp() {
        InspectorManager.resetSingleton();
    }

    @AfterEach
    void tearDown() {
        InspectorManager.resetSingleton();
    }

    @Test
    void observationTypeUsesDeclaredTypeInsteadOfRuntimeNarrowedType() throws Exception {
        MethodStatement declaringStatement = mock(MethodStatement.class);
        GenericMethod genericMethod = mock(GenericMethod.class);
        VariableReference var = mock(VariableReference.class);

        doReturn(genericMethod).when(declaringStatement).getMethod();
        doReturn(InspectorTraceObserverTest.class.getMethod("buildDeclaredSwitchArgument"))
                .when(genericMethod).getMethod();
        doReturn(RuntimeSwitchArgument.class).when(var).getVariableClass();

        TestableInspectorTraceObserver observer = new TestableInspectorTraceObserver();
        assertEquals(DeclaredSwitchArgument.class, observer.observationType(var, declaringStatement),
                "Observer should prefer the statement's declared type over a runtime-narrowed subtype");
    }
}
