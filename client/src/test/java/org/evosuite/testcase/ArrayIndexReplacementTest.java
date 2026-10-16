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
import org.evosuite.testcase.statements.MethodStatement;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replacing the array of an element that a non-assignment statement uses as an argument must
 * never leave the element without a type, nor with a type that is not the type of the code
 * rendered for it.
 */
public class ArrayIndexReplacementTest {

    public static void takeString(String s) {
    }

    public static void takeObject(Object o) {
    }

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = ArrayIndexReplacementTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
    }

    private static MethodStatement call(TestCase tc, String name, Class<?> parameter,
                                        VariableReference argument) throws Exception {
        GenericMethod method = new GenericMethod(
                ArrayIndexReplacementTest.class.getMethod(name, parameter),
                ArrayIndexReplacementTest.class);
        MethodStatement statement = new MethodStatement(tc, method, null,
                Collections.singletonList(argument));
        tc.addStatement(statement);
        return statement;
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
        String className = "ElementProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : visitor.getCode().split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");
        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-element", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }

    @Test
    public void testArrayOfTooLowDimensionIsNotAcceptedForNestedElement() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference grid = tc.addStatement(new ArrayStatement(tc, String[][].class, new int[]{2, 2}));
        VariableReference flat = tc.addStatement(new ArrayStatement(tc, String[].class, 2));
        ArrayIndex cell = new ArrayIndex(tc, (ArrayReference) grid, Arrays.asList(0, 1));
        MethodStatement use = call(tc, "takeString", String.class, cell);

        tc.replace(grid, flat);

        assertNotNull(cell.getType(), "An element must never lose its type");
        assertNotNull(cell.getGenericClass());
        VariableReference argument = use.getParameterReferences().get(0);
        assertSame(grid, argument.getAdditionalVariableReference(),
                "grid[0][1] cannot be rebound to a one-dimensional array");
        assertCompiles(tc);
    }

    @Test
    public void testElementTypeFollowsReplacedArrayWhenStillFitting() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference strings = tc.addStatement(new ArrayStatement(tc, String[].class, 2));
        VariableReference others = tc.addStatement(new ArrayStatement(tc, String[].class, 2));
        ArrayIndex first = new ArrayIndex(tc, (ArrayReference) strings, 0);
        MethodStatement use = call(tc, "takeObject", Object.class, first);

        tc.replace(strings, others);

        assertSame(others, use.getParameterReferences().get(0).getAdditionalVariableReference());
        assertEquals(String.class, use.getParameterReferences().get(0).getType());
        assertCompiles(tc);
    }

    @Test
    public void testArrayOfIncompatibleComponentTypeIsNotAcceptedForArgument() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference strings = tc.addStatement(new ArrayStatement(tc, String[].class, 2));
        VariableReference numbers = tc.addStatement(new ArrayStatement(tc, Integer[].class, 2));
        ArrayIndex first = new ArrayIndex(tc, (ArrayReference) strings, 0);
        MethodStatement use = call(tc, "takeString", String.class, first);

        tc.replace(strings, numbers);

        assertSame(strings, use.getParameterReferences().get(0).getAdditionalVariableReference(),
                "takeString(Integer[] element) does not compile, the argument must stay");
        assertEquals(String.class, use.getParameterReferences().get(0).getType());
        assertCompiles(tc);
    }

    @Test
    public void testDirectReplacementByLowerDimensionDeclinesAndKeepsType() {
        TestCase tc = new DefaultTestCase();
        VariableReference grid = tc.addStatement(new ArrayStatement(tc, String[][].class, new int[]{2, 2}));
        VariableReference flat = tc.addStatement(new ArrayStatement(tc, String[].class, 2));
        ArrayIndex cell = new ArrayIndex(tc, (ArrayReference) grid, Arrays.asList(0, 1));

        assertNull(cell.withReplacement(grid, flat));

        assertSame(grid, cell.getAdditionalVariableReference());
        assertEquals(String.class, cell.getType());
    }
}
