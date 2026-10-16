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
import org.evosuite.testcase.fm.MethodDescriptor;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.CharPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mocked method returning a type variable may have been stubbed during the search with a
 * value of an unrelated type: the erased method returns Object, so Mockito accepts it. The
 * rendered doReturn must not cast that value to the resolved return type, which javac rejects.
 */
public class TestCodeVisitorMockReturnCastTest {

    private String originalTargetClass;

    @BeforeEach
    public void setUpTargetClass() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = TestCodeVisitorMockReturnCastTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void restoreTargetClass() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
    }

    public interface Accessor<K, V> {
        V get(K key);
    }

    @Test
    public void inconvertibleStubValueIsNotCastToResolvedReturnType() throws Exception {
        TestCase tc = new DefaultTestCase();
        Type accessorType = TypeUtils.parameterize(Accessor.class, Long.class, Integer.class);
        FunctionalMockStatement mockStatement = new FunctionalMockStatement(
                tc, accessorType, GenericClassFactory.get(accessorType));
        VariableReference character = tc.addStatement(new CharPrimitiveStatement(tc, 'x'));
        VariableReference string = tc.addStatement(new StringPrimitiveStatement(tc, "s"));
        tc.addStatement(mockStatement, 0);

        MethodDescriptor descriptor = new MethodDescriptor(
                Accessor.class.getMethod("get", Object.class), GenericClassFactory.get(accessorType));
        descriptor.increaseCounter();
        descriptor.increaseCounter();
        mockStatement.addMethodStubbing(descriptor, Arrays.asList(character, string));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains("doReturn("), "Expected a doReturn stub:\n" + code);
        assertFalse(code.contains("(Integer) 'x'") || code.contains("(Integer) char0"),
                "A char cannot be cast to Integer:\n" + code);
        assertFalse(code.contains("(Integer) \"s\"") || code.contains("(Integer) string0"),
                "A String cannot be cast to Integer:\n" + code);
    }
}
