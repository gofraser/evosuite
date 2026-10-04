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

import com.examples.with.different.packagename.DollarClassOuter;
import com.examples.with.different.packagename.DollarClassOuter$Hidden$PublicTopLevel;
import com.examples.with.different.packagename.MarkerExposerApi;
import org.evosuite.Properties;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

public class DollarTypeMetadataCompileTest {
    @Test
    public void publicTopLevelDollarNameDoesNotInheritUnrelatedMemberPrivacy() throws Exception {
        Class<?> type = DollarClassOuter$Hidden$PublicTopLevel.class;
        assertNull(type.getEnclosingClass());
        assertTrue(Modifier.isPublic(type.getModifiers()));
        String code = renderAndCompile(type, type, "value");
        assertFalse(code.contains("Class.forName"), code);
    }

    @Test
    public void exactMemberMetadataStillFollowsPrivateEnclosingChain() throws Exception {
        String binaryName = DollarClassOuter.class.getName() + "$Hidden$Child";
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(binaryName)) {
                    return super.loadClass(name, resolve);
                }
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try (InputStream input = getResourceAsStream(name.replace('.', '/') + ".class")) {
                        ClassWriter writer = new ClassWriter(0);
                        new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public void visitInnerClass(String name, String outerName,
                                                        String innerName, int access) {
                                // Match generated children that omit their own InnerClasses table.
                            }
                        }, 0);
                        byte[] bytes = writer.toByteArray();
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (Exception exception) {
                        throw new ClassNotFoundException(name, exception);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        };
        Class<?> type = loader.loadClass(binaryName);
        assertNull(type.getEnclosingClass());
        assertTrue(Modifier.isPublic(type.getModifiers()));
        String code = renderAndCompile(type, MarkerExposerApi.class, "size");
        assertTrue(code.contains("Class.forName(\"" + binaryName + "\")"), code);
    }

    private String renderAndCompile(Class<?> type, Class<?> api, String methodName) throws Exception {
        String oldPrefix = Properties.CLASS_PREFIX;
        Properties.CLASS_PREFIX = "org.evosuite.testcase";
        try {
            TestCase test = new DefaultTestCase();
            VariableReference value = test.addStatement(new StringPrimitiveStatement(test, "value"));
            VariableReference instance = test.addStatement(new ConstructorStatement(test,
                    new GenericConstructor(type.getConstructor(String.class), type),
                    Collections.singletonList(value)));
            test.addStatement(new MethodStatement(test,
                    new GenericMethod(api.getMethod(methodName), type), instance,
                    Collections.emptyList()));
            TestCodeVisitor visitor = new TestCodeVisitor();
            test.accept(visitor);
            StringBuilder source = new StringBuilder("package org.evosuite.testcase;\n");
            for (Class<?> imported : visitor.getImports()) {
                source.append("import ").append(imported.getCanonicalName()).append(";\n");
            }
            String name = "DollarMetadataProbe";
            source.append("public class ").append(name)
                    .append(" { public static void run() throws Throwable {\n")
                    .append(visitor.getCode()).append("}}\n");
            JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                    "dollar-metadata", name, source.toString());
            assertTrue(result.success, source + "\n" + result.stderr);
            return visitor.getCode();
        } finally {
            Properties.CLASS_PREFIX = oldPrefix;
        }
    }
}
