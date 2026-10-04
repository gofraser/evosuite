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
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Observed on dom4j's {@code LazyList.LazyListIterator}: a non-static inner class of a generic
 * outer class was declared as the raw {@code LazyList.LazyListIterator}, so {@code next()}
 * returned Object and the assignment to a typed variable did not compile. The declaration
 * has to carry the owner's type arguments ({@code LazyList<Integer>.LazyListIterator}).
 */
public class InnerClassOwnerCompileTest {

    public static class Outer<E> {
        public E value;

        public class Inner implements Iterator<E> {
            public boolean hasNext() {
                return false;
            }

            public E next() {
                return value;
            }
        }
    }

    public static int use(Integer value) {
        return value == null ? 0 : value;
    }

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = InnerClassOwnerCompileTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
    }

    @Test
    public void testNextOfInnerClassOfGenericOuterCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        Type outerType = TypeUtils.parameterize(Outer.class, Integer.class);
        VariableReference outer = addOuter(tc, outerType);
        Type innerType = TypeUtils.parameterizeWithOwner(outerType, Outer.Inner.class);
        VariableReference inner = addInner(tc, innerType, outer);
        GenericMethod next = new GenericMethod(Outer.Inner.class.getMethod("next"), innerType);
        VariableReference element = tc.addStatement(
                new MethodStatement(tc, next, inner, Arrays.asList()));
        GenericMethod use = new GenericMethod(
                InnerClassOwnerCompileTest.class.getMethod("use", Integer.class),
                InnerClassOwnerCompileTest.class);
        tc.addStatement(new MethodStatement(tc, use, null, Arrays.asList(element)));
        assertCompiles(tc);
    }

    /**
     * javac types {@code outer.new Inner()} from the declared type of {@code outer}; the
     * constructor model can carry a different instantiation of the outer's type parameter
     * (dom4j's LazyList<Integer> enclosing a model owner of LazyList<String>).
     */
    @Test
    public void testInnerDeclarationFollowsTheEnclosingInstanceType() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference outer = addOuter(tc, TypeUtils.parameterize(Outer.class, String.class));
        Type modelOwner = TypeUtils.parameterizeWithOwner(
                TypeUtils.parameterize(Outer.class, Integer.class), Outer.Inner.class);
        addInner(tc, modelOwner, outer);
        assertCompiles(tc);
    }

    private static VariableReference addOuter(TestCase tc, Type outerType) throws Exception {
        return tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Outer.class.getConstructor(), outerType),
                Arrays.asList()));
    }

    private static VariableReference addInner(TestCase tc, Type innerType, VariableReference outer)
            throws Exception {
        return tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Outer.Inner.class.getConstructor(Outer.class), innerType),
                Arrays.asList(outer)));
    }

    private static void assertCompiles(TestCase tc) {
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
        String className = "InnerOwnerProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");

        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-inner-owner", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }
}
