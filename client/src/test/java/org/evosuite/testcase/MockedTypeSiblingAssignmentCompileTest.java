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
import org.evosuite.runtime.mock.java.io.MockFile;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A variable declared as {@code MockFile} (the mock that
 * stands for {@code java.io.File}) was later assigned a {@code de.schlichtherle.io.File}, a
 * sibling of MockFile below java.io.File, without a cast.
 */
public class MockedTypeSiblingAssignmentCompileTest {

    public static class Sibling extends java.io.File {
        private static final long serialVersionUID = 1L;

        public Sibling(String name) {
            super(name);
        }
    }

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = MockedTypeSiblingAssignmentCompileTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
    }

    @Test
    public void siblingAssignedToMockedVariableCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference name = tc.addStatement(new StringPrimitiveStatement(tc, "f"));
        VariableReference mock = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(MockFile.class.getConstructor(String.class), MockFile.class),
                Arrays.asList(name)));
        // The variable is a java.io.File; only the constructor behind it is the mock's.
        mock.setType(java.io.File.class);
        VariableReference sibling = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Sibling.class.getConstructor(String.class), Sibling.class),
                Arrays.asList(name)));
        tc.addStatement(new AssignmentStatement(tc, mock, sibling));

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
        String className = "SiblingProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");
        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-sibling", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }
}
