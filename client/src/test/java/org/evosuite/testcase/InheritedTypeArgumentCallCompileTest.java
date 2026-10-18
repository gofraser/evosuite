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
import org.evosuite.testcase.statements.FieldStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code DefaultContig.Builder} (a static nested class of the generic
 * {@code DefaultContig}): the non-generic builder inherits
 * {@code addAllReads(Iterable<P>)} from {@code AbstractContigBuilder<AssembledRead, ...>}, and
 * an {@code ArrayList<AceAssembledReadAdapter>} was passed to it. The call is checked against
 * the receiver's (inherited) parameterization, {@code Iterable<AssembledRead>}, which is
 * invariant in its element type.
 *
 * <p>The other cases pin how members inherited through such a class, and such a class as a type
 * argument, are rendered.
 */
public class InheritedTypeArgumentCallCompileTest {

    public static class Base<P extends CharSequence> {
        public void addAll(Iterable<P> items) {
        }
    }

    public static class Value<V> {
        public V value;

        public V get() {
            return value;
        }
    }

    public static class Holder<T> {
        // A static nested class of a generic outer: gentyref treats it as raw.
        public static class Derived extends Base<CharSequence> {
        }

        public static class Named extends Value<String> {
        }

        public static class Key implements Comparable<Key> {
            @Override
            public int compareTo(Key other) {
                return 0;
            }
        }
    }

    @SuppressWarnings("rawtypes")
    public static class RawKey implements Comparable {
        @Override
        public int compareTo(Object other) {
            return 0;
        }
    }

    public static class BoundedBox<E extends Comparable<E>> {
        public List<E> values() {
            return new ArrayList<>();
        }

        public E first() {
            return null;
        }
    }

    public static class StringConsumer {
        public StringConsumer(String value) {
        }
    }

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = InheritedTypeArgumentCallCompileTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
    }

    @Test
    public void testIterableOfSubtypeToInheritedInvariantParameterCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference derived = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Holder.Derived.class.getConstructor(), Holder.Derived.class),
                Arrays.asList()));
        Type listType = TypeUtils.parameterize(ArrayList.class, Integer.class);
        VariableReference list = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(ArrayList.class.getConstructor(), listType),
                Arrays.asList()));
        // The method model as the search sees it: owner Derived, parameter Iterable<P> resolved through the superclass.
        GenericMethod addAll = new GenericMethod(Base.class.getMethod("addAll", Iterable.class),
                Holder.Derived.class);
        tc.addStatement(new MethodStatement(tc, addAll, derived, Arrays.asList(list)));

        assertCompiles(tc);
    }

    private static VariableReference addNamed(TestCase tc) throws Exception {
        return tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Holder.Named.class.getConstructor(), Holder.Named.class),
                Collections.emptyList()));
    }

    private static void addStringConsumer(TestCase tc, VariableReference value) throws Exception {
        tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(StringConsumer.class.getConstructor(String.class), StringConsumer.class),
                Collections.singletonList(value)));
    }

    @Test
    public void inheritedMethodReturnThroughStaticNestedReceiverCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference named = addNamed(tc);
        VariableReference value = tc.addStatement(new MethodStatement(tc,
                new GenericMethod(Value.class.getMethod("get"), Holder.Named.class), named,
                Collections.emptyList()));
        addStringConsumer(tc, value);

        // javac sees the members Named inherits from Value<String> unerased: no cast.
        String code = assertCompiles(tc);
        assertTrue(code.contains("String string0 = inheritedTypeArgumentCallCompileTest_Holder_Named0.get();"),
                code);
    }

    @Test
    public void inheritedFieldReadThroughStaticNestedReceiverCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference named = addNamed(tc);
        VariableReference value = tc.addStatement(new FieldStatement(tc,
                new GenericField(Value.class.getField("value"), Holder.Named.class), named));
        addStringConsumer(tc, value);

        String code = assertCompiles(tc);
        assertTrue(code.contains("String string0 = inheritedTypeArgumentCallCompileTest_Holder_Named0.value;"),
                code);
    }

    @Test
    public void inheritedFieldReferenceThroughStaticNestedReceiverCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference named = addNamed(tc);
        FieldReference value = new FieldReference(tc,
                new GenericField(Value.class.getField("value"), Holder.Named.class), named);
        addStringConsumer(tc, value);

        String code = assertCompiles(tc);
        assertTrue(code.contains(
                "StringConsumer(inheritedTypeArgumentCallCompileTest_Holder_Named0.value);"), code);
    }

    @Test
    public void staticNestedTypeArgumentOfParameterizedBoundCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        Type boxType = TypeUtils.parameterize(BoundedBox.class, Holder.Key.class);
        VariableReference box = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(BoundedBox.class.getConstructor(), boxType),
                Collections.emptyList()));
        VariableReference values = tc.addStatement(new MethodStatement(tc,
                new GenericMethod(BoundedBox.class.getMethod("values"), boxType), box,
                Collections.emptyList()));
        tc.addStatement(new MethodStatement(tc,
                new GenericMethod(List.class.getMethod("size"), List.class), values,
                Collections.emptyList()));

        // Holder.Key implements Comparable<Key>, so it is within the bound of E.
        String code = assertCompiles(tc);
        String boxOfKey = "InheritedTypeArgumentCallCompileTest.BoundedBox"
                + "<InheritedTypeArgumentCallCompileTest.Holder.Key>";
        assertTrue(code.contains(boxOfKey + " inheritedTypeArgumentCallCompileTest_BoundedBox0 = new "
                + boxOfKey + "();"), code);
    }

    @Test
    public void elementOfStaticNestedTypeArgumentOfParameterizedBoundCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        Type boxType = TypeUtils.parameterize(BoundedBox.class, Holder.Key.class);
        VariableReference box = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(BoundedBox.class.getConstructor(), boxType),
                Collections.emptyList()));
        VariableReference key = tc.addStatement(new MethodStatement(tc,
                new GenericMethod(BoundedBox.class.getMethod("first"), boxType), box,
                Collections.emptyList()));
        tc.addStatement(new MethodStatement(tc,
                new GenericMethod(Holder.Key.class.getMethod("compareTo", Holder.Key.class), Holder.Key.class),
                key, Collections.singletonList(key)));

        assertCompiles(tc);
    }

    @Test
    public void elementOfRawTypeArgumentOutsideTheBoundCompiles() throws Exception {
        // RawKey implements the raw Comparable, so it is outside the bound of E and the box
        // is declared raw: javac sees first() return Comparable.
        TestCase tc = new DefaultTestCase();
        Type boxType = TypeUtils.parameterize(BoundedBox.class, RawKey.class);
        VariableReference box = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(BoundedBox.class.getConstructor(), boxType),
                Collections.emptyList()));
        VariableReference key = tc.addStatement(new MethodStatement(tc,
                new GenericMethod(BoundedBox.class.getMethod("first"), boxType), box,
                Collections.emptyList()));
        tc.addStatement(new MethodStatement(tc,
                new GenericMethod(RawKey.class.getMethod("compareTo", Object.class), RawKey.class),
                key, Collections.singletonList(key)));

        assertCompiles(tc);
    }

    private static String assertCompiles(TestCase tc) throws Exception {
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        StringBuilder source = new StringBuilder("package org.evosuite.testcase;\n");
        for (Class<?> imported : visitor.getImports()) {
            String canonical = imported.getCanonicalName();
            if (canonical != null) {
                source.append("import ").append(canonical).append(";\n");
            }
        }
        String className = "InheritedArgProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");

        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-inherited-arg", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
        return code;
    }
}
