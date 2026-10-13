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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code Instrumentation.retransformClasses} rejects any change of superclass or interfaces, and
 * one rejected class fails the whole batch. In retransforming mode the instrumentation must
 * therefore leave the superclass of a class extending a mocked JDK class (here {@link Thread})
 * alone, as it already does for the marker interface.
 */
public class RetransformationSuperclassSwapTest {

    private boolean mockJvmNonDeterminism;

    @BeforeEach
    public void setUp() {
        mockJvmNonDeterminism = RuntimeSettings.mockJVMNonDeterminism;
        RuntimeSettings.mockJVMNonDeterminism = true;
    }

    @AfterEach
    public void tearDown() {
        RuntimeSettings.mockJVMNonDeterminism = mockJvmNonDeterminism;
    }

    @Disabled("D3: retransformation still swaps a mocked superclass, which the JVM rejects"
            + " - see CLEANUP-PLAN.md")
    @Test
    public void retransformationKeepsTheSuperclassOfAThreadSubclass() {
        RuntimeInstrumentation instrumentation = new RuntimeInstrumentation();
        instrumentation.setRetransformingMode(true);
        ClassReader reader = new ClassReader(threadSubclassBytes("sample/ThreadChild"));

        byte[] transformed = instrumentation.transformBytes(
                getClass().getClassLoader(), "sample/ThreadChild", reader, false);

        assertEquals("java/lang/Thread", new ClassReader(transformed).getSuperName(),
                "retransformation must not change the superclass; the JVM rejects the whole batch");
        assertEquals("java/lang/Thread", superConstructorOwner(transformed),
                "the super constructor call must match the unchanged superclass");
    }

    private static String superConstructorOwner(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (MethodNode method : node.methods) {
            if (!"<init>".equals(method.name)) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn.getOpcode() == Opcodes.INVOKESPECIAL
                        && "<init>".equals(((MethodInsnNode) insn).name)) {
                    return ((MethodInsnNode) insn).owner;
                }
            }
        }
        return null;
    }

    @Test
    public void initialLoadStillSwapsTheSuperclassToItsMock() {
        RuntimeInstrumentation instrumentation = new RuntimeInstrumentation();
        ClassReader reader = new ClassReader(threadSubclassBytes("sample/ThreadChild"));

        byte[] transformed = instrumentation.transformBytes(
                getClass().getClassLoader(), "sample/ThreadChild", reader, false);

        assertEquals("org/evosuite/runtime/mock/java/lang/MockThread",
                new ClassReader(transformed).getSuperName());
    }

    private static byte[] threadSubclassBytes(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null,
                "java/lang/Thread", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Thread", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
