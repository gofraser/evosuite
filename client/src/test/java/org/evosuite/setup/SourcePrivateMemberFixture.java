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

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Two crafted classes in package {@value #PACKAGE}: {@code Outer} and its member class
 * {@code Outer$Inner}. Inner's own class file calls it public, while Outer's InnerClasses table
 * calls it private, so reflection reports public and javac enforces private. Outer declares
 * {@code public static void use(Inner)} and {@code public boolean add(Inner)}.
 */
public final class SourcePrivateMemberFixture {

    public static final String PACKAGE = "craftedprivate";

    private static final String OUTER = "craftedprivate/Outer";
    private static final String INNER = "craftedprivate/Outer$Inner";

    public final Class<?> outer;
    public final Class<?> inner;

    public SourcePrivateMemberFixture() throws ClassNotFoundException {
        CraftedLoader loader = new CraftedLoader();
        outer = loader.loadClass("craftedprivate.Outer");
        inner = loader.loadClass("craftedprivate.Outer$Inner");
    }

    /** Defines the two crafted classes and serves their bytes as resources. */
    private static final class CraftedLoader extends ClassLoader {
        private final Map<String, byte[]> bytes = new HashMap<>();

        CraftedLoader() {
            super(SourcePrivateMemberFixture.class.getClassLoader());
            bytes.put(OUTER + ".class", outerBytes());
            bytes.put(INNER + ".class", innerBytes());
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
            if (name.startsWith(PACKAGE + ".")) {
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

    private static byte[] outerBytes() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, OUTER, null,
                "java/lang/Object", null);
        cw.visitInnerClass(INNER, OUTER, "Inner", Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC);
        constructor(cw);
        MethodVisitor use = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "use",
                "(L" + INNER + ";)V", null, null);
        use.visitCode();
        use.visitInsn(Opcodes.RETURN);
        use.visitMaxs(0, 1);
        use.visitEnd();
        MethodVisitor add = cw.visitMethod(Opcodes.ACC_PUBLIC, "add", "(L" + INNER + ";)Z",
                null, null);
        add.visitCode();
        add.visitInsn(Opcodes.ICONST_1);
        add.visitInsn(Opcodes.IRETURN);
        add.visitMaxs(1, 2);
        add.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] innerBytes() {
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
}
