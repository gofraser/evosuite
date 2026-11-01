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
package org.evosuite.instrumentation.testability.transformer;

import org.objectweb.asm.tree.*;

/**
 * <p>MethodNodeTransformer class.</p>
 *
 * @author Gordon Fraser
 */
public class MethodNodeTransformer {

    /**
     * Transforms the instructions of the given method node. Methods without instructions are left unchanged.
     */
    public void transform(MethodNode mn) {
        if (mn.instructions == null || mn.instructions.size() == 0) {
            return;
        }

        AbstractInsnNode node = mn.instructions.getFirst();

        boolean finished = false;
        while (!finished) {
            if (node instanceof MethodInsnNode) {
                node = transformMethodInsnNode(mn, (MethodInsnNode) node);
            } else if (node instanceof VarInsnNode) {
                node = transformVarInsnNode(mn, (VarInsnNode) node);
            } else if (node instanceof FieldInsnNode) {
                node = transformFieldInsnNode(mn, (FieldInsnNode) node);
            } else if (node instanceof InsnNode) {
                node = transformInsnNode(mn, (InsnNode) node);
            } else if (node instanceof TypeInsnNode) {
                node = transformTypeInsnNode(mn, (TypeInsnNode) node);
            } else if (node instanceof JumpInsnNode) {
                node = transformJumpInsnNode(mn, (JumpInsnNode) node);
            } else if (node instanceof LabelNode) {
                node = transformLabelNode(mn, (LabelNode) node);
            } else if (node instanceof IntInsnNode) {
                node = transformIntInsnNode(mn, (IntInsnNode) node);
            } else if (node instanceof MultiANewArrayInsnNode) {
                node = transformMultiANewArrayInsnNode(mn, (MultiANewArrayInsnNode) node);
            }

            if (node == mn.instructions.getLast()) {
                finished = true;
            } else {
                node = node.getNext();
            }
        }
    }

    protected AbstractInsnNode transformMethodInsnNode(MethodNode mn,
                                                       MethodInsnNode methodNode) {
        return methodNode;
    }

    protected AbstractInsnNode transformVarInsnNode(MethodNode mn, VarInsnNode varNode) {
        return varNode;
    }

    protected AbstractInsnNode transformFieldInsnNode(MethodNode mn,
                                                      FieldInsnNode fieldNode) {
        return fieldNode;
    }

    protected AbstractInsnNode transformInsnNode(MethodNode mn, InsnNode insnNode) {
        return insnNode;
    }

    protected AbstractInsnNode transformTypeInsnNode(MethodNode mn, TypeInsnNode typeNode) {
        return typeNode;
    }

    protected AbstractInsnNode transformJumpInsnNode(MethodNode mn, JumpInsnNode jumpNode) {
        return jumpNode;
    }

    protected AbstractInsnNode transformLabelNode(MethodNode mn, LabelNode labelNode) {
        return labelNode;
    }

    protected AbstractInsnNode transformIntInsnNode(MethodNode mn, IntInsnNode intInsnNode) {
        return intInsnNode;
    }

    protected AbstractInsnNode transformMultiANewArrayInsnNode(MethodNode mn,
                                                               MultiANewArrayInsnNode arrayInsnNode) {
        return arrayInsnNode;
    }

}
