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

    @Test
    void defaultAwtComponentNameIsNotAsserted() throws Exception {
        org.evosuite.symbolic.TestCaseBuilder builder = new org.evosuite.symbolic.TestCaseBuilder();
        VariableReference panel0 = builder.appendConstructor(java.awt.Panel.class.getConstructor());
        VariableReference name0 = builder.appendMethod(panel0, java.awt.Component.class.getMethod("getName"));
        org.evosuite.testcase.DefaultTestCase test = builder.getDefaultTestCase();

        org.evosuite.Properties.TARGET_CLASS = "java.awt.Panel";
        new UnitAssertionGenerator().addAssertions(test);

        for (int i = 0; i < test.size(); i++) {
            for (Assertion a : test.getStatement(i).getAssertions()) {
                String code = a.getCode();
                assertFalse(code.matches(".*\\\"panel\\d+\\\".*"), code);
            }
        }
        assertNotNull(name0);
    }

    /** Not a component itself: hands on the name and toString() of the panel it wraps. */
    public static class PanelHelper {
        private final java.awt.Panel panel;

        public PanelHelper(java.awt.Panel panel) {
            this.panel = panel;
        }

        public String getName() {
            return panel.getName();
        }

        @Override
        public String toString() {
            return panel.toString();
        }
    }

    @Test
    void defaultAwtNameRelayedByANonComponentIsNotAsserted() throws Exception {
        org.evosuite.symbolic.TestCaseBuilder builder = new org.evosuite.symbolic.TestCaseBuilder();
        VariableReference panel0 = builder.appendConstructor(java.awt.Panel.class.getConstructor());
        VariableReference helper0 = builder.appendConstructor(
                PanelHelper.class.getConstructor(java.awt.Panel.class), panel0);
        builder.appendMethod(helper0, PanelHelper.class.getMethod("getName"));
        builder.appendMethod(helper0, PanelHelper.class.getMethod("toString"));
        org.evosuite.testcase.DefaultTestCase test = builder.getDefaultTestCase();

        org.evosuite.Properties.TARGET_CLASS = PanelHelper.class.getName();
        new UnitAssertionGenerator().addAssertions(test);

        for (int i = 0; i < test.size(); i++) {
            for (Assertion a : test.getStatement(i).getAssertions()) {
                String code = a.getCode();
                assertFalse(code.matches("(?s).*panel\\d+.*"), code);
            }
        }
    }
}
