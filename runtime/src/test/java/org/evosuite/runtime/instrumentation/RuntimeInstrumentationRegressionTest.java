/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.runtime.instrumentation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

public class RuntimeInstrumentationRegressionTest {

    @Test
    public void testInstrumentationFailureThrowsInsteadOfReturningPartialBytecode() {
        RuntimeInstrumentation instrumentation = new ThrowingRuntimeInstrumentation();
        ClassReader reader = new ClassReader(createSimpleClassBytes("sample/Bug8"));

        IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class,
                () -> instrumentation.transformBytes(getClass().getClassLoader(), "sample/Bug8", reader, false));
        Assertions.assertTrue(ex.getMessage().contains("sample/Bug8"));
        Assertions.assertNotNull(ex.getCause());
    }

    private static byte[] createSimpleClassBytes(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static class ThrowingRuntimeInstrumentation extends RuntimeInstrumentation {
        @Override
        protected void applyClassVisitor(ClassNode classNode, ClassVisitor classVisitor) {
            throw new RuntimeException("forced instrumentation failure");
        }
    }
}
