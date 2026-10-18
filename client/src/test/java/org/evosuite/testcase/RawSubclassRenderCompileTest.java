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
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collections;
import java.util.Hashtable;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rendered tests that call generic members through a non-generic subclass of a raw generic
 * class must compile: javac sees the members erased, so every result must be cast back to the
 * type the test variable carries.
 */
public class RawSubclassRenderCompileTest {

    private String originalTargetClass;

    @BeforeEach
    public void setUpTargetClass() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = RawSubclassRenderCompileTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void restoreTargetClass() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
    }

    @SuppressWarnings("rawtypes")
    public static class RawHashtableSubclass extends Hashtable {
        private static final long serialVersionUID = 1L;

        public RawHashtableSubclass() {
        }
    }

    public static class ArrayConsumer {
        public ArrayConsumer(String[] values) {
        }
    }

    @Test
    public void arrayResultThroughRawSubclassCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        Type tableType = TypeUtils.parameterize(Hashtable.class, String.class, String[].class);
        VariableReference table = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(RawHashtableSubclass.class.getConstructor(), RawHashtableSubclass.class),
                Collections.emptyList()));
        table.setType(tableType);
        VariableReference key = tc.addStatement(new StringPrimitiveStatement(tc, "a"));
        VariableReference array = tc.addStatement(new ArrayStatement(tc, String[].class, 1));
        Method put = Hashtable.class.getMethod("put", Object.class, Object.class);
        VariableReference previous = tc.addStatement(new MethodStatement(tc,
                new GenericMethod(put, tableType), table, Arrays.asList(key, array)));
        assertTrue(previous.getVariableClass().equals(String[].class), "Setup: put must return String[]");
        tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(ArrayConsumer.class.getConstructor(String[].class), ArrayConsumer.class),
                Collections.singletonList(previous)));

        assertCompiles(tc);
    }

    static void assertCompiles(TestCase tc) {
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        StringBuilder source = new StringBuilder();
        source.append("package org.evosuite.testcase;\n");
        for (Class<?> imported : visitor.getImports()) {
            String canonical = imported.getCanonicalName();
            if (canonical != null) {
                source.append("import ").append(canonical).append(";\n");
            }
        }
        String className = "RawSubclassRenderProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");

        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-raw-subclass-render-compile", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }
}
