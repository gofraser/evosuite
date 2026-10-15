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
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * On a {@code NotifyingListAdaptor<Integer>}: {@code remove(intArray0[0])}
 * was meant to call {@code remove(Object)} but, with a primitive int argument, javac picks
 * {@code remove(int)}, whose element result cannot be assigned to the boolean the test declared.
 */
public class PrimitiveArgumentForObjectOverloadCompileTest {

    public ArrayList<Integer> listType;

    @Test
    public void primitiveIntForObjectParameterPicksTheObjectOverload() throws Exception {
        Type integers = PrimitiveArgumentForObjectOverloadCompileTest.class.getField("listType").getGenericType();
        TestCase tc = new DefaultTestCase();
        VariableReference list = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(ArrayList.class.getConstructor(), integers), Collections.emptyList()));
        VariableReference number = tc.addStatement(new IntPrimitiveStatement(tc, 0));
        VariableReference removed = tc.addStatement(new MethodStatement(tc,
                new GenericMethod(ArrayList.class.getMethod("remove", Object.class), integers),
                list, Arrays.asList(number)));
        PrimitiveAssertion assertion = new PrimitiveAssertion();
        assertion.setSource(removed);
        assertion.setValue(false);
        tc.getStatement(removed.getStPosition()).addAssertion(assertion);

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        StringBuilder source = new StringBuilder("package org.evosuite.testcase;\n"
                + "import static org.junit.jupiter.api.Assertions.*;\n");
        for (Class<?> imported : visitor.getImports()) {
            String canonical = imported.getCanonicalName();
            if (canonical != null) {
                source.append("import ").append(canonical).append(";\n");
            }
        }
        String className = "PrimitiveObjectProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");
        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-primitive-object", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
        // remove(int) would return an Integer, the declared boolean proves remove(Object) was bound.
        assertTrue(code.contains("boolean "), code);
    }
}
