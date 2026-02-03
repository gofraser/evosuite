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
import org.objectweb.asm.tree.*;

/**
 * Replace signatures of all calls/field accesses on Booleans
 */
public class BooleanCallsTransformer extends MethodNodeTransformer {

    private final BooleanTestabilityTransformation booleanTestabilityTransformation;

    /**
     * @param booleanTestabilityTransformation
     */
    public BooleanCallsTransformer(
            BooleanTestabilityTransformation booleanTestabilityTransformation) {
        this.booleanTestabilityTransformation = booleanTestabilityTransformation;
    }

    /* (non-Javadoc)
     * @see org.evosuite.instrumentation.MethodNodeTransformer#transformMethodInsnNode(org.objectweb.asm.tree.MethodNode, org.objectweb.asm.tree.MethodInsnNode)
     */
    @Override
    protected AbstractInsnNode transformMethodInsnNode(MethodNode mn,
                                                       MethodInsnNode methodNode) {
        if (methodNode.owner.equals(Type.getInternalName(BooleanHelper.class)))
            return methodNode;

        methodNode.desc = this.booleanTestabilityTransformation.transformMethodDescriptor(methodNode.owner,
                methodNode.name, methodNode.desc);
        methodNode.name = DescriptorMapping.getInstance().getMethodName(methodNode.owner,
                methodNode.name,
                methodNode.desc);
        if (DescriptorMapping.getInstance().isBooleanMethod(methodNode.desc)) {
            BooleanTestabilityTransformation.logger.info("Method needs value transformation: " + methodNode.name);
            if (DescriptorMapping.getInstance().hasBooleanParameters(methodNode.desc)) {
                BooleanTestabilityTransformation.logger.info("Method needs parameter transformation: "
                        + methodNode.name);
                TransformationStatistics.transformBackToBooleanParameter();
                int firstBooleanParameterIndex = -1;
                Type[] types = Type.getArgumentTypes(methodNode.desc);
                for (int i = 0; i < types.length; i++) {
                    if (types[i].getDescriptor().equals("Z")) {
                        if (firstBooleanParameterIndex == -1) {
                            firstBooleanParameterIndex = i;
                            break;
                        }
                    }
                }
                if (firstBooleanParameterIndex != -1) {
                    int numOfPushs = types.length - 1 - firstBooleanParameterIndex;
                    //                        int numOfPushs = types.length - firstBooleanParameterIndex;

                    if (numOfPushs == 0) {
                        AbstractInsnNode prev = methodNode.getPrevious();
                        boolean isConstantBoolean = prev != null && (prev.getOpcode() == Opcodes.ICONST_1 || prev.getOpcode() == Opcodes.ICONST_0);

                        if (!isConstantBoolean) {
                            //the boolean parameter is the last parameter
                            MethodInsnNode booleanHelperInvoke = new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    Type.getInternalName(BooleanHelper.class),
                                    "intToBoolean",
                                    Type.getMethodDescriptor(Type.BOOLEAN_TYPE,
                                            Type.INT_TYPE));
                            mn.instructions.insertBefore(methodNode,
                                    booleanHelperInvoke);
                        }
                    } else {
                        InsnList insnlist = new InsnList();

                        for (int i = 0; i < numOfPushs; i++) {
                            Type type = types[types.length - 1 - i];
                            addPushParameter(insnlist, type);
                        }
                        for (int i = firstBooleanParameterIndex; i < types.length; i++) {
                            if (i == firstBooleanParameterIndex) {
                                MethodInsnNode booleanHelperInvoke = new MethodInsnNode(
                                        Opcodes.INVOKESTATIC,
                                        Type.getInternalName(BooleanHelper.class),
                                        "intToBoolean",
                                        Type.getMethodDescriptor(Type.BOOLEAN_TYPE,
                                                Type.INT_TYPE));
                                insnlist.add(booleanHelperInvoke);
                            } else {
                                addPopParameter(insnlist, types[i]);
                            }

                        }
                        mn.instructions.insertBefore(methodNode, insnlist);
                    }
                }
            }
            if (Type.getReturnType(methodNode.desc).equals(Type.BOOLEAN_TYPE)) {
                BooleanTestabilityTransformation.logger.info("Method needs return transformation: " + methodNode.name);
                TransformationStatistics.transformBackToBooleanParameter();
                MethodInsnNode n = new MethodInsnNode(Opcodes.INVOKESTATIC,
                        Type.getInternalName(BooleanHelper.class), "booleanToInt",
                        Type.getMethodDescriptor(Type.INT_TYPE,
                                Type.BOOLEAN_TYPE));
                mn.instructions.insert(methodNode, n);
                return n;
            }
        } else {
            BooleanTestabilityTransformation.logger.info("Method needs no transformation: " + methodNode.name);
        }

