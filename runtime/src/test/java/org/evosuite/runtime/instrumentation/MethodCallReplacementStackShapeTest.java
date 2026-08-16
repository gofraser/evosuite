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
package org.evosuite.runtime.instrumentation;

import org.evosuite.runtime.RuntimeSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Both branches of the "is mocking enabled?" diamond that the method call replacement
 * instrumentation emits have to leave the operand stack in the same shape. If they do not,
 * ASM cannot compute the stack map frames and the class cannot be instrumented at all.
 */
public class MethodCallReplacementStackShapeTest {

    @BeforeEach
    public void enableJvmMocking() {
        RuntimeSettings.mockJVMNonDeterminism = true;
        MethodCallReplacementCache.resetSingleton();
    }

    @AfterEach
    public void resetState() {
        RuntimeSettings.mockJVMNonDeterminism = false;
        MethodCallReplacementCache.resetSingleton();
    }

    /**
     * {@code Desktop.browse(URI)} is an instance method that is replaced by a static mock taking
     * the receiver as first argument. The fallback branch must re-emit an INVOKEVIRTUAL, not the
     * INVOKESTATIC of the replacement, otherwise the receiver is left on the stack.
     */
    @Test
    public void testInstanceCallReplacedByStaticMockKeepsStackShape() {
        byte[] instrumented = instrument(DesktopCaller.class.getName());
        assertVerifies(instrumented);
    }

    /**
     * A discarded {@code new java.net.URL(...)} compiled without a DUP (as ecj does) leaves a
     * single uninitialized reference on the stack, so the constructor-to-factory replacement must
     * only pop once.
     */
    @Test
    public void testDiscardedConstructionWithoutDupIsReplaced() {
        byte[] instrumented = instrument(new ClassReader(generateDiscardedUrlConstruction()),
                "org.evosuite.runtime.instrumentation.NoDupUrlCaller");
        assertVerifies(instrumented);
    }

    /**
     * The same construction with the usual javac shape (NEW, DUP, ..., INVOKESPECIAL, POP) has to
     * keep working.
     */
    @Test
    public void testDiscardedConstructionWithDupIsReplaced() {
        byte[] instrumented = instrument(UrlCaller.class.getName());
        assertVerifies(instrumented);
    }

    private static byte[] instrument(String classNameWithDots) {
        ClassReader reader;
        try {
            reader = new ClassReader(classNameWithDots);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read " + classNameWithDots, e);
        }
        return instrument(reader, classNameWithDots);
    }

    private static byte[] instrument(ClassReader reader, String classNameWithDots) {
        // Mirrors what RuntimeInstrumentation does: COMPUTE_FRAMES is where a mismatch between
        // the two branches of the diamond blows up.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        ClassVisitor cv = new MethodCallReplacementClassAdapter(writer, classNameWithDots);
        ClassNode cn = new ClassNode();
        reader.accept(cn, ClassReader.SKIP_FRAMES);
        cn.accept(cv);
        return writer.toByteArray();
    }

    private static void assertVerifies(byte[] bytecode) {
        StringWriter messages = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(bytecode),
                MethodCallReplacementStackShapeTest.class.getClassLoader(), false,
                new PrintWriter(messages));
        Assertions.assertTrue(messages.toString().isEmpty(),
                "Instrumented class does not verify:\n" + messages);
    }

    /**
     * Generates {@code NEW java/net/URL; LDC; INVOKESPECIAL java/net/URL.<init>(String)V; RETURN},
     * ie a construction whose result is discarded and which has no DUP. javac cannot produce this
     * shape, ecj can.
     */
    private static byte[] generateDiscardedUrlConstruction() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "org/evosuite/runtime/instrumentation/NoDupUrlCaller", null, "java/lang/Object", null);

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "go", "()V", null,
                new String[]{"java/net/MalformedURLException"});
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, "java/net/URL");
        mv.visitLdcInsn("http://www.evosuite.org");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/net/URL", "<init>",
                "(Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Contains a Desktop.browse shaped instance call. Never executed, only instrumented.
     */
    public static class DesktopCaller {
        public void browse(java.awt.Desktop desktop, java.net.URI uri) throws java.io.IOException {
            desktop.browse(uri);
        }
    }

    /**
     * Contains a discarded construction in the shape javac emits (with a DUP).
     */
    public static class UrlCaller {
        public void go() throws java.net.MalformedURLException {
            new java.net.URL("http://www.evosuite.org");
        }
    }
}
