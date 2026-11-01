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

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * <p>BooleanArrayInterpreter class.</p>
 *
 * @author Gordon Fraser
 */
public class BooleanArrayInterpreter extends BasicInterpreter {

    protected static Logger logger = LoggerFactory.getLogger(BooleanArrayInterpreter.class);

    public static final BasicValue BYTE = new BasicValue(null);

    public static final BasicValue BOOLEAN = new BasicValue(null);

    public static final BasicValue BOOLEAN_ARRAY = new BasicValue(null);

    public static final BasicValue BYTE_ARRAY = new BasicValue(null);

    public static final BasicValue INT_ARRAY = new BasicValue(null);

    @Override
    public BasicValue newValue(Type type) {
        if (type == null) {
            return BasicValue.UNINITIALIZED_VALUE;
        }
        switch (type.getSort()) {
            case Type.ARRAY:
                String desc = type.getDescriptor();
                if (desc.equals("[Z")) {
                    return BOOLEAN_ARRAY;
                } else if (desc.equals("[I")) {
                    return INT_ARRAY;
                } else if (desc.equals("[B")) {
                    return BYTE_ARRAY;
                } else {
                    return super.newValue(type);
                }
            default:
                return super.newValue(type);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public BasicValue naryOperation(AbstractInsnNode insn, List values)
            throws AnalyzerException {
        if (insn.getOpcode() == INVOKESTATIC || insn.getOpcode() == INVOKEVIRTUAL
                || insn.getOpcode() == INVOKEINTERFACE) {
            MethodInsnNode mn = (MethodInsnNode) insn;

            if (Type.getReturnType(mn.desc).equals(Type.BOOLEAN_TYPE)) {
                return BOOLEAN;
            } else if (mn.desc.equals("[Z")) {
                return BOOLEAN_ARRAY;
            } else if (mn.desc.equals("[B")) {
                return BYTE_ARRAY;
            } else if (mn.desc.equals("[I")) {
                return INT_ARRAY;
            } else if (Type.getReturnType(mn.desc).equals(Type.BYTE_TYPE)) {
                return BYTE;
            } else {
                if (mn.name.equals("clone") && mn.owner.equals("[I")) {
                    return INT_ARRAY;
                } else if (mn.name.equals("clone") && mn.owner.equals("[Z")) {
                    return BOOLEAN_ARRAY;
                } else if (mn.name.equals("clone") && mn.owner.equals("[B")) {
                    return BYTE_ARRAY;
                } else {
                    return super.naryOperation(insn, values);
                }
            }
        } else {
            return super.naryOperation(insn, values);
        }
    }
}
