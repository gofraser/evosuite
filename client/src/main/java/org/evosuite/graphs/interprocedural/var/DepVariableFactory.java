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

package org.evosuite.graphs.interprocedural.var;

import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;

/**
 * Creates the {@link DepVariable} that matches the kind of a bytecode instruction.
 */
public class DepVariableFactory {
    /**
     * Creates the dependency variable that represents the value loaded by an instruction.
     *
     * @param ins the instruction to classify
     * @return a this, parameter, static field, instance field, array element or other variable
     */
    public static DepVariable createVariableInstance(BytecodeInstruction ins) {
        if (ins.loadsReferenceToThis()) {
            ThisVariable var = new ThisVariable(ins);
            var.setType(DepVariable.THIS);
            var.setName("this");

            return var;
        } else if (ins.isParameter()) {
            ParameterVariable var = new ParameterVariable(ins);
            var.setType(DepVariable.PARAMETER);
            var.setName(ins.getVariableName());

            return var;
        } else if (ins.getASMNode().getOpcode() == Opcodes.GETSTATIC) {
            StaticFieldVariable var = new StaticFieldVariable(ins);
            var.setType(DepVariable.STATIC_FIELD);
            var.setName(((FieldInsnNode) ins.getASMNode()).name);

            return var;
        } else if (ins.getASMNode().getOpcode() == Opcodes.GETFIELD) {
            InstanceFieldVariable var = new InstanceFieldVariable(ins);
            var.setType(DepVariable.INSTANCE_FIELD);
            var.setName(((FieldInsnNode) ins.getASMNode()).name);

            return var;
        } else if (ins.isArrayLoadInstruction()) {
            ArrayElementVariable var = new ArrayElementVariable(ins);
            var.setType(DepVariable.ARRAY_ELEMENT);
            var.setName("array index");

            return var;
        } else {
            OtherVariable var = new OtherVariable(ins);
            var.setType(DepVariable.OTHER);
            var.setName("$unknown");

            return var;
        }

    }

}
