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

import org.evosuite.runtime.LoopCounter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public class LoopCounterMethodAdapterTest {

    @Test
    public void testSkipsInstrumentationWhenFrameContainsUninitializedValue() {
        MethodNode methodNode = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "m", "()V", null, null);
        LoopCounterMethodAdapter adapter = new LoopCounterMethodAdapter(methodNode, "sample/Bug",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "m", "()V");

        adapter.visitCode();
        Label newLabel = new Label();
        adapter.visitLabel(newLabel);
        adapter.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
        adapter.visitInsn(Opcodes.DUP);
        adapter.visitFrame(Opcodes.F_NEW, 0, new Object[]{}, 2, new Object[]{newLabel, newLabel});

        Label target = new Label();
        adapter.visitJumpInsn(Opcodes.GOTO, target);
        adapter.visitLabel(target);
        adapter.visitInsn(Opcodes.POP2);
        adapter.visitInsn(Opcodes.RETURN);
        adapter.visitMaxs(2, 0);
        adapter.visitEnd();

        Assertions.assertEquals(0, countLoopChecks(methodNode));
    }

    @Test
    public void testInstrumentsJumpWhenFrameHasNoUninitializedValues() {
        MethodNode methodNode = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "m", "()V", null, null);
        LoopCounterMethodAdapter adapter = new LoopCounterMethodAdapter(methodNode, "sample/Bug",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "m", "()V");

        adapter.visitCode();
        Label target = new Label();
        adapter.visitInsn(Opcodes.ICONST_0);
        adapter.visitJumpInsn(Opcodes.IFEQ, target);
        adapter.visitLabel(target);
        adapter.visitInsn(Opcodes.RETURN);
        adapter.visitMaxs(1, 0);
        adapter.visitEnd();

        Assertions.assertEquals(1, countLoopChecks(methodNode));
    }

    private int countLoopChecks(MethodNode methodNode) {
        String owner = Type.getInternalName(LoopCounter.class);
        int count = 0;
        for (AbstractInsnNode insn = methodNode.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode methodInsnNode = (MethodInsnNode) insn;
                if (owner.equals(methodInsnNode.owner)
                        && "checkLoop".equals(methodInsnNode.name)) {
                    count++;
                }
            }
        }
        return count;
    }
}
