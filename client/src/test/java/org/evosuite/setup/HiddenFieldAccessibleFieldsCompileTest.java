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
package org.evosuite.setup;

import org.evosuite.Properties;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestCodeVisitor;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Firebird's {@code JaybirdSqlParser.arraySpec_return} declares a package-private
 * {@code CommonTree tree} that hides the public {@code Object tree} of ANTLR's
 * {@code ParserRuleReturnScope}. The accessible-field lookup used the public-only view and
 * offered the hidden superclass field, a reference of type Object that javac resolves to the
 * hiding field; assigning anything through it failed to compile.
 */
public class HiddenFieldAccessibleFieldsCompileTest {

    public static class Tree {
    }

    public static class Scope {
        public Object tree;
    }

    public static class Ret extends Scope {
        Tree tree;
    }

    private String originalPrefix;

    @BeforeEach
    public void setUp() {
        originalPrefix = Properties.CLASS_PREFIX;
        Properties.CLASS_PREFIX = "org.evosuite.setup";
    }

    @AfterEach
    public void tearDown() {
        Properties.CLASS_PREFIX = originalPrefix;
    }

    @Test
    public void testHidingFieldIsOfferedInsteadOfHiddenOne() {
        Field[] fields = TestClusterUtils.getAccessibleFields(Ret.class).toArray(new Field[0]);
        assertEquals(1, fields.length);
        assertEquals(Ret.class, fields[0].getDeclaringClass());
        assertEquals(Tree.class, fields[0].getType());
    }

    @Test
    public void testAssignmentThroughOfferedFieldCompiles() throws Exception {
        Field field = TestClusterUtils.getAccessibleFields(Ret.class).iterator().next();
        TestCase tc = new DefaultTestCase();
        VariableReference ret = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Ret.class.getConstructor(), Ret.class), Arrays.asList()));
        VariableReference value = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Tree.class.getConstructor(), Tree.class), Arrays.asList()));
        tc.addStatement(new AssignmentStatement(tc, new FieldReference(tc,
                new GenericField(field, Ret.class), ret), value));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        StringBuilder source = new StringBuilder("package org.evosuite.setup;\n");
        for (Class<?> imported : visitor.getImports()) {
            String canonical = imported.getCanonicalName();
            if (canonical != null) {
                source.append("import ").append(canonical).append(";\n");
            }
        }
        String className = "HiddenFieldProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : visitor.getCode().split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");
        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-hidden-field", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }
}
