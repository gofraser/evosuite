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

package org.evosuite.instrumentation.testability;

import org.evosuite.instrumentation.TransformationStatistics;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * <p>
 * StringTransformation class.
 * </p>
 *
 * @author fraser
 */
public class StringTransformation {

    private static final Logger logger = LoggerFactory.getLogger(StringTransformation.class);
    private static final String JAVA_LANG_STRING = "java/lang/String";
    private static final String JAVA_UTIL_REGEX_PATTERN = "java/util/regex/Pattern";
    private static final String JAVA_UTIL_REGEX_MATCHER = "java/util/regex/Matcher";

    private final ClassNode cn;

    /**
     * <p>
     * Constructor for StringTransformation.
     * </p>
     *
     * @param cn a {@link org.objectweb.asm.tree.ClassNode} object.
     */
    public StringTransformation(ClassNode cn) {
        this.cn = cn;
    }

    /**
     * <p>
     * transform.
     * </p>
     *
     * @return a {@link org.objectweb.asm.tree.ClassNode} object.
     */
    public ClassNode transform() {
        for (MethodNode mn : cn.methods) {
            if (transformMethod(mn)) {
                mn.maxStack++;
            }
        }

        return cn;
    }

    /**
     * Replace boolean-returning method calls on String classes.
     *
     * @param mn the method node to transform.
     */
    private boolean transformStrings(MethodNode mn) {
        logger.info("Current method: " + mn.name);
        boolean changed = false;
        AbstractInsnNode node = mn.instructions.getFirst();
        while (node != null) {
            AbstractInsnNode next = node.getNext();
            if (node instanceof MethodInsnNode) {
                MethodInsnNode min = (MethodInsnNode) node;
                if (min.owner.equals(JAVA_LANG_STRING)) {
                    if (min.name.equals("equals")) {
                        changed = true;
                        MethodInsnNode equalCheck = new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                Type.getInternalName(StringHelper.class),
                                "StringEquals",
                                Type.getMethodDescriptor(Type.INT_TYPE,
                                        Type.getType(String.class),
                                        Type.getType(Object.class)), false);
                        mn.instructions.insertBefore(node, equalCheck);
                        mn.instructions.remove(node);
                        TransformationStatistics.transformedStringComparison();

                    } else if (min.name.equals("equalsIgnoreCase")) {
                        changed = true;
                        MethodInsnNode equalCheck = new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                Type.getInternalName(StringHelper.class),
                                "StringEqualsIgnoreCase",
                                Type.getMethodDescriptor(Type.INT_TYPE,
                                        Type.getType(String.class),
                                        Type.getType(String.class)), false);
                        mn.instructions.insertBefore(node, equalCheck);
                        mn.instructions.remove(node);
                        TransformationStatistics.transformedStringComparison();

                    } else if (min.name.equals("startsWith")) {
                        changed = true;
                        if (min.desc.equals("(Ljava/lang/String;)Z")) {
                            mn.instructions.insertBefore(node, new InsnNode(
                                    Opcodes.ICONST_0));
                        }
                        MethodInsnNode equalCheck = new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                Type.getInternalName(StringHelper.class),
                                "StringStartsWith",
                                Type.getMethodDescriptor(Type.INT_TYPE,
                                        Type.getType(String.class),
                                        Type.getType(String.class),
                                        Type.INT_TYPE), false);
                        mn.instructions.insertBefore(node, equalCheck);
                        mn.instructions.remove(node);
                        TransformationStatistics.transformedStringComparison();

                    } else if (min.name.equals("endsWith")) {
                        changed = true;
                        MethodInsnNode equalCheck = new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                Type.getInternalName(StringHelper.class),
                                "StringEndsWith",
                                Type.getMethodDescriptor(Type.INT_TYPE,
                                        Type.getType(String.class),
                                        Type.getType(String.class)), false);
                        mn.instructions.insertBefore(node, equalCheck);
                        mn.instructions.remove(node);
                        TransformationStatistics.transformedStringComparison();

                    } else if (min.name.equals("isEmpty")) {
                        changed = true;
                        MethodInsnNode equalCheck = new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                Type.getInternalName(StringHelper.class),
                                "StringIsEmpty",
                                Type.getMethodDescriptor(Type.INT_TYPE,
                                        Type.getType(String.class)), false);
                        mn.instructions.insertBefore(node, equalCheck);
                        mn.instructions.remove(node);
                        TransformationStatistics.transformedStringComparison();
                    } else if (min.name.equals("matches")) {
                        changed = true;
                        MethodInsnNode equalCheck = new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                Type.getInternalName(StringHelper.class),
                                "StringMatches",
                                Type.getMethodDescriptor(Type.INT_TYPE,
                                        Type.getType(String.class),
                                        Type.getType(String.class)), false);
                        mn.instructions.insertBefore(node, equalCheck);
                        mn.instructions.remove(node);
                        TransformationStatistics.transformedStringComparison();
                    } else if (min.name.equals("regionMatches")) {
                        Type[] argumentTypes = Type.getArgumentTypes(min.desc);
                        if (argumentTypes.length == 4) {
                            changed = true;
                            MethodInsnNode equalCheck = new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    Type.getInternalName(StringHelper.class),
                                    "StringRegionMatches",
                                    Type.getMethodDescriptor(Type.INT_TYPE, Type.getType(String.class), Type.INT_TYPE,
                                            Type.getType(String.class), Type.INT_TYPE,
                                            Type.INT_TYPE), false);
                            mn.instructions.insertBefore(node, equalCheck);
                            mn.instructions.remove(node);
                            TransformationStatistics.transformedStringComparison();

                        } else if (argumentTypes.length == 5) {
                            changed = true;
                            MethodInsnNode equalCheck = new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    Type.getInternalName(StringHelper.class),
                                    "StringRegionMatches",
                                    Type.getMethodDescriptor(Type.INT_TYPE,
                                            Type.getType(String.class),
                                            Type.BOOLEAN_TYPE,
                                            Type.INT_TYPE,
                                            Type.getType(String.class),
                                            Type.INT_TYPE,
                                            Type.INT_TYPE), false);
                            mn.instructions.insertBefore(node, equalCheck);
                            mn.instructions.remove(node);
                            TransformationStatistics.transformedStringComparison();
                        }
                    }

                } else if (min.owner.equals(JAVA_UTIL_REGEX_PATTERN)) {
                    if (min.name.equals("matches")) {
                        changed = true;
                        MethodInsnNode equalCheck = new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                Type.getInternalName(StringHelper.class),
                                "StringMatchRegex",
                                Type.getMethodDescriptor(Type.INT_TYPE,
                                        Type.getType(String.class),
                                        Type.getType(CharSequence.class)), false);
                        mn.instructions.insertBefore(node, equalCheck);
                        mn.instructions.remove(node);
                    }
                } else if (min.owner.equals(JAVA_UTIL_REGEX_MATCHER)) {
                    if (min.name.equals("matches")) {
                        changed = true;
                        MethodInsnNode equalCheck = new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                Type.getInternalName(StringHelper.class),
                                "StringMatchRegex",
                                Type.getMethodDescriptor(Type.INT_TYPE,
                                        Type.getType(Matcher.class)), false);
                        mn.instructions.insertBefore(node, equalCheck);
                        mn.instructions.remove(node);
                    }
                }
            }
            node = next;
        }
        return changed;
    }

    private static boolean isStringMethod(AbstractInsnNode node) {
        if (node == null) {
            return false;
        }
        if (node.getOpcode() == Opcodes.INVOKESTATIC) {
            MethodInsnNode methodInsnNode = (MethodInsnNode) node;
            return methodInsnNode.owner.equals(Type.getInternalName(StringHelper.class))
                    && methodInsnNode.name.startsWith("String");
        }
        return false;
    }

    /**
     * <p>
     * transformMethod.
     * </p>
     *
     * @param mn a {@link org.objectweb.asm.tree.MethodNode} object.
     * @return a boolean.
     */
    public boolean transformMethod(MethodNode mn) {
        boolean changed = transformStrings(mn);
        if (changed) {
            try {
                mn.maxStack++;
                Analyzer a = new Analyzer(new StringBooleanInterpreter());
                a.analyze(cn.name, mn);
                Frame[] frames = a.getFrames();
                AbstractInsnNode[] insns = mn.instructions.toArray();
                Map<AbstractInsnNode, Frame> frameMap = new HashMap<>();
                for (int i = 0; i < frames.length; i++) {
                    if (frames[i] != null && i < insns.length) {
                        frameMap.put(insns[i], frames[i]);
                    }
                }
                AbstractInsnNode node = mn.instructions.getFirst();
                while (node != null) {
                    AbstractInsnNode next = node.getNext();
                    if (!frameMap.containsKey(node)) {
                        node = next;
                        continue;
                    }
                    Frame current = frameMap.get(node);
                    int size = current.getStackSize();
                    if (node.getOpcode() == Opcodes.IFNE) {
                        JumpInsnNode branch = (JumpInsnNode) node;
                        if (current.getStack(size - 1) == StringBooleanInterpreter.STRING_BOOLEAN
                                || isStringMethod(node.getPrevious())) {
                            logger.info("IFNE -> IFGT");
                            branch.setOpcode(Opcodes.IFGT);
                        }
                    } else if (node.getOpcode() == Opcodes.IFEQ) {
                        JumpInsnNode branch = (JumpInsnNode) node;
                        if (current.getStack(size - 1) == StringBooleanInterpreter.STRING_BOOLEAN
                                || isStringMethod(node.getPrevious())) {
                            logger.info("IFEQ -> IFLE");
                            branch.setOpcode(Opcodes.IFLE);
                        }
                    } else if (node.getOpcode() == Opcodes.IF_ICMPEQ) {
                        JumpInsnNode branch = (JumpInsnNode) node;
                        if (current.getStack(size - 2) == StringBooleanInterpreter.STRING_BOOLEAN
                                || isStringMethod(node.getPrevious().getPrevious())) {
                            if (node.getPrevious().getOpcode() == Opcodes.ICONST_0) {
                                branch.setOpcode(Opcodes.IFLE);
                                mn.instructions.remove(node.getPrevious());
                            } else if (node.getPrevious().getOpcode() == Opcodes.ICONST_1) {
                                branch.setOpcode(Opcodes.IFGT);
                                mn.instructions.remove(node.getPrevious());
                            }
                        }
                    } else if (node.getOpcode() == Opcodes.IF_ICMPNE) {
                        JumpInsnNode branch = (JumpInsnNode) node;
                        if (current.getStack(size - 2) == StringBooleanInterpreter.STRING_BOOLEAN
                                || isStringMethod(node.getPrevious().getPrevious())) {
                            if (node.getPrevious().getOpcode() == Opcodes.ICONST_0) {
                                branch.setOpcode(Opcodes.IFGT);
                                mn.instructions.remove(node.getPrevious());
                            } else if (node.getPrevious().getOpcode() == Opcodes.ICONST_1) {
                                branch.setOpcode(Opcodes.IFLE);
                                mn.instructions.remove(node.getPrevious());
                            }
                        }
                    } else if (node.getOpcode() == Opcodes.IRETURN) {
                        if (current.getStack(size - 1) == StringBooleanInterpreter.STRING_BOOLEAN
                                || isStringMethod(node.getPrevious())) {
                            logger.info("IFEQ -> IFLE");
                            MethodInsnNode n = new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    Type.getInternalName(BooleanHelper.class),
                                    "intToBoolean",
                                    Type.getMethodDescriptor(Type.BOOLEAN_TYPE,
                                            Type.INT_TYPE), false);

                            mn.instructions.insertBefore(node, n);
                        }
                    }
                    node = next;
                }
            } catch (Exception e) {
                logger.error("Error during string transformation", e);
                return changed;
            }
        }
        return changed;
    }
}
