/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.graphs.cfg;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class BasicBlockTest {

    @Test
    public void testIdDeterminism() {
        String className = "TestClass";
        String methodName = "testMethod";
        ClassLoader cl = getClass().getClassLoader();

        BytecodeInstruction i1 = new BytecodeInstruction(cl, className, methodName, 10, 0, new InsnNode(Opcodes.NOP));
        List<BytecodeInstruction> instructions1 = new ArrayList<>();
        instructions1.add(i1);

        // Create first block
        BasicBlock b1 = new BasicBlock(cl, className, methodName, instructions1);

        // Create second block with same content
        BytecodeInstruction i2 = new BytecodeInstruction(cl, className, methodName, 10, 0, new InsnNode(Opcodes.NOP));
        List<BytecodeInstruction> instructions2 = new ArrayList<>();
        instructions2.add(i2);
        BasicBlock b2 = new BasicBlock(cl, className, methodName, instructions2);

        // Now they should be equal because they have the same ID (10) derived from instruction ID.
        // Note: BasicBlock.equals() compares ID, className, methodName, and instructions list.
        // The instructions list contains BytecodeInstruction objects.
        // BytecodeInstruction.equals() compares className, methodName, instructionId.
        // So instructions lists should be equal.
        assertEquals(b1, b2);
        assertEquals(b1.hashCode(), b2.hashCode());
    }

    @Test
    public void testEntryExitBlock() {
        EntryBlock entry = new EntryBlock("C", "m");
        ExitBlock exit = new ExitBlock("C", "m");

        // They have different IDs (-1 vs -2) and different isEntry/ExitBlock flags.
        assertNotEquals(entry, exit);
    }
}
