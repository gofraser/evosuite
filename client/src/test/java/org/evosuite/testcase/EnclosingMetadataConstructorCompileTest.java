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

import com.examples.with.different.packagename.HiddenExposerFixture;
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
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

public class EnclosingMetadataConstructorCompileTest {
    @Test
    public void privateMemberWithoutItsOwnInnerClassesAttributeCompiles() throws Exception {
        renderAndCompile("HiddenExposer", ArrayList.class);
    }

    @Test
    public void inheritedMarkerInterfaceDoesNotHideSuperclassApi() throws Exception {
        renderAndCompile("MarkerExposer", MarkerExposerApi.class);
    }

    private void renderAndCompile(String memberName, Class<?> api) throws Exception {
        String binaryName = HiddenExposerFixture.class.getName() + "$" + memberName;
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
                            public void visit(int version, int access, String name, String signature,
                                              String superName, String[] interfaces) {
                                super.visit(version, access | Opcodes.ACC_PUBLIC, name, signature,
                                        superName, interfaces);
                            }
                            @Override
                            public void visitInnerClass(String name, String outerName,
                                                        String innerName, int access) {
                                // Jython's generated child has no InnerClasses attribute at all.
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
        Class<?> exposer = loader.loadClass(binaryName);
        assertNull(exposer.getEnclosingClass());
        assertTrue(Modifier.isPublic(exposer.getModifiers()));
        String oldPrefix = Properties.CLASS_PREFIX;
        Properties.CLASS_PREFIX = "org.evosuite.testcase";
        try {
            TestCase test = new DefaultTestCase();
            VariableReference label = test.addStatement(new StringPrimitiveStatement(test, "value"));
            VariableReference instance = test.addStatement(new ConstructorStatement(test,
                    new GenericConstructor(exposer.getConstructor(String.class), exposer),
                    Collections.singletonList(label)));
            test.addStatement(new MethodStatement(test,
                    new GenericMethod(api.getMethod("size"), exposer), instance,
                    Collections.emptyList()));
            TestCodeVisitor visitor = new TestCodeVisitor();
            test.accept(visitor);
            StringBuilder source = new StringBuilder("package org.evosuite.testcase;\n");
            for (Class<?> imported : visitor.getImports()) {
                source.append("import ").append(imported.getCanonicalName()).append(";\n");
            }
            String name = "EnclosingMetadataProbe";
            source.append("public class ").append(name)
                    .append(" { public static void run() throws Throwable {\n")
                    .append(visitor.getCode()).append("}}\n");
            JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                    "enclosing-metadata", name, source.toString());
            assertTrue(result.success, source + "\n" + result.stderr);
            assertTrue(visitor.getCode().contains("Class.forName(\"" + binaryName + "\")"));
        } finally {
            Properties.CLASS_PREFIX = oldPrefix;
        }
    }
}
