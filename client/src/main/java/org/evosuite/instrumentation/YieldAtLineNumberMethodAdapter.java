/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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

package org.evosuite.instrumentation;

import org.evosuite.PackageInfo;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * <p>YieldAtLineNumberMethodAdapter class.</p>
 *
 * @author fraser
 */
public class YieldAtLineNumberMethodAdapter extends AbstractEvoMethodAdapter {

    /**
     * <p>Constructor for YieldAtLineNumberMethodAdapter.</p>
     *
     * @param mv         a {@link org.objectweb.asm.MethodVisitor} object.
     * @param access     a int.
     * @param className  a {@link java.lang.String} object.
     * @param methodName a {@link java.lang.String} object.
     * @param desc       a {@link java.lang.String} object.
     */
    public YieldAtLineNumberMethodAdapter(MethodVisitor mv, int access, String className,
                                          String methodName, String desc) {
        super(mv, access, className, methodName, desc);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitLineNumber(int line, Label start) {
        super.visitLineNumber(line, start);

        if (shouldSkip()) {
            return;
        }

        if (!isSuperCallDone) {
            return;
        }

        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                PackageInfo.getNameWithSlash(ExecutionTracer.class),
                "checkTimeout", "()V", false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitInsn(int opcode) {
        // ATHROW instrumentation runs even in <clinit>, matching the original behavior.
        if (opcode == Opcodes.ATHROW) {
            super.visitInsn(Opcodes.DUP);
            this.visitLdcInsn(className);
            this.visitLdcInsn(methodName);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    PackageInfo.getNameWithSlash(ExecutionTracer.class),
                    "exceptionThrown",
                    "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V", false);
        }
        super.visitInsn(opcode);
    }

    @Override
    protected int getExtraStackSlots() {
        return 2;
    }
}
