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
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MapperConfig<MapperConfig<SerializationConfig>>} and
 * {@code TypeResolverBuilder<DefaultTypeResolverBuilder>} were declared although the type
 * argument is not within the recursive bound {@code T extends Foo<T>} ("type argument ... is
 * not within bounds of type-variable T").
 */
public class RecursiveBoundCompileTest {

    public interface Builder<T extends Builder<T>> {
    }

    public static class Standard implements Builder<Standard> {
    }

    public static class Default extends Standard {
    }

    public static class Config<T extends Config<T>> {
    }

    public static class Serialization extends Config<Serialization> {
    }

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = RecursiveBoundCompileTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
    }

    /** Default inherits Builder<Standard>, so Builder<Default> is out of bounds. */
    @Test
    public void testSubclassOfTheBoundTypeAsArgumentCompiles() throws Exception {
        assertListOfCompiles(TypeUtils.parameterize(Builder.class, Default.class));
    }

    /** Config<Serialization> is not a Config<Config<Serialization>>. */
    @Test
    public void testNestedInstantiationAsArgumentCompiles() throws Exception {
        assertListOfCompiles(TypeUtils.parameterize(Config.class,
                TypeUtils.parameterize(Config.class, Serialization.class)));
    }

    /** A bound that does hold must keep its argument. */
    @Test
    public void testWithinBoundArgumentIsKept() throws Exception {
        TestCodeVisitor visitor = renderListOf(TypeUtils.parameterize(Config.class,
                Serialization.class));
        assertTrue(visitor.getCode().contains("Config<RecursiveBoundCompileTest.Serialization>"),
                visitor.getCode());
    }

    private TestCodeVisitor renderListOf(Type elementType) throws Exception {
        TestCase tc = new DefaultTestCase();
        Type listType = TypeUtils.parameterize(ArrayList.class, elementType);
        tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(ArrayList.class.getConstructor(), listType),
                Arrays.asList()));
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        return visitor;
    }

    private void assertListOfCompiles(Type elementType) throws Exception {
        TestCodeVisitor visitor = renderListOf(elementType);
        String code = visitor.getCode();

        StringBuilder source = new StringBuilder("package org.evosuite.testcase;\n");
        for (Class<?> imported : visitor.getImports()) {
            String canonical = imported.getCanonicalName();
            if (canonical != null) {
                source.append("import ").append(canonical).append(";\n");
            }
        }
        String className = "RecursiveBoundProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");

        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-recursive-bound", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }
}
