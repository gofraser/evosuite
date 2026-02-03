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
package org.evosuite.instrumentation.error;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Map;

public class NullPointerExceptionInstrumentation extends ErrorBranchInstrumenter {

    public NullPointerExceptionInstrumentation(ErrorConditionMethodAdapter mv) {
        super(mv);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name,
                                String desc, boolean itf) {

        // If non-static, add a null check
        if (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE ||
                (opcode == Opcodes.INVOKESPECIAL && !name.equals("<init>"))) {

            Map<Integer, Integer> tempVariables = getMethodCallee(desc);
            insertBranch(Opcodes.IFNONNULL, "java/lang/NullPointerException");
            restoreMethodParameters(tempVariables, desc);
        }
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
        // If non-static, add a null check

        if (opcode == Opcodes.GETFIELD) {
            mv.visitInsn(Opcodes.DUP);
            insertBranch(Opcodes.IFNONNULL, "java/lang/NullPointerException");

        } else if (opcode == Opcodes.PUTFIELD && !methodName.equals("<init>")) {
            // Stack: objectref, value
            if (Type.getType(desc).getSize() == 2) {
                // 2 words
                // v1 v2 v3 (v1=objectref, v2/v3=value)
                mv.visitInsn(Opcodes.DUP2_X1);
                // v2 v3 v1 v2 v3

                mv.visitInsn(Opcodes.POP2);
                // v2 v3 v1 (objectref on top)

                mv.visitInsn(Opcodes.DUP_X2);
                // v1 v2 v3 v1 (restore original stack, dup objectref on top)

            } else {
                // 1 word
                // v1 v2 (v1=objectref, v2=value)
                mv.visitInsn(Opcodes.DUP2);
                // v1 v2 v1 v2
                mv.visitInsn(Opcodes.POP);
                // v1 v2 v1
            }
            insertBranch(Opcodes.IFNONNULL, "java/lang/NullPointerException");
        }
    }
}
