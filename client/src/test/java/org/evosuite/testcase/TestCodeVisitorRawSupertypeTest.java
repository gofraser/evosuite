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
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.FieldStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collections;
import java.util.Hashtable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Members of a generic class are erased when javac sees them through a raw view of that
 * class: a raw receiver, or a non-generic receiver class that extends the generic class raw
 * ({@code class ProviderList extends Hashtable}). The rendered read must then be cast back to
 * the type the test variable carries.
 */
public class TestCodeVisitorRawSupertypeTest {

    private String originalTargetClass;

    @BeforeEach
    public void setUpTargetClass() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = TestCodeVisitorRawSupertypeTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void restoreTargetClass() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
    }

    @SuppressWarnings("rawtypes")
    public static class RawHashtableSubclass extends Hashtable {
        private static final long serialVersionUID = 1L;

        public RawHashtableSubclass() {
        }
    }

    public static class Pair<T extends Comparable<T>> {
        public T key;

        public Pair(T key) {
            this.key = key;
        }
    }

    @SuppressWarnings("rawtypes")
    public static class RawPairSubclass extends Pair {
        @SuppressWarnings("unchecked")
        public RawPairSubclass(Comparable key) {
            super(key);
        }
    }

    public static class StringPairSubclass extends Pair<String> {
        public StringPairSubclass(String key) {
            super(key);
        }
    }

    public static class IntegerConsumer {
        public IntegerConsumer(Integer value) {
        }
    }

    private static VariableReference addInteger(TestCase tc, int value) throws Exception {
        VariableReference primitive = tc.addStatement(new IntPrimitiveStatement(tc, value));
        return tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Integer.class.getConstructor(int.class), Integer.class),
                Collections.singletonList(primitive)));
    }

    @Test
    public void methodReturnThroughNonGenericSubclassOfRawGenericTypeIsCast() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference table = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(RawHashtableSubclass.class.getConstructor(), RawHashtableSubclass.class),
                Collections.emptyList()));
        VariableReference key = addInteger(tc, 1);
        VariableReference value = addInteger(tc, 2);

        // The search knows the table as a Hashtable<Object, Integer>; the receiver variable is
        // the raw subclass, through which javac sees put returning Object.
        Type ownerType = TypeUtils.parameterize(Hashtable.class, Object.class, Integer.class);
        Method put = Hashtable.class.getMethod("put", Object.class, Object.class);
        VariableReference previous = tc.addStatement(new MethodStatement(tc,
                new GenericMethod(put, ownerType), table, Arrays.asList(key, value)));
        assertTrue(previous.getVariableClass().equals(Integer.class), "Setup: put must return Integer");
        tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(IntegerConsumer.class.getConstructor(Integer.class), IntegerConsumer.class),
                Collections.singletonList(previous)));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains("Integer integer2 = (Integer)testCodeVisitorRawSupertypeTest_RawHashtableSubclass0.put("),
                "A member erased by the raw supertype must be cast back:\n" + code);
    }

    @Test
    public void methodReturnThroughNonGenericSubclassOfParameterizedTypeIsNotCast() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference key = tc.addStatement(new org.evosuite.testcase.statements.StringPrimitiveStatement(tc, "k"));
        VariableReference pair = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(StringPairSubclass.class.getConstructor(String.class), StringPairSubclass.class),
                Collections.singletonList(key)));
        Method compareTo = String.class.getMethod("compareTo", String.class);
        FieldReference keyField = new FieldReference(tc,
                new GenericField(Pair.class.getField("key"), StringPairSubclass.class), pair);
        tc.addStatement(new MethodStatement(tc, new GenericMethod(compareTo, String.class),
                key, Collections.singletonList(keyField)));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains(".compareTo(testCodeVisitorRawSupertypeTest_StringPairSubclass0.key)"),
                "A parameterized supertype keeps the member's type, no cast is needed:\n" + code);
    }

    @Test
    public void fieldReadThroughRawGenericReceiverIsCast() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference integer = addInteger(tc, 7);
        VariableReference pair = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Pair.class.getConstructor(Comparable.class), Pair.class),
                Collections.singletonList(integer)));
        tc.addStatement(new FieldStatement(tc,
                new GenericField(Pair.class.getField("key"), TypeUtils.parameterize(Pair.class, Integer.class)),
                pair));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains("TestCodeVisitorRawSupertypeTest.Pair testCodeVisitorRawSupertypeTest_Pair0 = new"),
                "Setup: receiver must be raw:\n" + code);
        assertTrue(code.contains("Integer integer1 = (Integer)testCodeVisitorRawSupertypeTest_Pair0.key;"),
                "A field read through a raw receiver must be cast back:\n" + code);
    }

    @Test
    public void fieldReferenceArgumentThroughRawGenericReceiverIsCast() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference integer = addInteger(tc, 7);
        VariableReference pair = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Pair.class.getConstructor(Comparable.class), Pair.class),
                Collections.singletonList(integer)));
        FieldReference keyField = new FieldReference(tc,
                new GenericField(Pair.class.getField("key"), TypeUtils.parameterize(Pair.class, Integer.class)),
                pair);
        tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(IntegerConsumer.class.getConstructor(Integer.class), IntegerConsumer.class),
                Collections.singletonList(keyField)));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains("IntegerConsumer(((Integer) testCodeVisitorRawSupertypeTest_Pair0.key))"),
                "A field reference read through a raw receiver must be cast back:\n" + code);
    }

    @Test
    public void fieldReferenceThroughNonGenericSubclassOfRawGenericTypeIsCastOnReadOnly() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference integer = addInteger(tc, 7);
        VariableReference pair = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(RawPairSubclass.class.getConstructor(Comparable.class), RawPairSubclass.class),
                Collections.singletonList(integer)));
        FieldReference keyField = new FieldReference(tc,
                new GenericField(Pair.class.getField("key"), TypeUtils.parameterize(Pair.class, Integer.class)),
                pair);
        tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(IntegerConsumer.class.getConstructor(Integer.class), IntegerConsumer.class),
                Collections.singletonList(keyField)));
        // The same field as an assignment target must stay a plain, assignable expression.
        tc.addStatement(new AssignmentStatement(tc, keyField, integer));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains("IntegerConsumer(((Integer) testCodeVisitorRawSupertypeTest_RawPairSubclass0.key))"),
                "A field reference read through a raw supertype must be cast back:\n" + code);
        assertTrue(code.contains("\ntestCodeVisitorRawSupertypeTest_RawPairSubclass0.key = integer0;"),
                "An assignment target must not be cast:\n" + code);
        assertFalse(code.contains("((Integer) testCodeVisitorRawSupertypeTest_RawPairSubclass0.key) ="),
                "An assignment target must not be cast:\n" + code);
    }
}
