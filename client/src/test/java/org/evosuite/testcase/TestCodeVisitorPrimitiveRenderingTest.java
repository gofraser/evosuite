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
package org.evosuite.testcase;

import org.apache.commons.lang3.reflect.TypeUtils;
import org.evosuite.Properties;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.variable.ConstantValue;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestCodeVisitorPrimitiveRenderingTest {

    private String originalTargetClass;

    @BeforeEach
    public void setUpTargetClass() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = TestCodeVisitorPrimitiveRenderingTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void restoreTargetClass() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
    }

    public static <E> boolean removeElement(List<E> list, E element) {
        return list.remove(element);
    }

    private static String renderRemoveElement(Class<?> elementType, Object constant) throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference list = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(ArrayList.class.getConstructor(),
                        TypeUtils.parameterize(ArrayList.class, elementType)),
                Collections.emptyList()));
        ConstantValue element = new ConstantValue(tc, GenericClassFactory.get(constant.getClass() == Short.class
                ? short.class : byte.class), constant);
        Method remove = TestCodeVisitorPrimitiveRenderingTest.class.getMethod(
                "removeElement", List.class, Object.class);
        // E resolved to the wrapper, as the search instantiates removeElement for a List<Short>.
        GenericMethod genericRemove = new GenericMethod(remove, TestCodeVisitorPrimitiveRenderingTest.class);
        genericRemove.setTypeParameters(Collections.singletonList(GenericClassFactory.get(elementType)));
        tc.addStatement(new MethodStatement(tc, genericRemove, null, Arrays.asList(list, element)));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        return visitor.getCode();
    }

    @Test
    public void shortConstantPassedToGenericParameterKeepsItsPrimitiveCast() throws Exception {
        String code = renderRemoveElement(Short.class, (short) -1);

        assertTrue(code.contains("(short) (-1))"),
                "A short constant boxed by a reference cast must stay a short:\n" + code);
        assertFalse(code.contains("(Short)  (-1)") || code.contains("(Short) (-1)"),
                "(Short) (-1) casts an int, which javac rejects:\n" + code);
    }

    @Test
    public void byteConstantPassedToGenericParameterKeepsItsPrimitiveCast() throws Exception {
        String code = renderRemoveElement(Byte.class, (byte) 3);

        assertTrue(code.contains("(byte)3)") || code.contains("(byte) 3)"),
                "A byte constant boxed by a reference cast must stay a byte:\n" + code);
    }
}
