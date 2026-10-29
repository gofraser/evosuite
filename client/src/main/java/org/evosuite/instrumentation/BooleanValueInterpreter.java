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

import org.evosuite.instrumentation.testability.BooleanTestabilityTransformation;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;

import java.util.List;

/**
 * An interpreter that determines which values are real Booleans.
 *
 * @author Gordon Fraser
 */
public class BooleanValueInterpreter extends BasicInterpreter {

    public static final BasicValue BOOLEAN_VALUE = new BasicValue(Type.BOOLEAN_TYPE);

    public static final BasicValue BOOLEAN_ARRAY = new BasicValue(Type.getType("[Z"));

    private final boolean isStatic;

    private final Type[] types;

    public BooleanValueInterpreter(String desc, boolean isStatic) {
        super(ASM9);
        this.types = Type.getArgumentTypes(desc);
        this.isStatic = isStatic;
    }

    @Override
    public BasicValue newValue(final Type type) {
        if (type == null) {
            return BasicValue.UNINITIALIZED_VALUE;
        }
        switch (type.getSort()) {
            case Type.BOOLEAN:
                return BOOLEAN_VALUE;
            case Type.ARRAY:
                if (type.getElementType() == Type.BOOLEAN_TYPE) {
                    return BOOLEAN_ARRAY;
                }
                // fall through
            default:
                return super.newValue(type);
        }
    }

    @Override
    public BasicValue unaryOperation(AbstractInsnNode insn, BasicValue value)
            throws AnalyzerException {
        if (insn.getOpcode() == Opcodes.INSTANCEOF) {
            return BOOLEAN_VALUE;
        } else if (insn.getOpcode() == Opcodes.GETFIELD) {
            FieldInsnNode fieldNode = (FieldInsnNode) insn;
            if (BooleanTestabilityTransformation.isTransformedField(fieldNode.owner,
                    fieldNode.name,
                    fieldNode.desc)) {
                return BOOLEAN_VALUE;
            }
        }
        return super.unaryOperation(insn, value);
    }

    @Override
    public BasicValue newOperation(AbstractInsnNode insn) throws AnalyzerException {
        if (insn.getOpcode() == ICONST_0) {
            return BOOLEAN_VALUE;
        } else if (insn.getOpcode() == ICONST_1) {
            return BOOLEAN_VALUE;
        } else if (insn.getOpcode() == Opcodes.GETSTATIC) {
            FieldInsnNode fieldNode = (FieldInsnNode) insn;
            if (BooleanTestabilityTransformation.isTransformedField(fieldNode.owner,
                    fieldNode.name,
                    fieldNode.desc)) {
                return BOOLEAN_VALUE;
            }

        }
        return super.newOperation(insn);
    }

    @Override
    public BasicValue binaryOperation(AbstractInsnNode insn, BasicValue value1,
                                      BasicValue value2) throws AnalyzerException {
        switch (insn.getOpcode()) {
            case IALOAD:
            case BALOAD:
            case CALOAD:
            case SALOAD:
                if (value1 == BOOLEAN_ARRAY) {
                    return BOOLEAN_VALUE;
                }
                break;
            default:
                break;
        }
        return super.binaryOperation(insn, value1, value2);
    }

    @Override
    public BasicValue copyOperation(AbstractInsnNode insn, BasicValue value)
            throws AnalyzerException {
        if (insn.getOpcode() == Opcodes.ILOAD) {
            VarInsnNode varNode = (VarInsnNode) insn;
            if (isStatic) {
                if (varNode.var < types.length) {
                    if (types[varNode.var] == Type.BOOLEAN_TYPE) {
                        return BOOLEAN_VALUE;
                    }
                }
            } else {
                if (varNode.var > 0 && varNode.var - 1 < types.length) {
                    if (types[varNode.var - 1] == Type.BOOLEAN_TYPE) {
                        return BOOLEAN_VALUE;
                    }
                }
            }
        }
        return super.copyOperation(insn, value);
    }

    @SuppressWarnings("rawtypes")
    @Override
    public BasicValue naryOperation(AbstractInsnNode insn, List values)
            throws AnalyzerException {
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode mi = (MethodInsnNode) insn;
            if (Type.getReturnType(BooleanTestabilityTransformation.getOriginalDesc(mi.owner,
                    mi.name,
                    mi.desc)) == Type.BOOLEAN_TYPE) {
                return BOOLEAN_VALUE;
            }

        }
        return super.naryOperation(insn, values);
    }

    @Override
    public BasicValue merge(BasicValue v, BasicValue w) {
        if (v == BOOLEAN_VALUE && w == BasicValue.INT_VALUE) {
            return BasicValue.INT_VALUE;
        } else if (w == BOOLEAN_VALUE && v == BasicValue.INT_VALUE) {
            return BasicValue.INT_VALUE;
        } else {
            return super.merge(v, w);
        }
    }

}
