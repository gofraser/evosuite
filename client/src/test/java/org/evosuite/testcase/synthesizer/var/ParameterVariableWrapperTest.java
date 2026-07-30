/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
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
                ParameterVariableWrapper.concreteContainerClass(java.util.Map.class));
        assertEquals(java.util.ArrayList.class,
                ParameterVariableWrapper.concreteContainerClass(Iterable.class));
        assertEquals(java.util.LinkedHashSet.class,
                ParameterVariableWrapper.concreteContainerClass(java.util.Set.class));
        assertEquals(SeveralConstructors.class,
                ParameterVariableWrapper.concreteContainerClass(SeveralConstructors.class));
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

        VariableReference key = ParameterVariableWrapper.ensureStringMapKey(test, result);

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
