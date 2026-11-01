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
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DepVariableDataTypeTest {

    /**
     * The instructions below have no CFG to resolve, so the opcode based inference is exercised
     * directly instead of through getDataType().
     */
    private static String inferredTypeOf(AbstractInsnNode node) throws Exception {
        BytecodeInstruction ins = new BytecodeInstruction(
                DepVariableDataTypeTest.class.getClassLoader(), "Foo", "bar()V", 0, 0, node);
        Method infer = DepVariable.class.getDeclaredMethod("inferType", String.class);
        infer.setAccessible(true);
        return (String) infer.invoke(new OtherVariable(ins), ins.getInstructionType());
    }

    @Test
    public void doubleToFloatProducesFloat() throws Exception {
        assertEquals("float", inferredTypeOf(new InsnNode(Opcodes.D2F)));
    }

    @Test
    public void shortPushProducesInt() throws Exception {
        assertEquals("int", inferredTypeOf(new IntInsnNode(Opcodes.SIPUSH, 1000)));
    }

    @Test
    public void bytePushProducesInt() throws Exception {
        assertEquals("int", inferredTypeOf(new IntInsnNode(Opcodes.BIPUSH, 7)));
    }
}
