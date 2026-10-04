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
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Observed on geo-google's {@code FunctionChain(List<Functor>)}, where {@code Functor} is a
 * generic class used raw: the null argument of an overloaded constructor call was cast to
 * {@code (List<Functor<?, ?, ?>>) null}, a different type from the declared {@code List<Functor>}
 * that no constructor accepts.
 */
public class RawTypeArgumentCallCompileTest {

    public static class Fn<A, B> {
    }

    public static class Chain {
        public Chain(List<Fn> functions) {
        }

        public Chain(Fn[] functions) {
        }
    }

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = RawTypeArgumentCallCompileTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
    }

    @Test
    public void testNullCastOfListOfRawGenericCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        Type listOfRaw = TypeUtils.parameterize(List.class, Fn.class);
        VariableReference nullList = tc.addStatement(new NullStatement(tc, listOfRaw));
        tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Chain.class.getConstructor(List.class), Chain.class),
                Arrays.asList(nullList)));

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
        String className = "RawArgProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");

        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-raw-arg", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }
}
