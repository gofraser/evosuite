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
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Observed on Liferay's {@code MapBackedSet}: a variable that EvoSuite declared with a wildcard type
 * argument ({@code LinkedHashMap<?, Boolean>}) was passed to a constructor whose parameter is
 * {@code Map<E, Boolean>} with {@code E} resolved to {@code String}. javac rejects that.
 */
public class WildcardArgumentCompileTest {

    private String originalTargetClass;

    public static class MapBackedHolder<E> {
        public MapBackedHolder(Map<E, Boolean> backing) {
        }
    }

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = WildcardArgumentCompileTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
    }

    @Test
    public void testTypeVariableTypedVariableAsGenericConstructorArgumentCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        // The constructor's own type variable E leaked into the variable's type, as in the
        // observed run; it is declared as LinkedHashMap<?, Boolean>.
        Type wildcardMap = TypeUtils.parameterize(LinkedHashMap.class,
                MapBackedHolder.class.getTypeParameters()[0], Boolean.class);
        VariableReference map = TestFactory.getInstance().addConstructor(
                tc,
                new GenericConstructor(LinkedHashMap.class.getConstructor(), wildcardMap),
                0, 0);

        Type holderType = TypeUtils.parameterize(MapBackedHolder.class, String.class);
        ConstructorStatement holder = new ConstructorStatement(
                tc,
                new GenericConstructor(MapBackedHolder.class.getConstructor(Map.class), holderType),
                Arrays.asList(map));
        tc.addStatement(holder);

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
        String className = "WildcardArgumentProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");

        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-wildcard-argument", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }
}
