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

package org.evosuite.instrumentation.testability.transformer;

import org.objectweb.asm.tree.*;

/**
 * <p>MethodNodeTransformer class.</p>
 *
 * @author Gordon Fraser
 */
public class MethodNodeTransformer {

    /**
     * <p>transform</p>
     *
     * @param mn a {@link org.objectweb.asm.tree.MethodNode} object.
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

    /**
     * <p>transformMethodInsnNode</p>
     *
     * @param mn         a {@link org.objectweb.asm.tree.MethodNode} object.
     * @param methodNode a {@link org.objectweb.asm.tree.MethodInsnNode} object.
     * @return a {@link org.objectweb.asm.tree.AbstractInsnNode} object.
     */
    protected AbstractInsnNode transformMethodInsnNode(MethodNode mn,
                                                       MethodInsnNode methodNode) {
        return methodNode;
    }

    /**
     * <p>transformVarInsnNode</p>
     *
     * @param mn      a {@link org.objectweb.asm.tree.MethodNode} object.
     * @param varNode a {@link org.objectweb.asm.tree.VarInsnNode} object.
     * @return a {@link org.objectweb.asm.tree.AbstractInsnNode} object.
     */
    protected AbstractInsnNode transformVarInsnNode(MethodNode mn, VarInsnNode varNode) {
        return varNode;
    }

    /**
     * <p>transformFieldInsnNode</p>
     *
     * @param mn        a {@link org.objectweb.asm.tree.MethodNode} object.
     * @param fieldNode a {@link org.objectweb.asm.tree.FieldInsnNode} object.
     * @return a {@link org.objectweb.asm.tree.AbstractInsnNode} object.
     */
    protected AbstractInsnNode transformFieldInsnNode(MethodNode mn,
                                                      FieldInsnNode fieldNode) {
        return fieldNode;
    }

    /**
     * <p>transformInsnNode</p>
     *
     * @param mn       a {@link org.objectweb.asm.tree.MethodNode} object.
     * @param insnNode a {@link org.objectweb.asm.tree.InsnNode} object.
     * @return a {@link org.objectweb.asm.tree.AbstractInsnNode} object.
     */
    protected AbstractInsnNode transformInsnNode(MethodNode mn, InsnNode insnNode) {
        return insnNode;
    }

    /**
     * <p>transformTypeInsnNode</p>
     *
     * @param mn       a {@link org.objectweb.asm.tree.MethodNode} object.
     * @param typeNode a {@link org.objectweb.asm.tree.TypeInsnNode} object.
     * @return a {@link org.objectweb.asm.tree.AbstractInsnNode} object.
     */
    protected AbstractInsnNode transformTypeInsnNode(MethodNode mn, TypeInsnNode typeNode) {
        return typeNode;
    }

    /**
     * <p>transformJumpInsnNode</p>
     *
     * @param mn       a {@link org.objectweb.asm.tree.MethodNode} object.
     * @param jumpNode a {@link org.objectweb.asm.tree.JumpInsnNode} object.
     * @return a {@link org.objectweb.asm.tree.AbstractInsnNode} object.
     */
    protected AbstractInsnNode transformJumpInsnNode(MethodNode mn, JumpInsnNode jumpNode) {
        return jumpNode;
    }

    /**
     * <p>transformLabelNode</p>
     *
     * @param mn        a {@link org.objectweb.asm.tree.MethodNode} object.
     * @param labelNode a {@link org.objectweb.asm.tree.LabelNode} object.
     * @return a {@link org.objectweb.asm.tree.AbstractInsnNode} object.
     */
    protected AbstractInsnNode transformLabelNode(MethodNode mn, LabelNode labelNode) {
        return labelNode;
    }

    /**
     * <p>transformIntInsnNode</p>
     *
     * @param mn          a {@link org.objectweb.asm.tree.MethodNode} object.
     * @param intInsnNode a {@link org.objectweb.asm.tree.IntInsnNode} object.
     * @return a {@link org.objectweb.asm.tree.AbstractInsnNode} object.
     */
    protected AbstractInsnNode transformIntInsnNode(MethodNode mn, IntInsnNode intInsnNode) {
        return intInsnNode;
    }

    /**
     * <p>transformMultiANewArrayInsnNode</p>
     *
     * @param mn            a {@link org.objectweb.asm.tree.MethodNode} object.
     * @param arrayInsnNode a {@link org.objectweb.asm.tree.MultiANewArrayInsnNode} object.
     * @return a {@link org.objectweb.asm.tree.AbstractInsnNode} object.
     */
    protected AbstractInsnNode transformMultiANewArrayInsnNode(MethodNode mn,
                                                               MultiANewArrayInsnNode arrayInsnNode) {
        return arrayInsnNode;
    }

}
