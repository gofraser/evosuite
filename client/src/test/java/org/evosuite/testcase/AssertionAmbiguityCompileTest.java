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

import org.evosuite.assertion.PrimitiveAssertion;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A variable whose class is the primitive {@code long} but which is declared by an
 * {@code Integer} constructor is rendered as {@code Integer long0 = new Integer(..)}. A
 * {@link PrimitiveAssertion} with an Integer value on it must not render as the bare
 * {@code assertEquals(int, Integer)}, which javac rejects as ambiguous between
 * {@code assertEquals(long, long)} and {@code assertEquals(Object, Object)}.
 */
public class AssertionAmbiguityCompileTest {

    private static void assertRendersAndCompiles(Class<?> variableClass, Object value) throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference arg = tc.addStatement(PrimitiveStatement.getPrimitiveStatement(tc, int.class));
        tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Integer.class.getConstructor(int.class), Integer.class),
                Arrays.asList(arg)));
        VariableReference var = tc.getStatement(1).getReturnValue();
        var.setType(variableClass);
        PrimitiveAssertion assertion = new PrimitiveAssertion();
        assertion.setSource(var);
        assertion.setValue(value);
        tc.getStatement(1).addAssertion(assertion);

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        StringBuilder source = new StringBuilder();
        source.append("package org.evosuite.testcase;\n");
        source.append("import static org.junit.Assert.*;\n");
        String className = "AssertionAmbiguityProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : visitor.getCode().split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");
        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-assertion-ambiguity-compile", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }

    @Test
    public void testIntegerValueOnLongVariableDeclaredAsInteger() throws Exception {
        assertRendersAndCompiles(long.class, Integer.valueOf(-64860000));
    }

    @Test
    public void testIntegerValueOnIntVariableDeclaredAsInteger() throws Exception {
        // D4J Collections 12: Integer int0 = new Integer(2); assertEquals(2, int0);
        assertRendersAndCompiles(int.class, Integer.valueOf(2));
    }
}
