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
package org.evosuite.testcase.factories;

import com.examples.with.different.packagename.generic.BoundedKeyQueue;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassUtils;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An entry receiver whose class has a type variable is built from the cluster's constructor of the
 * unbound owner. Its parameters must be satisfied for the instantiation that is printed, not for
 * the bare type variable: a variable of type {@code T} (a mock of {@code T}'s bound) is accepted
 * for any instantiation, and the rendered cast to it does not compile.
 */
class ObjectRuleEntryGenericReceiverTest {

    @Test
    void constructorParametersAreSatisfiedForTheBoundOwner() throws Exception {
        TestCase test = new DefaultTestCase();
        java.lang.reflect.Constructor<?> reflected = BoundedKeyQueue.class.getConstructors()[0];
        // Nothing is required, so every reference parameter becomes a typed null: no generator
        // or mock is involved, whatever state the test cluster is in.
        Set<String> required = Collections.emptySet();

        VariableReference receiver = new ObjectRuleEntryBuilder()
                .constructReceiverWithRequiredCollaborators(test,
                        new GenericConstructor(reflected, BoundedKeyQueue.class), required);

        ConstructorStatement statement =
                (ConstructorStatement) test.getStatement(receiver.getStPosition());
        Type[] declared = statement.getConstructor().getParameterTypes();
        List<VariableReference> arguments = statement.getParameterReferences();
        assertEquals(declared.length, arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            Type argumentType = arguments.get(i).getType();
            assertFalse(argumentType instanceof TypeVariable,
                    "argument " + i + " has the unbound type " + argumentType + " for " + declared[i]);
            assertTrue(GenericClassUtils.isAssignable(declared[i], argumentType),
                    "argument " + i + " of type " + argumentType + " does not fit " + declared[i]);
        }
    }
}
