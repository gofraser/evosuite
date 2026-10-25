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
package org.evosuite.coverage.io.input;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;

import java.util.List;

public class InputCoverageFactoryTest {

    private static final String MISSING = "org.evosuite.NoSuchClassForGoals";

    private String targetClass;

    @BeforeEach
    public void setUp() {
        targetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = "";
        BytecodeInstructionPool.clearAll();
    }

    @AfterEach
    public void tearDown() {
        BytecodeInstructionPool.clearAll();
        Properties.TARGET_CLASS = targetClass;
    }

    @Test
    public void testClassThatCannotBeLoadedIsSkipped() {
        ClassLoader loader = TestGenerationContext.getInstance().getClassLoaderForSUT();
        BytecodeInstructionPool pool = BytecodeInstructionPool.getInstance(loader);
        pool.registerInstruction(new BytecodeInstruction(loader, MISSING, "foo()V", 0, 0,
                new InsnNode(Opcodes.NOP)));
        pool.registerInstruction(new BytecodeInstruction(loader, String.class.getName(), "foo()V", 0, 0,
                new InsnNode(Opcodes.NOP)));

        List<?> goals = new InputCoverageFactory().getCoverageGoals();

        Assertions.assertFalse(goals.isEmpty(), "goals of the loadable class are kept");
    }
}
