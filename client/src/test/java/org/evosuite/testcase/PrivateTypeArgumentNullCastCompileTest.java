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
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * For {@code SGDText}: {@code m_dictionary.put(word, null)} on a
 * {@code LinkedHashMap<String, Count>} field, where {@code Count} is a private nested class, was
 * rendered {@code put("...", (SGDText.Count) null)}.
 */
public class PrivateTypeArgumentNullCastCompileTest {

    private static class Count {
    }

    public static class Holder {
        public LinkedHashMap<String, Count> dictionary = new LinkedHashMap<>();
    }

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = PrivateTypeArgumentNullCastCompileTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
    }

    @Test
    public void nullForPrivateTypeArgumentIsNotCastToThatType() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference holder = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Holder.class.getConstructor(), Holder.class), Arrays.asList()));
        FieldReference dictionary = new FieldReference(tc,
                new GenericField(Holder.class.getField("dictionary"), Holder.class), holder);
        VariableReference key = tc.addStatement(new StringPrimitiveStatement(tc, "key"));
        VariableReference nullValue = tc.addStatement(new NullStatement(tc, Count.class));
        tc.addStatement(new MethodStatement(tc,
                new GenericMethod(java.util.HashMap.class.getMethod("put", Object.class, Object.class),
                        Holder.class.getField("dictionary").getGenericType()), dictionary, Arrays.asList(key, nullValue)));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();
        assertFalse(code.contains("Count) null"), code);

        StringBuilder source = new StringBuilder("package org.evosuite.testcase;\n");
        for (Class<?> imported : visitor.getImports()) {
            String canonical = imported.getCanonicalName();
            if (canonical != null) {
                source.append("import ").append(canonical).append(";\n");
            }
        }
        String className = "PrivateNullProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");
        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-private-null", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }

    private static class SerializableCount implements java.io.Serializable {
    }

    public static class SerializableHolder {
        public LinkedHashMap<String, SerializableCount> serialDictionary = new LinkedHashMap<>();
    }

    @Test
    public void nullForPrivateTypeArgumentWithAccessibleSupertypeIsNotCastToSupertype() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference holder = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(SerializableHolder.class.getConstructor(), SerializableHolder.class), Arrays.asList()));
        FieldReference dictionary = new FieldReference(tc,
                new GenericField(SerializableHolder.class.getField("serialDictionary"), SerializableHolder.class), holder);
        VariableReference key = tc.addStatement(new StringPrimitiveStatement(tc, "key"));
        VariableReference nullValue = tc.addStatement(new NullStatement(tc, SerializableCount.class));
        tc.addStatement(new MethodStatement(tc,
                new GenericMethod(java.util.HashMap.class.getMethod("put", Object.class, Object.class),
                        SerializableHolder.class.getField("serialDictionary").getGenericType()), dictionary, Arrays.asList(key, nullValue)));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();
        assertFalse(code.contains("Serializable) null"), code);

        StringBuilder source = new StringBuilder("package org.evosuite.testcase;\n");
        for (Class<?> imported : visitor.getImports()) {
            String canonical = imported.getCanonicalName();
            if (canonical != null) {
                source.append("import ").append(canonical).append(";\n");
            }
        }
        String className = "PrivateSerialNullProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");
        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-private-null", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }
}
