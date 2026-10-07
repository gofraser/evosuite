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
import org.evosuite.setup.TestUsageChecker;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A member class whose own class file calls it public while the enclosing class's InnerClasses
 * table calls it protected (xerces' ScannedEntity): reflection reports public, javac enforces
 * protected, and a test outside the package can neither name the class nor construct it.
 */
public class ProtectedMemberClassAccessTest {

    private static final String OUTER = "craftedprotected/Outer";
    private static final String INNER = "craftedprotected/Outer$Inner";

    private String originalClassPrefix;
    private Class<?> inner;
    private Constructor<?> innerConstructor;

    /** Defines the two crafted classes and serves their bytes as resources. */
    private static final class CraftedLoader extends ClassLoader {
        private final Map<String, byte[]> bytes = new HashMap<>();

        CraftedLoader() {
            super(ProtectedMemberClassAccessTest.class.getClassLoader());
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
            if (name.startsWith("craftedprotected.")) {
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
        cw.visitInnerClass(INNER, OUTER, "Inner", Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC);
        constructor(cw);
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
        inner = new CraftedLoader().loadClass("craftedprotected.Outer$Inner");
        innerConstructor = inner.getConstructor();
        Properties.CLASS_PREFIX = "some.other.pkg";
    }

    @AfterEach
    public void tearDown() {
        Properties.CLASS_PREFIX = originalClassPrefix;
    }

    @Test
    public void reflectionDisagreesWithTheEnclosingTable() {
        assertTrue(Modifier.isPublic(inner.getModifiers()));
        assertTrue(Modifier.isProtected(TestUsageChecker.getSourceModifiers(inner)));
    }

    @Test
    public void constructorOfProtectedMemberIsNotAdmittedOutsideItsPackage() {
        assertFalse(TestUsageChecker.canUse(innerConstructor));
    }

    @Test
    public void constructorOfProtectedMemberIsAdmittedInItsOwnPackage() {
        Properties.CLASS_PREFIX = "craftedprotected";
        assertTrue(TestUsageChecker.canUse(innerConstructor));
    }

    @Test
    public void protectedMemberIsNeitherNamedNorConstructedDirectly() {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new ConstructorStatement(tc, new GenericConstructor(innerConstructor, inner),
                Collections.<VariableReference>emptyList()));
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();
        assertFalse(code.contains("new "), code);
        assertFalse(code.contains("Outer.Inner "), code);
    }
}
