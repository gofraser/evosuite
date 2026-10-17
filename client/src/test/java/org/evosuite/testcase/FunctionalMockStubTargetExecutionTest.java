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
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.fm.MethodDescriptor;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The search stubs a mock with the method and matchers that the generated test names, so that
 * the stub matches the same calls in both.
 */
public class FunctionalMockStubTargetExecutionTest {

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        Properties.getInstance().resetToDefaults();
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = FunctionalMockStubTargetExecutionTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
        Properties.getInstance().resetToDefaults();
    }

    public interface Typed<T> {
        String put(T value);
    }

    public static class User {
        public static String putNull(Typed<String> typed) {
            return typed.put(null);
        }

        public static String putValue(Typed<String> typed) {
            return typed.put("x");
        }
    }

    /**
     * The stub was recorded on the raw type, whose put(T) takes an Object, but the mock is a
     * {@code Typed<String>}: the generated test stubs put(anyString()), which does not match a
     * call with null, and the search has to stub the same.
     */
    @Test
    public void aStubMatchesTheCallsTheGeneratedTestMatches() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference value = tc.addStatement(new StringPrimitiveStatement(tc, "stubbed"));
        Type type = TypeUtils.parameterize(Typed.class, String.class);
        FunctionalMockStatement mock = new FunctionalMockStatement(tc, type, GenericClassFactory.get(type));
        VariableReference ref = tc.addStatement(mock);
        MethodDescriptor descriptor = new MethodDescriptor(Typed.class.getMethod("put", Object.class),
                GenericClassFactory.get(Typed.class));
        descriptor.increaseCounter();
        mock.addMethodStubbing(descriptor, Collections.singletonList(value));
        VariableReference withNull = tc.addStatement(new MethodStatement(tc,
                new GenericMethod(User.class.getMethod("putNull", Typed.class), User.class), null,
                Arrays.asList(ref)));
        VariableReference withValue = tc.addStatement(new MethodStatement(tc,
                new GenericMethod(User.class.getMethod("putValue", Typed.class), User.class), null,
                Arrays.asList(ref)));

        assertTrue(tc.toCode().contains(".put(anyString());"), tc.toCode());

        Scope scope = new Scope();
        for (Statement statement : tc) {
            assertNull(statement.execute(scope, System.out));
        }
        assertNull(withNull.getObject(scope), "anyString() does not match null");
        assertEquals("stubbed", withValue.getObject(scope));
    }

    public static class Position implements Comparable<Position> {
        @Override
        public int compareTo(Position other) {
            return 0;
        }
    }

    public static class Sorter {
        @SuppressWarnings({"rawtypes", "unchecked"})
        public static int compare(Position position) {
            Comparable comparable = position;
            return comparable.compareTo(new Position());
        }
    }

    /** A call through the bridge reaches the stub of the method the bridge forwards to. */
    @Test
    public void aStubOfAMethodReachedThroughABridgeAnswersCallsThroughTheBridge() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference seven = tc.addStatement(
                new org.evosuite.testcase.statements.numeric.IntPrimitiveStatement(tc, 7));
        FunctionalMockStatement mock = new FunctionalMockStatement(tc, Position.class,
                GenericClassFactory.get(Position.class));
        VariableReference ref = tc.addStatement(mock);
        MethodDescriptor descriptor = new MethodDescriptor(Comparable.class.getMethod("compareTo", Object.class),
                GenericClassFactory.get(Position.class));
        descriptor.increaseCounter();
        mock.addMethodStubbing(descriptor, Collections.singletonList(seven));
        VariableReference result = tc.addStatement(new MethodStatement(tc,
                new GenericMethod(Sorter.class.getMethod("compare", Position.class), Sorter.class), null,
                Arrays.asList(ref)));

        assertTrue(tc.toCode().contains(".compareTo(nullable("), tc.toCode());

        Scope scope = new Scope();
        for (Statement statement : tc) {
            assertNull(statement.execute(scope, System.out));
        }
        assertEquals(7, result.getObject(scope));
    }
}
