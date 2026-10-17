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
import org.evosuite.testcase.accessfixture.TableBase;
import org.evosuite.testcase.accessfixture.other.SecretHolder;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.FieldStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.variable.VariableReferenceImpl;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A variable whose class is a private nested class of another package is declared with an
 * accessible supertype, whichever statement declares it: the private class cannot be named.
 */
public class HiddenClassDeclarationCompileTest {

    private static final String TEST_PACKAGE = TableBase.class.getPackage().getName();

    private String originalTargetClass;
    private String originalClassPrefix;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        originalClassPrefix = Properties.CLASS_PREFIX;
        Properties.TARGET_CLASS = TableBase.class.getName();
        Properties.resetTargetClass();
        Properties.CLASS_PREFIX = TEST_PACKAGE;
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.CLASS_PREFIX = originalClassPrefix;
        Properties.resetTargetClass();
    }

    private static Class<?> secretClass() throws ClassNotFoundException {
        return Class.forName(SecretHolder.class.getName() + "$Secret");
    }

    private static VariableReference addHolder(TestCase tc) throws Exception {
        return tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(SecretHolder.class.getConstructor(), SecretHolder.class),
                Collections.emptyList()));
    }

    private static VariableReference readField(TestCase tc, VariableReference holder, String name)
            throws Exception {
        return tc.addStatement(new FieldStatement(tc,
                new GenericField(SecretHolder.class.getField(name), SecretHolder.class), holder));
    }

    private static void run(TestCase tc, VariableReference runnable) throws Exception {
        tc.addStatement(new MethodStatement(tc,
                new GenericMethod(Runnable.class.getMethod("run"), Runnable.class), runnable,
                Collections.emptyList()));
    }

    @Test
    public void fieldReadOfAPrivateClassIsDeclaredWithAnAccessibleSupertype() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference holder = addHolder(tc);
        VariableReference secret = readField(tc, holder, "secret");
        run(tc, secret);

        String code = render(tc);
        assertCompiles(code);
        assertFalse(code.contains("Secret "), code);
    }

    @Test
    public void castFieldReadOfAPrivateClassIsCastToTheAccessibleSupertype() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference holder = addHolder(tc);
        VariableReference secret = readField(tc, holder, "anything");
        secret.setType(secretClass());
        run(tc, secret);

        String code = render(tc);
        assertCompiles(code);
        assertFalse(code.contains("Secret)"), code);
    }

    @Test
    public void assignmentDeclaringAPrivateClassIsDeclaredWithAnAccessibleSupertype() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference holder = addHolder(tc);
        VariableReference made = tc.addStatement(new MethodStatement(tc,
                new GenericMethod(SecretHolder.class.getMethod("make"), SecretHolder.class), holder,
                Collections.emptyList()));
        VariableReference target = new VariableReferenceImpl(tc, secretClass());
        tc.addStatement(new AssignmentStatement(tc, target, made));
        run(tc, target);

        String code = render(tc);
        assertCompiles(code);
        assertFalse(code.contains("Secret "), code);
    }

    private static String render(TestCase tc) {
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        StringBuilder imports = new StringBuilder();
        for (Class<?> imported : visitor.getImports()) {
            String canonical = imported.getCanonicalName();
            if (canonical != null) {
                imports.append("import ").append(canonical).append(";\n");
            }
        }
        return imports + "\n" + visitor.getCode();
    }

    private static void assertCompiles(String importsAndCode) {
        int split = importsAndCode.indexOf("\n\n");
        String imports = importsAndCode.substring(0, split);
        String code = importsAndCode.substring(split + 2);
        String className = "HiddenClassDeclarationProbe" + System.nanoTime();
        StringBuilder source = new StringBuilder();
        source.append("package ").append(TEST_PACKAGE).append(";\n").append(imports).append("\n");
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");

        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-hidden-class-declaration", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }
}
