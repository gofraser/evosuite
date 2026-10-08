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

import org.evosuite.Properties;
import org.evosuite.testcase.statements.ArrayStatement;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replacing a variable in a test changes all of its uses or none of them, also where the uses
 * share an element reference, and each statement decides from what it holds before anything is
 * changed.
 */
public class ReplacementAllOrNothingTest {

    public static Integer makeInteger() {
        return 1;
    }

    public static Integer[] makeIntegers() {
        return new Integer[]{1};
    }

    public static Number[] makeNumbers() {
        return new Number[]{1};
    }

    public static void takeInteger(Integer i) {
    }

    public static void takeObject(Object o) {
    }

    public static void takeString(String s) {
    }

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = ReplacementAllOrNothingTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
    }

    private static GenericMethod method(String name, Class<?>... parameters) throws Exception {
        return new GenericMethod(ReplacementAllOrNothingTest.class.getMethod(name, parameters),
                ReplacementAllOrNothingTest.class);
    }

    private static MethodStatement call(TestCase tc, String name, Class<?> parameter,
                                        VariableReference argument) throws Exception {
        MethodStatement statement = new MethodStatement(tc, method(name, parameter), null,
                Collections.singletonList(argument));
        tc.addStatement(statement);
        return statement;
    }

    private static VariableReference make(TestCase tc, String name) throws Exception {
        return tc.addStatement(new MethodStatement(tc, method(name), null,
                Collections.emptyList()));
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
        String className = "ReplacementProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : visitor.getCode().split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");
        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-replacement", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }

    private static boolean anyStatementReferences(TestCase tc, VariableReference var) {
        for (Statement statement : tc) {
            if (statement.getReturnValue() != var && statement.references(var)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void testSharedElementIsNotRetypedForOneStatementOnly() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference integers = tc.addStatement(new ArrayStatement(tc, Integer[].class, 1));
        VariableReference numbers = tc.addStatement(new ArrayStatement(tc, Number[].class, 1));
        VariableReference value = make(tc, "makeInteger");
        // One element reference, shared by the assignment that writes it and the call that
        // reads it, as the test factory builds them.
        ArrayIndex element = new ArrayIndex(tc, (ArrayReference) integers, 0);
        tc.addStatement(new AssignmentStatement(tc, element, value));
        MethodStatement use = call(tc, "takeInteger", Integer.class, element);

        tc.replace(integers, numbers);

        VariableReference argument = use.getParameterReferences().get(0);
        assertEquals(Integer.class, argument.getType(),
                "takeInteger(numberArray0[0]) does not compile");
        assertSame(integers, argument.getAdditionalVariableReference());
        assertCompiles(tc);
    }

    @Test
    public void testReplacementByFewerDimensionsChangesAllUsesOrNone() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference grid = tc.addStatement(new ArrayStatement(tc, String[][].class, new int[]{1, 2}));
        VariableReference flat = tc.addStatement(new ArrayStatement(tc, String[].class, 2));
        ArrayIndex cell = new ArrayIndex(tc, (ArrayReference) grid, Arrays.asList(0, 1));
        call(tc, "takeObject", Object.class, grid);
        call(tc, "takeString", String.class, cell);
        String before = tc.toCode();

        tc.replace(grid, flat);

        if (anyStatementReferences(tc, grid)) {
            assertEquals(before, tc.toCode(),
                    "A use of grid was replaced while another one, grid[0][1], was kept");
        } else {
            tc.remove(grid.getStPosition());
            assertTrue(tc.isValid(), tc.toCode());
            tc.clone();
            assertCompiles(tc);
        }

        TestFactory.getInstance().replaceAndDelete(tc, grid.getStPosition(), flat);

        assertTrue(tc.isValid(), tc.toCode());
        assertFalse(anyStatementReferences(tc, grid), tc.toCode());
        assertEquals(tc.size(), tc.clone().size());
        assertCompiles(tc);
    }

    @Test
    public void testRefusedReplacementKeepsTheDefinitionOfTheReplacedVariable() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference integers = make(tc, "makeIntegers");
        VariableReference numbers = make(tc, "makeNumbers");
        Statement definition = tc.getStatement(integers.getStPosition());
        call(tc, "takeInteger", Integer.class, new ArrayIndex(tc, (ArrayReference) integers, 0));
        call(tc, "takeObject", Object.class, numbers);

        assertFalse(tc.replace(integers, numbers),
                "takeInteger(numberArray0[0]) refuses, so nothing may change");

        assertSame(integers, definition.getReturnValue(),
                "The statement defining integerArray0 must not be left defining numberArray0");
        assertTrue(tc.isValid(), tc.toCode());

        TestFactory.getInstance().replaceAndDelete(tc, integers.getStPosition(), numbers);

        List<String> remaining = Arrays.asList(tc.toCode().split("\\R"));
        assertEquals(2, tc.size(), tc.toCode());
        assertTrue(remaining.stream().anyMatch(line -> line.contains("makeNumbers()")), tc.toCode());
        assertTrue(remaining.stream().anyMatch(line -> line.contains("takeObject(")), tc.toCode());
        assertTrue(tc.isValid(), tc.toCode());
        assertCompiles(tc);
    }

    @Test
    public void testElementCalleeOfIncompatibleTypeIsRefused() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference integers = tc.addStatement(new ArrayStatement(tc, Integer[].class, 1));
        VariableReference strings = tc.addStatement(new ArrayStatement(tc, String[].class, 1));
        ArrayIndex element = new ArrayIndex(tc, (ArrayReference) integers, 0);
        MethodStatement call = new MethodStatement(tc,
                new GenericMethod(Integer.class.getMethod("intValue"), Integer.class), element,
                Collections.emptyList());
        tc.addStatement(call);

        assertFalse(call.acceptsReplacement(integers, strings));
        call.replace(integers, strings);
        tc.replace(integers, strings);

        assertSame(integers, call.getCallee().getAdditionalVariableReference(),
                "stringArray0[0].intValue() does not compile");
        assertEquals(Integer.class, call.getCallee().getType());
        assertCompiles(tc);
    }
}