        // TODO: If this is a method that is not transformed, and it requires a Boolean parameter
        // then we need to convert this boolean back to an int
        // For example, we could use flow analysis to determine the point where the value is added to the stack
        // and insert a conversion function there
        return methodNode;
    }

    private void addPushParameter(InsnList insnList, Type type) {
        if (isPrimitiveOrWrapper(type)) {
             String desc = Type.getMethodDescriptor(Type.VOID_TYPE, type == Type.BOOLEAN_TYPE ? Type.INT_TYPE : type);
             insnList.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    Type.getInternalName(BooleanHelper.class),
                    "pushParameter",
                    desc));
        } else {
            insnList.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    Type.getInternalName(BooleanHelper.class),
                    "pushParameter",
                    Type.getMethodDescriptor(Type.VOID_TYPE,
                            Type.getType(Object.class))));
        }
    }

    private void addPopParameter(InsnList insnList, Type type) {
        String methodName;
        String desc = Type.getMethodDescriptor(type);
        boolean objectNeedCast = false;

        if (type == Type.BOOLEAN_TYPE) methodName = "popParameterBooleanFromInt";
        else if (type == Type.CHAR_TYPE) methodName = "popParameterChar";
        else if (type == Type.BYTE_TYPE) methodName = "popParameterByte";
        else if (type == Type.SHORT_TYPE) methodName = "popParameterShort";
        else if (type == Type.INT_TYPE) methodName = "popParameterInt";
        else if (type == Type.FLOAT_TYPE) methodName = "popParameterFloat";
        else if (type == Type.LONG_TYPE) methodName = "popParameterLong";
        else if (type == Type.DOUBLE_TYPE) methodName = "popParameterDouble";
        else {
            methodName = "popParameterObject";
            desc = Type.getMethodDescriptor(Type.getType(Object.class));
            objectNeedCast = true;
        }

        insnList.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                Type.getInternalName(BooleanHelper.class),
                methodName,
                desc));

        if (objectNeedCast) {
             insnList.add(new TypeInsnNode(
                    Opcodes.CHECKCAST,
                    type.getInternalName()));
        }
    }

    private boolean isPrimitiveOrWrapper(Type type) {
        int sort = type.getSort();
        return sort == Type.BOOLEAN || sort == Type.CHAR || sort == Type.BYTE
                || sort == Type.SHORT || sort == Type.INT || sort == Type.FLOAT
                || sort == Type.LONG || sort == Type.DOUBLE;
    }

    /* (non-Javadoc)
     * @see org.evosuite.instrumentation.MethodNodeTransformer#transformFieldInsnNode(org.objectweb.asm.tree.MethodNode, org.objectweb.asm.tree.FieldInsnNode)
     */
    @Override
    protected AbstractInsnNode transformFieldInsnNode(MethodNode mn,
                                                      FieldInsnNode fieldNode) {

        // TODO: If the field owner is not transformed, then convert this to a proper Boolean
        fieldNode.desc = this.booleanTestabilityTransformation.transformFieldDescriptor(fieldNode.owner, fieldNode.name,
                fieldNode.desc);

        // If after transformation the field is still Boolean, we need to convert
        if (Type.getType(fieldNode.desc).equals(Type.BOOLEAN_TYPE)) {
            if (fieldNode.getOpcode() == Opcodes.PUTFIELD
                    || fieldNode.getOpcode() == Opcodes.PUTSTATIC) {
                MethodInsnNode n = new MethodInsnNode(Opcodes.INVOKESTATIC,
                        Type.getInternalName(BooleanHelper.class), "intToBoolean",
                        Type.getMethodDescriptor(Type.BOOLEAN_TYPE,
                                Type.INT_TYPE));
                TransformationStatistics.transformBackToBooleanField();
                mn.instructions.insertBefore(fieldNode, n);
            } else {
                MethodInsnNode n = new MethodInsnNode(Opcodes.INVOKESTATIC,
                        Type.getInternalName(BooleanHelper.class), "booleanToInt",
                        Type.getMethodDescriptor(Type.INT_TYPE,
                                Type.BOOLEAN_TYPE));
                mn.instructions.insert(fieldNode, n);
                TransformationStatistics.transformBackToBooleanField();
                return n;
            }
        }
        return fieldNode;
    }
}
