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
package org.evosuite.testcase.synthesizer.var;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ParameterVariableWrapperTest {

    @Test
    public void prefersNoArgumentConstructorForCastParameter() {
        Constructor<?> constructor = ParameterVariableWrapper.selectConstructor(
                SeveralConstructors.class.getConstructors());

        assertNotNull(constructor);
        assertEquals(0, constructor.getParameterCount());
    }

    @Test
    public void resolvesAbstractContainerContractsToStableConcreteTypes() {
        assertEquals(java.util.LinkedHashMap.class,
                ContainerSetup.concreteContainerClass(java.util.Map.class));
        assertEquals(java.util.ArrayList.class,
                ContainerSetup.concreteContainerClass(Iterable.class));
        assertEquals(java.util.LinkedHashSet.class,
                ContainerSetup.concreteContainerClass(java.util.Set.class));
        assertEquals(SeveralConstructors.class,
                ContainerSetup.concreteContainerClass(SeveralConstructors.class));
    }

    @Test
    public void replacesGeneratedMapKeyWithDeterministicStringLiteral() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference map = test.addStatement(new org.evosuite.testcase.statements.ConstructorStatement(
                test, new GenericConstructor(LinkedHashMap.class.getConstructor(), LinkedHashMap.class),
                java.util.Collections.emptyList()));
        VariableReference oldKey = test.addStatement(new NullStatement(test, Object.class));
        VariableReference value = test.addStatement(new NullStatement(test, Object.class));
        Method put = LinkedHashMap.class.getMethod("put", Object.class, Object.class);
        VariableReference result = test.addStatement(new MethodStatement(test,
                new GenericMethod(put, LinkedHashMap.class), map, Arrays.asList(oldKey, value)));

        VariableReference key = ContainerSetup.ensureStringMapKey(test, result);

        assertEquals(String.class, key.getVariableClass());
        assertEquals("key", ((StringPrimitiveStatement) test.getStatement(key.getStPosition())).getValue());
        MethodStatement mutation = (MethodStatement) test.getStatement(result.getStPosition());
        assertEquals(key, mutation.getParameterReferences().get(0));
    }

    public static final class SeveralConstructors {
        public SeveralConstructors(String ignored) {
        }

        public SeveralConstructors() {
        }
    }
}
