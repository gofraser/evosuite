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

package org.evosuite.instrumentation.mutation;

import org.evosuite.PackageInfo;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;

/**
 * Utility class for mutation operators.
 */
public class MutationUtils {

    private static final Logger logger = LoggerFactory.getLogger(MutationUtils.class);

    /**
     * Get the default value for a given type as an instruction.
     *
     * @param type a {@link org.objectweb.asm.Type} object.
     * @return a {@link org.objectweb.asm.tree.AbstractInsnNode} object.
     */
    public static AbstractInsnNode getDefault(Type type) {
        if (type.equals(Type.BOOLEAN_TYPE)) {
            return new LdcInsnNode(0);
        } else if (type.equals(Type.INT_TYPE)) {
            return new LdcInsnNode(0);
        } else if (type.equals(Type.BYTE_TYPE)) {
            return new LdcInsnNode(0);
        } else if (type.equals(Type.CHAR_TYPE)) {
            return new LdcInsnNode(0);
        } else if (type.equals(Type.DOUBLE_TYPE)) {
            return new LdcInsnNode(0.0);
        } else if (type.equals(Type.FLOAT_TYPE)) {
            return new LdcInsnNode(0.0F);
        } else if (type.equals(Type.LONG_TYPE)) {
            return new LdcInsnNode(0L);
        } else if (type.equals(Type.SHORT_TYPE)) {
            return new LdcInsnNode(0);
        } else if (type.equals(Type.VOID_TYPE)) {
            return new LabelNode();
        } else {
            return new InsnNode(Opcodes.ACONST_NULL);
        }
    }

    /**
     * Get the name of the variable accessed by the given instruction.
     *
     * @param mn   a {@link org.objectweb.asm.tree.MethodNode} object.
     * @param node a {@link org.objectweb.asm.tree.AbstractInsnNode} object.
     * @return a {@link java.lang.String} object.
     * @throws VariableNotFoundException if the variable cannot be found.
     */
    public static String getName(MethodNode mn, AbstractInsnNode node)
            throws VariableNotFoundException {
        if (node instanceof VarInsnNode) {
            LocalVariableNode var = getLocal(mn, node, ((VarInsnNode) node).var);
            return var.name;
        } else if (node instanceof FieldInsnNode) {
            return ((FieldInsnNode) node).name;
        } else if (node instanceof IincInsnNode) {
            IincInsnNode incNode = (IincInsnNode) node;
            LocalVariableNode var = getLocal(mn, node, incNode.var);
            return var.name;
        } else {
            throw new RuntimeException("Unknown variable node: " + node);
        }
    }

    /**
     * Find the local variable corresponding to the given index at the position of the instruction.
     *
     * @param mn    a {@link org.objectweb.asm.tree.MethodNode} object.
     * @param node  a {@link org.objectweb.asm.tree.AbstractInsnNode} object.
     * @param index a int.
     * @return a {@link org.objectweb.asm.tree.LocalVariableNode} object.
     * @throws VariableNotFoundException if the variable cannot be found.
     */
    public static LocalVariableNode getLocal(MethodNode mn, AbstractInsnNode node, int index)
            throws VariableNotFoundException {
        int currentId = mn.instructions.indexOf(node);
        for (Object v : mn.localVariables) {
            LocalVariableNode localVar = (LocalVariableNode) v;
            int startId = mn.instructions.indexOf(localVar.start);
            int endId = mn.instructions.indexOf(localVar.end);
            logger.debug("Checking " + localVar.index + " in scope " + startId + " - "
                    + endId);
            if (currentId >= startId && currentId <= endId && localVar.index == index)
                return localVar;
        }

        throw new VariableNotFoundException("Could not find local variable " + index
                + " at position " + currentId + ", have variables: "
                + mn.localVariables.size());
    }

    /**
     * Calculate the next free local variable index.
     *
     * @param mn a {@link org.objectweb.asm.tree.MethodNode} object.
     * @return a int.
     */
    public static int getNextIndex(MethodNode mn) {
        Iterator<LocalVariableNode> it = mn.localVariables.iterator();
        int max = 0;
        int next = 0;
        while (it.hasNext()) {
            LocalVariableNode var = it.next();
            int index = var.index;
            if (index >= max) {
                max = index;
                next = max + Type.getType(var.desc).getSize();
            }
        }
        if (next == 0)
            next = getNextIndexFromLoad(mn);
        return next;
    }

    private static int getNextIndexFromLoad(MethodNode mn) {
        Iterator<AbstractInsnNode> it = mn.instructions.iterator();
        int index = 0;
        while (it.hasNext()) {
            AbstractInsnNode node = it.next();
            if (node instanceof VarInsnNode) {
                VarInsnNode varNode = (VarInsnNode) node;
                int varIndex = varNode.var;
                switch (varNode.getOpcode()) {
                    case Opcodes.ALOAD:
                    case Opcodes.ILOAD:
                    case Opcodes.FLOAD:
                    case Opcodes.IALOAD:
                    case Opcodes.BALOAD:
                    case Opcodes.CALOAD:
                    case Opcodes.AALOAD:
                    case Opcodes.ASTORE:
                    case Opcodes.ISTORE:
                    case Opcodes.FSTORE:
                    case Opcodes.IASTORE:
                    case Opcodes.BASTORE:
                    case Opcodes.CASTORE:
                    case Opcodes.AASTORE:
                        index = Math.max(index, varIndex + 1);
                        break;
                    case Opcodes.DLOAD:
                    case Opcodes.DSTORE:
                    case Opcodes.LLOAD:
                    case Opcodes.LSTORE:
                    case Opcodes.DALOAD:
                    case Opcodes.DASTORE:
                    case Opcodes.LALOAD:
                    case Opcodes.LASTORE:
                        index = Math.max(index, varIndex + 2);
                        break;
                }
            }
        }

        return index;
    }

