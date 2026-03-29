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
import org.evosuite.testcase.execution.ExecutionTracer;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Instrument classes to keep track of method entry and exit.
 *
 * @author Gordon Fraser
 */
public class MethodEntryAdapter extends AbstractEvoMethodAdapter {

    private final String fullMethodName;
    private final int access;

    /**
     * <p>Constructor for MethodEntryAdapter.</p>
     *
     * @param mv         a {@link org.objectweb.asm.MethodVisitor} object.
     * @param access     a int.
     * @param className  a {@link java.lang.String} object.
     * @param methodName a {@link java.lang.String} object.
     * @param desc       a {@link java.lang.String} object.
     */
    public MethodEntryAdapter(MethodVisitor mv, int access, String className,
                              String methodName, String desc) {
        super(mv, access, className, methodName, desc);
        this.fullMethodName = methodName + desc;
        this.access = access;
    }

    /**
     * For constructors, insert an early {@code enteredMethod} call BEFORE
     * {@code super()}/{@code this()} so that the root branch is recorded
     * even if the super-constructor throws.  ASM's {@link org.objectweb.asm.commons.AdviceAdapter}
     * delays {@code onMethodEnter} until after the delegating constructor
     * call, which means an exception in {@code super()} would prevent the
     * method entry from ever being traced.
     *
     * <p>We pass {@code null} as the caller because {@code this} is not yet
     * initialized before {@code super()}.
     */
    @Override
    public void visitCode() {
        super.visitCode();
        if (!shouldSkip() && isConstructor) {
            mv.visitLdcInsn(className);
            mv.visitLdcInsn(fullMethodName);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    PackageInfo.getNameWithSlash(ExecutionTracer.class),
                    "enteredMethod",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V", false);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onMethodEnter() {
        if (shouldSkip()) {
            super.onMethodEnter();
            return;
        }

        // For constructors, the early enteredMethod call was already
        // inserted in visitCode() (before super()).  Skip the duplicate.
        if (!isConstructor) {
            mv.visitLdcInsn(className);
            mv.visitLdcInsn(fullMethodName);
            if ((access & Opcodes.ACC_STATIC) > 0) {
                mv.visitInsn(Opcodes.ACONST_NULL);
            } else {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
            }
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    PackageInfo.getNameWithSlash(ExecutionTracer.class),
                    "enteredMethod",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V", false);
        }

        super.onMethodEnter();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onMethodExit(int opcode) {
        if (shouldSkip()) {
            super.onMethodExit(opcode);
            return;
        }

        if (opcode != Opcodes.ATHROW) {
            mv.visitLdcInsn(className);
            mv.visitLdcInsn(fullMethodName);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    PackageInfo.getNameWithSlash(org.evosuite.testcase.execution.ExecutionTracer.class),
                    "leftMethod", "(Ljava/lang/String;Ljava/lang/String;)V", false);
        }
        super.onMethodExit(opcode);
    }

    @Override
    protected int getExtraStackSlots() {
        return 3;
    }
}
