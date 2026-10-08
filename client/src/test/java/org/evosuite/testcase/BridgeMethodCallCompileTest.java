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
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Observed on summa's {@code StringTracker extends ResourceTrackerImpl<String>}: the search calls the
 * synthetic bridge {@code calculateBytes(Object)} with an argument that is not a String, to provoke
 * a ClassCastException. Source code that says {@code tracker.calculateBytes(x)} binds to the
 * {@code calculateBytes(String)} override instead and does not compile.
 */
public class BridgeMethodCallCompileTest {

    public static class Base<T> {
        public long calc(T value) {
            return 0;
        }
    }

    public static class Sub extends Base<String> {
        @Override
        public long calc(String value) {
            return value.length();
        }
    }

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = BridgeMethodCallCompileTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
    }

    @Test
    public void testBridgeCalledWithNonStringArgumentCompiles() throws Exception {
        java.lang.reflect.Method bridge = Sub.class.getMethod("calc", Object.class);
        assertTrue(bridge.isBridge());
        TestCase tc = new DefaultTestCase();
        VariableReference sub = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Sub.class.getConstructor(), Sub.class), Arrays.asList()));
        VariableReference arg = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Object.class.getConstructor(), Object.class), Arrays.asList()));
        tc.addStatement(new MethodStatement(tc, new GenericMethod(bridge, Sub.class), sub,
                Arrays.asList(arg)));

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
        String className = "BridgeProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");

        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-bridge", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }

    @Test
    public void testGenericMethodCalledOnSubclassReceiverWithNonStringArgumentCompiles() throws Exception {
        // The target class is the generic base: the method is Base.calc(T), not the bridge.
        java.lang.reflect.Method bridge = Base.class.getMethod("calc", Object.class);
        assertFalse(bridge.isBridge());
        TestCase tc = new DefaultTestCase();
        VariableReference sub = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Sub.class.getConstructor(), Sub.class), Arrays.asList()));
        VariableReference arg = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Object.class.getConstructor(), Object.class), Arrays.asList()));
        tc.addStatement(new MethodStatement(tc, new GenericMethod(bridge, Base.class), sub,
                Arrays.asList(arg)));

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
        String className = "BridgeProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");

        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-bridge", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }
}
