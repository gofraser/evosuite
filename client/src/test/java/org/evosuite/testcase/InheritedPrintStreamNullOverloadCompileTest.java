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
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.variable.ConstantValue;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Observed on Weka's LogWindow: its inner LogWindowPrintStream overrides println(String) and
 * println(Object) of PrintStream, which also declares println(char[]). A null argument is
 * applicable to all three, so the call only compiles with a cast to the targeted parameter.
 */
public class InheritedPrintStreamNullOverloadCompileTest {

    protected class InnerPrintStream extends PrintStream {
        public InnerPrintStream(OutputStream out) {
            super(out);
        }

        @Override
        public void println(String x) {
        }

        @Override
        public void println(Object x) {
        }
    }

    protected static class NestedPrintStream extends PrintStream {
        public NestedPrintStream(OutputStream out) {
            super(out);
        }

        @Override
        public void println(String x) {
        }

        @Override
        public void println(Object x) {
        }
    }

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = InheritedPrintStreamNullOverloadCompileTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
    }

    /**
     * The test cluster types an inner class of a non-generic outer class as a parameterized
     * type without type arguments (owner LogWindow), not as the plain class.
     */
    private static Type streamType(Class<?> streamClass) {
        if (streamClass == InnerPrintStream.class) {
            return TypeUtils.parameterizeWithOwner(InheritedPrintStreamNullOverloadCompileTest.class,
                    InnerPrintStream.class);
        }
        return streamClass;
    }

    private VariableReference createStream(TestCase tc, Class<?> streamClass) throws Exception {
        VariableReference out = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(ByteArrayOutputStream.class.getConstructor(),
                        ByteArrayOutputStream.class),
                Collections.emptyList()));
        if (streamClass == InnerPrintStream.class) {
            VariableReference outer = tc.addStatement(new ConstructorStatement(tc,
                    new GenericConstructor(InheritedPrintStreamNullOverloadCompileTest.class.getConstructor(),
                            InheritedPrintStreamNullOverloadCompileTest.class),
                    Collections.emptyList()));
            return tc.addStatement(new ConstructorStatement(tc,
                    new GenericConstructor(InnerPrintStream.class.getConstructor(
                            InheritedPrintStreamNullOverloadCompileTest.class, OutputStream.class),
                            streamType(InnerPrintStream.class)),
                    Arrays.asList(outer, out)));
        }
        return tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(streamClass.getConstructor(OutputStream.class), streamClass),
                Collections.singletonList(out)));
    }

    private void check(Class<?> streamClass, Class<?> methodOwner, Class<?> parameterType)
            throws Exception {
        check(streamClass, methodOwner, parameterType, false);
        check(streamClass, methodOwner, parameterType, true);
    }

    private void check(Class<?> streamClass, Class<?> methodOwner, Class<?> parameterType,
                       boolean inlinedStringNull) throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference stream = createStream(tc, streamClass);
        // The search inlines a null constant whose tracked type may differ from the parameter.
        VariableReference nullArgument = inlinedStringNull
                ? new ConstantValue(tc, GenericClassFactory.get(String.class), null)
                : tc.addStatement(new NullStatement(tc, parameterType));
        tc.addStatement(new MethodStatement(tc,
                new GenericMethod(methodOwner.getMethod("println", parameterType), streamType(streamClass)),
                stream, Collections.singletonList(nullArgument)));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        StringBuilder source = new StringBuilder("package org.evosuite.testcase;\n");
        for (Class<?> imported : visitor.getImports()) {
            String canonical = imported.getCanonicalName();
            if (canonical != null) {
                source.append("import ").append(canonical).append(";\n");
            }
        }
        String className = "InheritedPrintStreamProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : visitor.getCode().split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");
        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-inherited-printstream", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }

    @Test
    public void nullArgumentToOverridingOverloadOfInnerClassCompiles() throws Exception {
        check(InnerPrintStream.class, InnerPrintStream.class, String.class);
        check(InnerPrintStream.class, InnerPrintStream.class, Object.class);
        check(InnerPrintStream.class, PrintStream.class, char[].class);
    }

    @Test
    public void nullArgumentToOverridingOverloadOfNestedClassCompiles() throws Exception {
        check(NestedPrintStream.class, NestedPrintStream.class, String.class);
        check(NestedPrintStream.class, NestedPrintStream.class, Object.class);
        check(NestedPrintStream.class, PrintStream.class, char[].class);
    }
}
