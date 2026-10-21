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

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Instrumentation.retransformClasses} rejects any change of class or method modifiers
 * ("attempted to change method modifiers"), and one rejected class fails the whole batch, which
 * leaves every class in it uninstrumented. In retransforming mode the instrumentation must
 * therefore keep {@code final} where the class declares it, while an initial load still removes
 * it so that the class can be mocked.
 */
public class RetransformationModifiersTest {

    @Test
    public void retransformationKeepsFinalClassAndMethodModifiers() {
        RuntimeInstrumentation instrumentation = new RuntimeInstrumentation();
        instrumentation.setRetransformingMode(true);

        byte[] transformed = instrumentation.transformBytes(getClass().getClassLoader(),
                "sample/FinalSut", new ClassReader(finalClassBytes("sample/FinalSut")), false);

        assertTrue((new ClassReader(transformed).getAccess() & Opcodes.ACC_FINAL) != 0,
                "retransformation must not change the class modifiers; the JVM rejects the whole batch");
        assertTrue((method(transformed, "close").access & Opcodes.ACC_FINAL) != 0,
                "retransformation must not change the method modifiers; the JVM rejects the whole batch");
    }

    @Test
    public void initialLoadStillRemovesFinal() {
        RuntimeInstrumentation instrumentation = new RuntimeInstrumentation();

        byte[] transformed = instrumentation.transformBytes(getClass().getClassLoader(),
                "sample/FinalSut", new ClassReader(finalClassBytes("sample/FinalSut")), false);

        assertEquals(0, new ClassReader(transformed).getAccess() & Opcodes.ACC_FINAL);
        assertEquals(0, method(transformed, "close").access & Opcodes.ACC_FINAL);
    }

    private static MethodNode method(byte[] bytes, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (MethodNode method : node.methods) {
            if (name.equals(method.name)) {
                return method;
            }
        }
        throw new AssertionError("no method " + name);
    }

    private static byte[] finalClassBytes(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                internalName, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor close = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "close", "()V",
                null, null);
        close.visitCode();
        close.visitInsn(Opcodes.RETURN);
        close.visitMaxs(0, 0);
        close.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
