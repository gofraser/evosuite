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

import org.evosuite.instrumentation.TransformationStatistics;
import org.evosuite.instrumentation.testability.BooleanHelper;
import org.evosuite.instrumentation.testability.BooleanTestabilityTransformation;
import org.evosuite.instrumentation.testability.DescriptorMapping;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * Transform IFEQ/IFNE to IFLE/IFGT for transformed Boolean variables.
 */
public class BooleanIfTransformer extends MethodNodeTransformer {


    private final BooleanTestabilityTransformation booleanTestabilityTransformation;

    /**
     * Constructor for BooleanIfTransformer.
     *
     * @param booleanTestabilityTransformation the boolean testability transformation.
     */
    public BooleanIfTransformer(
            BooleanTestabilityTransformation booleanTestabilityTransformation) {
        this.booleanTestabilityTransformation = booleanTestabilityTransformation;
    }

    /* (non-Javadoc)
     * @see org.evosuite.instrumentation.MethodNodeTransformer#transformJumpInsnNode(org.objectweb.asm.tree.MethodNode,
     * org.objectweb.asm.tree.JumpInsnNode)
     */
    @Override
    protected AbstractInsnNode transformJumpInsnNode(MethodNode mn,
                                                     JumpInsnNode jumpNode) {
        if (jumpNode.getOpcode() == Opcodes.IFNE) {
            if (this.booleanTestabilityTransformation.isBooleanOnStack(mn, jumpNode, 0)) {
                TransformationStatistics.transformedBooleanComparison();
                // BooleanTestabilityTransformation.logger.info("Changing IFNE");
                jumpNode.setOpcode(Opcodes.IFGT);
            } else {
                AbstractInsnNode insn = jumpNode.getPrevious();
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode mi = (MethodInsnNode) insn;
                    if (Type.getReturnType(DescriptorMapping.getInstance().getMethodDesc(mi.owner,
                            mi.name,
                            mi.desc)) == Type.BOOLEAN_TYPE) {
                        // BooleanTestabilityTransformation.logger.info("Changing IFNE");
                        jumpNode.setOpcode(Opcodes.IFGT);
                    }
                }
            }
        } else if (jumpNode.getOpcode() == Opcodes.IFEQ) {
            if (this.booleanTestabilityTransformation.isBooleanOnStack(mn, jumpNode, 0)) {
                TransformationStatistics.transformedBooleanComparison();
                // BooleanTestabilityTransformation.logger.info("Changing IFEQ");
                jumpNode.setOpcode(Opcodes.IFLE);
            } else {
                AbstractInsnNode insn = jumpNode.getPrevious();
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode mi = (MethodInsnNode) insn;
                    if (Type.getReturnType(BooleanTestabilityTransformation.getOriginalDesc(mi.owner,
                            mi.name, mi.desc)) == Type.BOOLEAN_TYPE) {
                        // BooleanTestabilityTransformation.logger.info("Changing IFEQ");
                        jumpNode.setOpcode(Opcodes.IFLE);
                    }
                }
            }
        } else if (jumpNode.getOpcode() == Opcodes.IF_ICMPEQ || jumpNode.getOpcode() == Opcodes.IF_ICMPNE) {
            if (this.booleanTestabilityTransformation.isBooleanOnStack(mn, jumpNode, 0)) {
                insertBooleanConversion(mn, jumpNode);
            }
        }
        return jumpNode;
    }

    private void insertBooleanConversion(MethodNode mn, JumpInsnNode jumpNode) {
        InsnList convert = new InsnList();
        convert.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                Type.getInternalName(BooleanHelper.class), "pushParameter",
                Type.getMethodDescriptor(Type.VOID_TYPE,
                        Type.INT_TYPE)));
        convert.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                Type.getInternalName(BooleanHelper.class), "pushParameter",
                Type.getMethodDescriptor(Type.VOID_TYPE,
                        Type.INT_TYPE)));
        convert.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                Type.getInternalName(BooleanHelper.class),
                "popParameterBooleanFromInt",
                Type.getMethodDescriptor(Type.BOOLEAN_TYPE)));
        convert.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                Type.getInternalName(BooleanHelper.class),
                "popParameterBooleanFromInt",
                Type.getMethodDescriptor(Type.BOOLEAN_TYPE)));
        mn.instructions.insertBefore(jumpNode, convert);
        TransformationStatistics.transformedBooleanComparison();
    }
}
