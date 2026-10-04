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
import org.evosuite.instrumentation.BytecodeInstrumentation;
import org.evosuite.instrumentation.InstrumentationConfig;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.FieldStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

public class PromotedFieldRenderingCompileTest {
    private static final String BASE = "com.examples.with.different.packagename.fields.FieldAccessBase";
    private static final String CHILD = "com.examples.with.different.packagename.fields.other.FieldAccessChild";

    @Test
    public void promotedForeignFieldsUseReflectiveReadsAndWrites() throws Exception {
        renderAndCompile("org.evosuite.testcase", "source", true);
    }

    @Test
    public void packagePrivateFieldIsNotInheritedThroughForeignReceiver() throws Exception {
        renderAndCompile("com.examples.with.different.packagename.fields", "source", true);
    }

    @Test
    public void publicFieldsKeepDirectReadsAndWrites() throws Exception {
        renderAndCompile("org.evosuite.testcase", "publicSource", false);
    }

    private void renderAndCompile(String outputPackage, String fieldName, boolean reflective)
            throws Exception {
        String oldPrefix = Properties.CLASS_PREFIX;
        String oldTarget = Properties.TARGET_CLASS;
        Properties.CLASS_PREFIX = "com.examples.with.different.packagename.fields";
        Properties.TARGET_CLASS = BASE;
        Properties.resetTargetClass();
        try {
            class FixtureLoader extends ClassLoader {
                Class<?> instrument(String binaryName) throws Exception {
                    String name = binaryName.replace('.', '/');
                    byte[] bytes = new BytecodeInstrumentation(new InstrumentationConfig() {
                        @Override
                        public boolean makeAccessible() {
                            return true;
                        }
                    }).transformBytes(this, name, new ClassReader(name));
                    return defineClass(binaryName, bytes, 0, bytes.length);
                }
            }
            FixtureLoader loader = new FixtureLoader();
            Class<?> base = loader.instrument(BASE);
            Class<?> child = loader.instrument(CHILD);
            Field field = base.getDeclaredField(fieldName);
            assertTrue(Modifier.isPublic(field.getModifiers()), "instrumentation must promote the field");
            Properties.CLASS_PREFIX = outputPackage;
            TestCase test = new DefaultTestCase();
            VariableReference instance = test.addStatement(new ConstructorStatement(test,
                    new GenericConstructor(child.getConstructor(), child), Collections.emptyList()));
            GenericField genericField = new GenericField(field, child);
            test.addStatement(new FieldStatement(test, genericField, instance));
            VariableReference value = test.addStatement(new IntPrimitiveStatement(test, 17));
            test.addStatement(new AssignmentStatement(test,
                    new FieldReference(test, genericField, instance), value));
            TestCodeVisitor visitor = new TestCodeVisitor();
            test.accept(visitor);
            StringBuilder source = new StringBuilder("package " + outputPackage + ";\n");
            for (Class<?> imported : visitor.getImports()) {
                source.append("import ").append(imported.getCanonicalName()).append(";\n");
            }
            String className = "PromotedFieldProbe";
            source.append("public class ").append(className)
                    .append(" { public static void run() throws Throwable {\n")
                    .append(visitor.getCode()).append("}}\n");
            JavacScratchCompiler.Result compiled = JavacScratchCompiler.compile(
                    "promoted-field-rendering", className, source.toString());
            assertTrue(compiled.success, source + "\n" + compiled.stderr);
            assertEquals(reflective, visitor.getCode().contains("PrivateAccess.getVariable("));
            assertEquals(reflective, visitor.getCode().contains("PrivateAccess.setVariable("));
        } finally {
            Properties.CLASS_PREFIX = oldPrefix;
            Properties.TARGET_CLASS = oldTarget;
            Properties.resetTargetClass();
        }
    }
}
