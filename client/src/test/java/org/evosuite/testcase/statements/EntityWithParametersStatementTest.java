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
package org.evosuite.testcase.statements;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.variable.VariableReferenceImpl;
import org.evosuite.utils.ParameterizedTypeImpl;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EntityWithParametersStatementTest {

    private static class GenericOwner<E> {
        public ArrayList<E> values;

        public void accept(ArrayList<E> value) {
            // no-op
        }
    }

    @Test
    public void testReplaceParameterReferenceAllowsRawOwnerGenericListAssignment() throws Exception {
        TestCase tc = new DefaultTestCase();

        Method method = GenericOwner.class.getDeclaredMethod("accept", ArrayList.class);
        GenericMethod genericMethod = new GenericMethod(method, GenericOwner.class);

        VariableReference callee = new VariableReferenceImpl(tc, GenericOwner.class);
        VariableReference initialParameter = new VariableReferenceImpl(tc, method.getGenericParameterTypes()[0]);

        MethodStatement statement = new MethodStatement(tc, genericMethod, callee,
                new ArrayList<>(Arrays.asList(initialParameter)));

        Type arrayListOfObjectType = new ParameterizedTypeImpl(ArrayList.class,
                new Type[]{Object.class}, null);
        VariableReference replacement = new VariableReferenceImpl(tc, arrayListOfObjectType);

        statement.replaceParameterReference(replacement, 0);

        assertEquals(replacement, statement.getParameterReferences().get(0));
    }

    @Test
    public void testRawOwnerGenericFieldAcceptsConcreteArrayListArgument() throws Exception {
        Type fieldType = GenericOwner.class.getDeclaredField("values").getGenericType();
        Type arrayListOfStringType = new ParameterizedTypeImpl(ArrayList.class,
                new Type[]{String.class}, null);

        VariableReference candidate = new VariableReferenceImpl(new DefaultTestCase(), arrayListOfStringType);
        assertTrue(candidate.isAssignableTo(fieldType));
    }
}
