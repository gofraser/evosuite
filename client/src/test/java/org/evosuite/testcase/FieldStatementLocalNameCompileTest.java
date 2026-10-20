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
import org.evosuite.testcase.statements.FieldStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A field read that throws is rendered with a pre-declared local and a catch block. The local
 * has to be named like every other declaration, and the catch parameter must not hide a local
 * of the test (a name given to a variable of the test may well be {@code e}).
 */
public class FieldStatementLocalNameCompileTest {

    public static class Holder {
        public static String value = "x";
    }

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = FieldStatementLocalNameCompileTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
    }

    @Test
    public void catchParameterDoesNotHideALocalNamedE() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference named = tc.addStatement(new IntPrimitiveStatement(tc, 1));
        FieldStatement read = new FieldStatement(tc,
                new GenericField(Holder.class.getField("value"), Holder.class), null);
        tc.addStatement(read);
        TestPresentationMetadata.getOrCreate(tc).putVariableName(named.getStPosition(), "e");

        TestCodeVisitor visitor = new TestCodeVisitor();
        visitor.setException(read, new IllegalStateException("x"));
        tc.accept(visitor);
        String code = visitor.getCode();

        StringBuilder source = new StringBuilder("package org.evosuite.testcase;\n");
        for (Class<?> imported : visitor.getImports()) {
            String canonical = imported.getCanonicalName();
            if (canonical != null) {
                source.append("import ").append(canonical).append(";\n");
            }
        }
        String className = "FieldLocalProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");

        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-field-local", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }
}
