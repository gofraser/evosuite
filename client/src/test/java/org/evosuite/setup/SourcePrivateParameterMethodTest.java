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
package org.evosuite.setup;

import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A public method taking a member class that its own class file calls public while the enclosing
 * class's InnerClasses table calls it private. Reflection reports the parameter type as public,
 * but javac enforces private, so no test can write an argument for the method.
 */
public class SourcePrivateParameterMethodTest {

    private static final String OUTER = "craftedprivate/Outer";
    private static final String INNER = "craftedprivate/Outer$Inner";

    private String originalClassPrefix;
    private Class<?> outer;
    private Class<?> inner;

    /** Defines the two crafted classes and serves their bytes as resources. */
    private static final class CraftedLoader extends ClassLoader {
        private final Map<String, byte[]> bytes = new HashMap<>();

        CraftedLoader() {
            super(SourcePrivateParameterMethodTest.class.getClassLoader());
            bytes.put(OUTER + ".class", outer());
            bytes.put(INNER + ".class", inner());
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] data = bytes.get(name.replace('.', '/') + ".class");
            if (data == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, data, 0, data.length);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("craftedprivate.")) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    return loaded != null ? loaded : findClass(name);
                }
            }
            return super.loadClass(name, resolve);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            byte[] data = bytes.get(name);
            return data != null ? new ByteArrayInputStream(data) : super.getResourceAsStream(name);
        }
    }

    private static byte[] outer() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, OUTER, null,
                "java/lang/Object", null);
        cw.visitInnerClass(INNER, OUTER, "Inner", Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC);
        constructor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "use",
                "(L" + INNER + ";)V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] inner() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, INNER, null,
                "java/lang/Object", null);
        cw.visitInnerClass(INNER, OUTER, "Inner", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        constructor(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void constructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    @BeforeEach
    public void setUp() throws Exception {
        originalClassPrefix = Properties.CLASS_PREFIX;
        Properties.CLASS_PREFIX = "craftedprivate";
        CraftedLoader loader = new CraftedLoader();
        outer = loader.loadClass("craftedprivate.Outer");
        inner = loader.loadClass("craftedprivate.Outer$Inner");
    }

    @AfterEach
    public void tearDown() {
        Properties.CLASS_PREFIX = originalClassPrefix;
    }

    @Test
    public void reflectionDisagreesWithTheEnclosingTable() {
        assertTrue(Modifier.isPublic(inner.getModifiers()));
        assertTrue(Modifier.isPrivate(TestUsageChecker.getSourceModifiers(inner)));
    }

    @Test
    public void methodTakingTheSourcePrivateTypeIsNotUsable() throws Exception {
        Method use = outer.getMethod("use", inner);
        assertTrue(TestUsageChecker.isNotCallableFromSource(use));
        assertFalse(TestUsageChecker.canUse(use, outer),
                "canUse must agree with isNotCallableFromSource: callers that only ask canUse"
                        + " would otherwise render a call no test can compile");
    }
}
