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
package org.evosuite.testcase.statements;

import org.evosuite.symbolic.TestCaseBuilder;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

public class MethodStatementCompatibilityTest {

    private static class DeclaringType {
        public void ping() {
            // no-op
        }
    }

    private static class UnrelatedType {
        // no-op
    }

    /**
     * The shape of a class that can only be obtained through a supertype-typed factory or field,
     * such as {@code public static final OAIVerb IDENTIFY = new Identify()}.
     */
    public static class Base {
        public static Base makeSub() {
            return new Sub();
        }

        public static Base makeSibling() {
            return new Sibling();
        }
    }

    public static class Sub extends Base {
        public String greet() {
            return "sub";
        }
    }

    public static class Sibling extends Base {
        // no-op
    }

    private static class PrimitiveTarget {
        private final boolean value;

        private PrimitiveTarget(boolean value) {
            this.value = value;
        }

        private boolean negate(boolean input) {
            return value && !input;
        }
    }

    @Test
    public void testIncompatibleCalleeRejectedEvenWithBroadOwnerType() throws Exception {
        Method method = DeclaringType.class.getDeclaredMethod("ping");
        GenericMethod genericMethod = new GenericMethod(method, Object.class);

        Assertions.assertFalse(MethodStatement.isCompatibleCalleeType(genericMethod, UnrelatedType.class));
    }

    @Test
    public void testSupertypeCalleeAcceptedBecauseGeneratedCodeDowncasts() throws Exception {
        Method method = Sub.class.getDeclaredMethod("greet");
        GenericMethod genericMethod = new GenericMethod(method, Sub.class);

        Assertions.assertTrue(MethodStatement.isCompatibleCalleeType(genericMethod, Base.class));
        Assertions.assertFalse(MethodStatement.isCompatibleCalleeType(genericMethod, UnrelatedType.class));
    }

    @Test
    public void testDowncastThatDoesNotHoldIsReportedAsClassCastException() throws Exception {
        TestCaseBuilder builder = new TestCaseBuilder();
        // Statically a Base, so calling Sub.greet() on it is allowed and rendered as a downcast,
        // but the object behind it is a Sibling and the downcast fails.
        VariableReference callee = builder.appendMethod(null, Base.class.getMethod("makeSibling"));
        builder.appendMethod(callee, Sub.class.getDeclaredMethod("greet"));

        Scope scope = new Scope();
        Throwable thrown = null;
        for (Statement statement : builder.getDefaultTestCase()) {
            thrown = statement.execute(scope, System.out);
        }

        Assertions.assertNotNull(thrown);
        Assertions.assertInstanceOf(ClassCastException.class, thrown.getCause());
    }

    @Test
    public void testDowncastThatHoldsExecutesTheMethod() throws Exception {
        TestCaseBuilder builder = new TestCaseBuilder();
        VariableReference callee = builder.appendMethod(null, Base.class.getMethod("makeSub"));
        VariableReference ret = builder.appendMethod(callee, Sub.class.getDeclaredMethod("greet"));

        Scope scope = new Scope();
        for (Statement statement : builder.getDefaultTestCase()) {
            Assertions.assertNull(statement.execute(scope, System.out));
        }

        Assertions.assertEquals("sub", ret.getObject(scope));
    }

    @Test
    public void testPrimitiveBooleanParameterAcceptedAtExecution() throws Exception {
        TestCaseBuilder builder = new TestCaseBuilder();
        VariableReference arg = builder.appendBooleanPrimitive(true);
        VariableReference target = builder.appendConstructor(
                PrimitiveTarget.class.getDeclaredConstructor(boolean.class), arg);
        VariableReference ret = builder.appendMethod(
                target, PrimitiveTarget.class.getDeclaredMethod("negate", boolean.class), arg);

        Scope scope = new Scope();
        for (Statement statement : builder.getDefaultTestCase()) {
            Throwable thrown = statement.execute(scope, System.out);
            Assertions.assertNull(thrown);
        }

        Assertions.assertEquals(Boolean.FALSE, ret.getObject(scope));
    }
}