    /**
     * <p>
     * copy
     * </p>
     *
     * @param orig a {@link org.objectweb.asm.tree.InsnList} object.
     * @return a {@link org.objectweb.asm.tree.InsnList} object.
     */
    public static InsnList copy(InsnList orig) {
        Iterator<?> it = orig.iterator();
        InsnList copy = new InsnList();
        while (it.hasNext()) {
            AbstractInsnNode node = (AbstractInsnNode) it.next();

            if (node instanceof VarInsnNode) {
                VarInsnNode vn = (VarInsnNode) node;
                copy.add(new VarInsnNode(vn.getOpcode(), vn.var));
            } else if (node instanceof FieldInsnNode) {
                FieldInsnNode fn = (FieldInsnNode) node;
                copy.add(new FieldInsnNode(fn.getOpcode(), fn.owner, fn.name, fn.desc));
            } else if (node instanceof InsnNode) {
                if (node.getOpcode() != Opcodes.POP)
                    copy.add(new InsnNode(node.getOpcode()));
            } else if (node instanceof LdcInsnNode) {
                copy.add(new LdcInsnNode(((LdcInsnNode) node).cst));
            } else {
                throw new RuntimeException("Unexpected node type: " + node.getClass());
            }
        }
        return copy;
    }

    /**
     * Generates the instructions to cast a numerical value from one type to
     * another.
     *
     * @param from the type of the top stack value
     * @param to   the type into which this value must be cast.
     * @return a {@link org.objectweb.asm.tree.InsnList} object.
     */
    public static InsnList cast(final Type from, final Type to) {
        InsnList list = new InsnList();

        if (from != to) {
            if (from == Type.DOUBLE_TYPE) {
                if (to == Type.FLOAT_TYPE) {
                    list.add(new InsnNode(Opcodes.D2F));
                } else if (to == Type.LONG_TYPE) {
                    list.add(new InsnNode(Opcodes.D2L));
                } else {
                    list.add(new InsnNode(Opcodes.D2I));
                    list.add(cast(Type.INT_TYPE, to));
                }
            } else if (from == Type.FLOAT_TYPE) {
                if (to == Type.DOUBLE_TYPE) {
                    list.add(new InsnNode(Opcodes.F2D));
                } else if (to == Type.LONG_TYPE) {
                    list.add(new InsnNode(Opcodes.F2L));
                } else {
                    list.add(new InsnNode(Opcodes.F2I));
                    list.add(cast(Type.INT_TYPE, to));
                }
            } else if (from == Type.LONG_TYPE) {
                if (to == Type.DOUBLE_TYPE) {
                    list.add(new InsnNode(Opcodes.L2D));
                } else if (to == Type.FLOAT_TYPE) {
                    list.add(new InsnNode(Opcodes.L2F));
                } else {
                    list.add(new InsnNode(Opcodes.L2I));
                    list.add(cast(Type.INT_TYPE, to));
                }
            } else {
                if (to == Type.BYTE_TYPE) {
                    list.add(new InsnNode(Opcodes.I2B));
                } else if (to == Type.CHAR_TYPE) {
                    list.add(new InsnNode(Opcodes.I2C));
                } else if (to == Type.DOUBLE_TYPE) {
                    list.add(new InsnNode(Opcodes.I2D));
                } else if (to == Type.FLOAT_TYPE) {
                    list.add(new InsnNode(Opcodes.I2F));
                } else if (to == Type.LONG_TYPE) {
                    list.add(new InsnNode(Opcodes.I2L));
                } else if (to == Type.SHORT_TYPE) {
                    list.add(new InsnNode(Opcodes.I2S));
                }
            }
        }
        return list;
    }

    /**
     * <p>
     * getDistance
     * </p>
     *
     * @param val1 a double.
     * @param val2 a double.
     * @return a double.
     */
    public static double getDistance(double val1, double val2) {
        return val1 == val2 ? 1.0 : 0.0;
    }

    /**
     * <p>
     * getDistance
     * </p>
     *
     * @param obj1 a {@link java.lang.Object} object.
     * @param obj2 a {@link java.lang.Object} object.
     * @return a double.
     */
    public static double getDistance(Object obj1, Object obj2) {
        if (obj1 == obj2)
            return 1.0;
        else
            return 0.0;
    }

    /**
     * <p>
     * addPrimitiveDistanceCheck
     * </p>
     *
     * @param distance a {@link org.objectweb.asm.tree.InsnList} object.
     * @param type     a {@link org.objectweb.asm.Type} object.
     * @param mutant   a {@link org.objectweb.asm.tree.InsnList} object.
     */
    public static void addPrimitiveDistanceCheck(InsnList distance, Type type,
                                                 InsnList mutant) {
        distance.add(cast(type, Type.DOUBLE_TYPE));
        distance.add(copy(mutant));
        distance.add(cast(type, Type.DOUBLE_TYPE));
        distance.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                PackageInfo.getNameWithSlash(MutationUtils.class),
                "getDistance", "(DD)D", false));
    }

    /**
     * <p>
     * addReferenceDistanceCheck
     * </p>
     *
     * @param distance a {@link org.objectweb.asm.tree.InsnList} object.
     * @param type     a {@link org.objectweb.asm.Type} object.
     * @param mutant   a {@link org.objectweb.asm.tree.InsnList} object.
     */
    public static void addReferenceDistanceCheck(InsnList distance, Type type,
                                                 InsnList mutant) {
        distance.add(copy(mutant));
        distance.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                PackageInfo.getNameWithSlash(MutationUtils.class),
                "getDistance", "(Ljava/lang/Object;Ljava/lang/Object;)D", false));
    }
}
