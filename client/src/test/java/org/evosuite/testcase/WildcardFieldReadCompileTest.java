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
import org.evosuite.testcase.statements.FieldStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Observed on JacksonDatabind-103: {@code ObjectIdGenerator.IdKey.type} is a {@code Class<?>}
 * field, and its value was declared {@code Class<Object>}. A parameterized cast from
 * {@code Class<capture>} to {@code Class<Object>} is itself rejected, so reading the field
 * needs a raw cast (an unchecked but legal conversion) or the declaration must keep the wildcard.
 */
public class WildcardFieldReadCompileTest {

    public static class Key {
        public final Class<?> type = String.class;
    }

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = WildcardFieldReadCompileTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
    }

    @Test
    public void testWildcardFieldReadIntoConcreteClassVariableCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference key = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Key.class.getConstructor(), Key.class),
                Collections.emptyList()));
        FieldStatement read = new FieldStatement(tc,
                new GenericField(Key.class.getField("type"), Key.class), key);
        Type classOfObject = TypeUtils.parameterize(Class.class, Object.class);
        read.getReturnValue().setType(classOfObject);
        tc.addStatement(read);

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        StringBuilder source = new StringBuilder("package org.evosuite.testcase;\n");
        for (Class<?> imported : visitor.getImports()) {
            String canonical = imported.getCanonicalName();
            if (canonical != null) {
                source.append("import ").append(canonical).append(";\n");
            }
        }
        String className = "WildcardFieldReadProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : visitor.getCode().split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");
        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-wildcard-field-read", className, source.toString());
        assertTrue(visitor.getCode().contains("= (Class)"), visitor.getCode());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }
}
