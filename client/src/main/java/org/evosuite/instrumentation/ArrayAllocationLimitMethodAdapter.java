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
package org.evosuite.instrumentation;

import org.evosuite.PackageInfo;
import org.evosuite.Properties;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

import java.util.HashMap;
import java.util.Map;


/**
 * <p>ArrayAllocationLimitMethodAdapter class.</p>
 *
 * @author Gordon Fraser
 */
public class ArrayAllocationLimitMethodAdapter extends GeneratorAdapter {

    /**
     * <p>Constructor for ArrayAllocationLimitMethodAdapter.</p>
     *
     * @param mv         a {@link org.objectweb.asm.MethodVisitor} object.
     * @param className  a {@link java.lang.String} object.
     * @param methodName a {@link java.lang.String} object.
     * @param access     a int.
     * @param desc       a {@link java.lang.String} object.
     */
    public ArrayAllocationLimitMethodAdapter(MethodVisitor mv, String className,
                                             String methodName, int access, String desc) {
        super(Opcodes.ASM9, mv, access, methodName, desc);
    }

    /* (non-Javadoc)
     * @see org.objectweb.asm.MethodVisitor#visitIntInsn(int, int)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitIntInsn(int opcode, int operand) {
        if (opcode == Opcodes.NEWARRAY) {
            Label origTarget = new Label();
            visitInsn(Opcodes.DUP);
            visitFieldInsn(Opcodes.GETSTATIC, PackageInfo.getNameWithSlash(org.evosuite.Properties.class),
                    "ARRAY_LIMIT", "I");
            super.visitJumpInsn(Opcodes.IF_ICMPLT, origTarget);
            super.visitTypeInsn(Opcodes.NEW,
                    PackageInfo.getNameWithSlash(TestCaseExecutor.TimeoutExceeded.class));
            super.visitInsn(Opcodes.DUP);
            super.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    PackageInfo.getNameWithSlash(TestCaseExecutor.TimeoutExceeded.class),
                    "<init>", "()V", false);
            super.visitInsn(Opcodes.ATHROW);
            super.visitLabel(origTarget);

        }
        super.visitIntInsn(opcode, operand);
    }

    /* (non-Javadoc)
     * @see org.objectweb.asm.MethodVisitor#visitTypeInsn(int, java.lang.String)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitTypeInsn(int opcode, String type) {

        if (opcode == Opcodes.ANEWARRAY) {
            Label origTarget = new Label();
            visitInsn(Opcodes.DUP);
            visitFieldInsn(Opcodes.GETSTATIC, PackageInfo.getNameWithSlash(org.evosuite.Properties.class),
                    "ARRAY_LIMIT", "I");
            super.visitJumpInsn(Opcodes.IF_ICMPLT, origTarget);
            super.visitTypeInsn(Opcodes.NEW,
                    PackageInfo.getNameWithSlash(TestCaseExecutor.TimeoutExceeded.class));
            super.visitInsn(Opcodes.DUP);
            super.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    PackageInfo.getNameWithSlash(TestCaseExecutor.TimeoutExceeded.class),
                    "<init>", "()V", false);
            super.visitInsn(Opcodes.ATHROW);
            super.visitLabel(origTarget);

        }
        super.visitTypeInsn(opcode, type);
    }

    /**
     * Intercept calls to JDK factory methods that internally allocate large arrays,
     * bypassing the NEWARRAY/ANEWARRAY instrumentation. The size argument on the stack
     * is clamped to ARRAY_LIMIT before the call proceeds.
     */
    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                boolean isInterface) {
        if (shouldLimitSizeArg(opcode, owner, name, descriptor)) {
            // Stack: ..., int(size)  →  clamp size to ARRAY_LIMIT
            Label ok = new Label();
            visitInsn(Opcodes.DUP);
            visitFieldInsn(Opcodes.GETSTATIC, PackageInfo.getNameWithSlash(Properties.class),
                    "ARRAY_LIMIT", "I");
            super.visitJumpInsn(Opcodes.IF_ICMPLT, ok);
            // size >= ARRAY_LIMIT → throw TimeoutExceeded
            super.visitTypeInsn(Opcodes.NEW,
                    PackageInfo.getNameWithSlash(TestCaseExecutor.TimeoutExceeded.class));
            super.visitInsn(Opcodes.DUP);
            super.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    PackageInfo.getNameWithSlash(TestCaseExecutor.TimeoutExceeded.class),
                    "<init>", "()V", false);
            super.visitInsn(Opcodes.ATHROW);
            super.visitLabel(ok);
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    private static boolean shouldLimitSizeArg(int opcode, String owner, String name,
                                              String descriptor) {
        // ByteBuffer.allocate(int) and ByteBuffer.allocateDirect(int)
        if (opcode == Opcodes.INVOKESTATIC && "java/nio/ByteBuffer".equals(owner)
                && ("allocate".equals(name) || "allocateDirect".equals(name))
                && "(I)Ljava/nio/ByteBuffer;".equals(descriptor)) {
            return true;
        }
        // StringBuilder/StringBuffer initial capacity
        if ((opcode == Opcodes.INVOKESPECIAL)
                && ("java/lang/StringBuilder".equals(owner) || "java/lang/StringBuffer".equals(owner))
                && "<init>".equals(name) && "(I)V".equals(descriptor)) {
            return true;
        }
        return false;
    }

    /* (non-Javadoc)
     * @see org.objectweb.asm.MethodVisitor#visitMultiANewArrayInsn(java.lang.String, int)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitMultiANewArrayInsn(String desc, int dims) {

        Label origTarget = new Label();
        Label errorTarget = new Label();

        // Multidimensional arrays can only have max 256 dimensions
        if (Properties.ARRAY_LIMIT < 256) {
            push(dims);
            visitFieldInsn(Opcodes.GETSTATIC, PackageInfo.getNameWithSlash(org.evosuite.Properties.class),
                    "ARRAY_LIMIT", "I");
            super.visitJumpInsn(Opcodes.IF_ICMPGE, errorTarget);
        }

        // Check each of the dimensions
        Map<Integer, Integer> to = new HashMap<>();
        for (int i = dims - 1; i >= 0; i--) {
            int loc = newLocal(Type.INT_TYPE);
            storeLocal(loc);
            to.put(i, loc);
        }
        for (int i = 0; i < dims; i++) {
            loadLocal(to.get(i));
            visitFieldInsn(Opcodes.GETSTATIC, "org/evosuite/Properties",
                    "ARRAY_LIMIT", "I");
            super.visitJumpInsn(Opcodes.IF_ICMPGE, errorTarget);
        }

        // Check the product of all dimensions against ARRAY_LIMIT.
        // Individual dimensions may each be under the limit, but their
        // product can still cause OOM (e.g. new Float[50000][50000]).
        if (dims >= 2) {
            // Compute product as long to avoid int overflow
            loadLocal(to.get(0));
            visitInsn(Opcodes.I2L);
            for (int i = 1; i < dims; i++) {
                loadLocal(to.get(i));
                visitInsn(Opcodes.I2L);
                visitInsn(Opcodes.LMUL);
            }
            // Compare: product >= (long) ARRAY_LIMIT → reject
            visitFieldInsn(Opcodes.GETSTATIC, PackageInfo.getNameWithSlash(Properties.class),
                    "ARRAY_LIMIT", "I");
            visitInsn(Opcodes.I2L);
            visitInsn(Opcodes.LCMP);
            // LCMP pushes 1 if product > limit, 0 if equal, -1 if less
            super.visitJumpInsn(Opcodes.IFGE, errorTarget);
        }

        goTo(origTarget);
        super.visitLabel(errorTarget);
        super.visitTypeInsn(Opcodes.NEW,
                PackageInfo.getNameWithSlash(TestCaseExecutor.TimeoutExceeded.class));
        super.visitInsn(Opcodes.DUP);
        super.visitMethodInsn(Opcodes.INVOKESPECIAL,
                PackageInfo.getNameWithSlash(TestCaseExecutor.TimeoutExceeded.class),
                "<init>", "()V", false);
        super.visitInsn(Opcodes.ATHROW);
        super.visitLabel(origTarget);

        // Restore original dimensions
        for (int i = 0; i < dims; i++) {
            loadLocal(to.get(i));
        }

        super.visitMultiANewArrayInsn(desc, dims);
    }

}
