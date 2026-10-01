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
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ClassPrimitiveStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.evosuite.utils.generic.JavacScratchCompiler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.LinkedList;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GenericClassUtils} deliberately treats a {@code Class<Raw>} class-literal
 * variable (eg {@code Class<LinkedList> class0 = LinkedList.class;}) as satisfying a
 * parameterised {@code Class<...>} target, even though javac rejects that assignment
 * directly (see {@code 90e6a0906} and {@code ClassLiteralVarianceTest}). At a method
 * call argument, {@code TestCodeVisitor.getParameterString}'s {@code requiresRawClassCast}
 * compensates with a structural raw {@code (Class)} cast -- see
 * {@code testClassLiteralParameterCastUsesRawClass} above. This class checks whether the
 * same compensation exists on the two other paths such a variable can reach a
 * parameterised {@code Class<...>} target: a field assignment, and a value returned from
 * a method whose declared (instantiated) result is a parameterised {@code Class<...>}.
 *
 * <p>Each case drives the real {@link TestCodeVisitor} over a hand-built {@link TestCase}
 * and feeds the rendered source to {@code javac}, per the precedent in
 * {@code GeneratedAssertionCompileCheck} and {@code ClassLiteralVarianceTest}: the
 * assertion is "does it compile", never a match against the generated string.
 */
public class ClassLiteralRawPathCompileTest {

    private String originalTargetClass;
    private String originalClassPrefix;
    private String originalSubPrefix;
    private String originalProjectPrefix;

    @BeforeEach
    public void setUpTargetClass() {
        originalTargetClass = Properties.TARGET_CLASS;
        originalClassPrefix = Properties.CLASS_PREFIX;
        originalSubPrefix = Properties.SUB_PREFIX;
        originalProjectPrefix = Properties.PROJECT_PREFIX;

        Properties.TARGET_CLASS = ClassLiteralRawPathCompileTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void restoreTargetClass() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.CLASS_PREFIX = originalClassPrefix;
        Properties.SUB_PREFIX = originalSubPrefix;
        Properties.PROJECT_PREFIX = originalProjectPrefix;
        Properties.resetTargetClass();
    }

    /** Scenario (a): a directly parameterised field target. */
    public static class ClassLiteralFieldHolder {
        public Class<LinkedList<Integer>> target;
    }

    /**
     * Scenario (b): a class literal written into a generic field, then read back out
     * through a method whose declared, instantiated return type is a parameterised
     * {@code Class<...>} -- "passed through a method whose declared result is
     * parameterised".
     */
    public static class GenericClassLiteralHolder<T> {
        public Class<T> type;

        public Class<T> getType() {
            return type;
        }
    }

    @Test
    public void testClassLiteralFieldAssignmentCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference holder = TestFactory.getInstance().addConstructor(
                tc,
                new GenericConstructor(ClassLiteralFieldHolder.class.getDeclaredConstructor(),
                        ClassLiteralFieldHolder.class),
                0, 0);
        VariableReference classVar = tc.addStatement(new ClassPrimitiveStatement(tc, LinkedList.class));

        Field targetField = ClassLiteralFieldHolder.class.getDeclaredField("target");
        FieldReference fieldRef = new FieldReference(
                tc, new GenericField(targetField, ClassLiteralFieldHolder.class), holder);
        AssignmentStatement assignment = new AssignmentStatement(tc, fieldRef, classVar);
        assertTrue(assignment.isValid(), "assignment must be considered valid by EvoSuite's own model");
        tc.addStatement(assignment);

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        assertCompiles(visitor);
    }

    @Test
    public void testClassLiteralThroughGenericFieldAndReturnCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        Type holderType = TypeUtils.parameterize(GenericClassLiteralHolder.class,
                TypeUtils.parameterize(LinkedList.class, Integer.class));

        VariableReference holder = TestFactory.getInstance().addConstructor(
                tc,
                new GenericConstructor(
                        GenericClassLiteralHolder.class.getDeclaredConstructor(), holderType),
                0, 0);
        VariableReference classVar = tc.addStatement(new ClassPrimitiveStatement(tc, LinkedList.class));

        Field typeField = GenericClassLiteralHolder.class.getDeclaredField("type");
        FieldReference fieldRef = new FieldReference(
                tc, new GenericField(typeField, holderType), holder);
        AssignmentStatement assignment = new AssignmentStatement(tc, fieldRef, classVar);
        assertTrue(assignment.isValid(), "assignment must be considered valid by EvoSuite's own model");
        tc.addStatement(assignment);

        Method getType = GenericClassLiteralHolder.class.getDeclaredMethod("getType");
        MethodStatement methodStatement = new MethodStatement(
                tc, new GenericMethod(getType, holderType), holder, Arrays.asList());
        tc.addStatement(methodStatement);

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        assertCompiles(visitor);
    }

    // -- javac grounding -----------------------------------------------------------------------

    /**
     * Wraps the rendered statements as the body of a synthetic method in package
     * {@code org.evosuite.testcase} (so the package-visible fixture classes above resolve
     * by simple/outer-qualified name exactly as rendered) and compiles it with the real
     * system compiler. See {@link JavacScratchCompiler}, which owns the scratch directory
     * this needs and cleans it up again -- shared with {@code GenericAssignabilityMatrixTest}
     * and {@code ClassLiteralVarianceTest} since this is otherwise the same
     * compile-a-throwaway-probe boilerplate.
     */
    private static void assertCompiles(TestCodeVisitor visitor) {
        String code = visitor.getCode();

        StringBuilder source = new StringBuilder();
        source.append("package org.evosuite.testcase;\n");
        for (Class<?> imported : visitor.getImports()) {
            String canonical = imported.getCanonicalName();
            if (canonical != null) {
                source.append("import ").append(canonical).append(";\n");
            }
        }
        String className = "ClassLiteralRawPathProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n")
                .append("}\n");

        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-class-literal-raw-path-compile", className, source.toString());

        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }
}
