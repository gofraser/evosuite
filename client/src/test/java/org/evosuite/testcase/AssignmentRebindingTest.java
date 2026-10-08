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
import org.evosuite.testcase.statements.ArrayStatement;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.ConstantValue;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replacing a variable that the target or the value of an assignment uses inside itself (the
 * owner of a field, the array of an element) must either rebind the whole statement to something
 * that still fits, or leave it untouched for the caller to delete.
 */
public class AssignmentRebindingTest {

    public static class Box<T> {
        public T value;
    }

    public static class Node {
        public Object next;
    }

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = AssignmentRebindingTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
    }

    private static VariableReference addBox(TestCase tc, Class<?> argument) throws Exception {
        Type type = TypeUtils.parameterize(Box.class, argument);
        return tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Box.class.getConstructor(), type), Arrays.asList()));
    }

    private static FieldReference valueOf(TestCase tc, VariableReference box, Class<?> argument)
            throws Exception {
        return new FieldReference(tc, new GenericField(Box.class.getField("value"),
                TypeUtils.parameterize(Box.class, argument)), box);
    }

    private static void assertCompiles(TestCase tc) {
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        StringBuilder source = new StringBuilder("package org.evosuite.testcase;\n");
        for (Class<?> imported : visitor.getImports()) {
            String canonical = imported.getCanonicalName();
            if (canonical != null) {
                source.append("import ").append(canonical).append(";\n");
            }
        }
        String className = "RebindingProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : visitor.getCode().split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");
        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-rebinding", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }

    @Test
    public void testFieldOwnerOfIncompatibleTypeIsRefused() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference box1 = addBox(tc, String.class);
        VariableReference box2 = addBox(tc, Integer.class);
        VariableReference str0 = tc.addStatement(new StringPrimitiveStatement(tc, "x"));
        AssignmentStatement assignment = new AssignmentStatement(tc, valueOf(tc, box1, String.class), str0);
        tc.addStatement(assignment);

        tc.replace(box1, box2);

        assertSame(box1, assignment.getReturnValue().getAdditionalVariableReference(),
                "A Box<Integer> owner must not be accepted for a String value");
        assertTrue(assignment.references(box1));
        assertEquals(String.class, assignment.getReturnValue().getType());
    }

    @Test
    public void testFieldOwnerOfCompatibleTypeIsRebound() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference box1 = addBox(tc, String.class);
        VariableReference box2 = addBox(tc, String.class);
        VariableReference str0 = tc.addStatement(new StringPrimitiveStatement(tc, "x"));
        AssignmentStatement assignment = new AssignmentStatement(tc, valueOf(tc, box1, String.class), str0);
        tc.addStatement(assignment);

        TestFactory.getInstance().replaceAndDelete(tc, box1.getStPosition(), box2);

        assertSame(box2, assignment.getReturnValue().getAdditionalVariableReference());
        assertEquals(3, tc.size());
        assertCompiles(tc);
    }

    @Test
    public void testNullOwnerLeavesNeitherSideRebound() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference node = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Node.class.getConstructor(), Node.class), Arrays.asList()));
        FieldReference next = new FieldReference(tc,
                new GenericField(Node.class.getField("next"), Node.class), node);
        AssignmentStatement assignment = new AssignmentStatement(tc, next, node);
        tc.addStatement(assignment);
        ConstantValue nullNode = new ConstantValue(tc, GenericClassFactory.get(Node.class));
        nullNode.setValue(null);

        tc.replace(node, nullNode);

        assertSame(node, assignment.getValue(),
                "The value must not become null while the target keeps dereferencing the node");
        assertSame(node, assignment.getReturnValue().getAdditionalVariableReference());
    }

    @Test
    public void testArrayOfIncompatibleComponentTypeIsRefused() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference strings = tc.addStatement(new ArrayStatement(tc, String[].class, 2));
        VariableReference numbers = tc.addStatement(new ArrayStatement(tc, Integer[].class, 2));
        VariableReference str0 = tc.addStatement(new StringPrimitiveStatement(tc, "x"));
        AssignmentStatement assignment = new AssignmentStatement(tc,
                new ArrayIndex(tc, (ArrayReference) strings, 0), str0);
        tc.addStatement(assignment);

        tc.replace(strings, numbers);

        assertSame(strings, assignment.getReturnValue().getAdditionalVariableReference());
        assertTrue(assignment.references(strings));
    }

    @Test
    public void testNonArrayReplacementLeavesNeitherSideRebound() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference array = tc.addStatement(new ArrayStatement(tc, Object[].class, 2));
        VariableReference str0 = tc.addStatement(new StringPrimitiveStatement(tc, "x"));
        AssignmentStatement assignment = new AssignmentStatement(tc,
                new ArrayIndex(tc, (ArrayReference) array, 0), array);
        tc.addStatement(assignment);

        tc.replace(array, str0);

        assertSame(array, assignment.getValue(),
                "The value must not be rebound while the element target declines the replacement");
        assertSame(array, assignment.getReturnValue().getAdditionalVariableReference());
    }

    @Test
    public void testReplaceAndDeleteRemovesTheStatementThatRefused() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference box1 = addBox(tc, String.class);
        VariableReference box2 = addBox(tc, Integer.class);
        VariableReference str0 = tc.addStatement(new StringPrimitiveStatement(tc, "x"));
        tc.addStatement(new AssignmentStatement(tc, valueOf(tc, box1, String.class), str0));

        TestFactory.getInstance().replaceAndDelete(tc, box1.getStPosition(), box2);

        assertEquals(2, tc.size(), "Only the box and the string it was assigned should remain");
        assertTrue(tc.isValid());
        assertEquals(2, tc.clone().size());
        assertCompiles(tc);
    }
}
