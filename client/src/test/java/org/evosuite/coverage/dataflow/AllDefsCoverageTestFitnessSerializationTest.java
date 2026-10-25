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
package org.evosuite.coverage.dataflow;

import org.evosuite.TestGenerationContext;
import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.cfg.BasicBlock;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.evosuite.graphs.cfg.RawControlFlowGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The goals are sent from the client to the master, but the definition they target refers to the
 * class loader of the client, which cannot be serialized.
 */
public class AllDefsCoverageTestFitnessSerializationTest {

    private static final String CLASS_NAME = "Class1";
    private static final String METHOD_NAME = "method1";

    private ClassLoader classLoader;

    @BeforeEach
    public void setUp() {
        classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
        clearPools();
    }

    @AfterEach
    public void tearDown() {
        clearPools();
    }

    private void clearPools() {
        DefUsePool.clear();
        DefUseCoverageFactory.clear();
        GraphPool.getInstance(classLoader).clear();
        BytecodeInstructionPool.getInstance(classLoader).clear();
    }

    private AllDefsCoverageTestFitness createGoal() {
        RawControlFlowGraph cfg = mock(RawControlFlowGraph.class);
        when(cfg.getClassName()).thenReturn(CLASS_NAME);
        when(cfg.getMethodName()).thenReturn(METHOD_NAME);
        when(cfg.vertexSet()).thenReturn(new HashSet<>());
        GraphPool.getInstance(classLoader).registerRawCFG(cfg);

        BasicBlock block = mock(BasicBlock.class);
        when(block.getClassName()).thenReturn(CLASS_NAME);
        when(block.getMethodName()).thenReturn(METHOD_NAME);
        when(block.getControlDependencies()).thenReturn(Collections.emptySet());
        when(block.getControlDependentBranchIds()).thenReturn(Collections.emptySet());

        BytecodeInstruction instruction = new BytecodeInstruction(classLoader, CLASS_NAME, METHOD_NAME, 10, 0,
                new VarInsnNode(Opcodes.ISTORE, 1), -1, block);
        BytecodeInstructionPool.getInstance(classLoader).registerInstruction(instruction);
        DefUsePool.addAsDefinition(instruction);
        Definition def = DefUsePool.getDefinitionByInstruction(instruction);
        assertNotNull(def);
        return new AllDefsCoverageTestFitness(def, Collections.emptyMap());
    }

    private static byte[] serialize(Object o) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(o);
        }
        return bytes.toByteArray();
    }

    private static Object deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return in.readObject();
        }
    }

    @Test
    public void testSerializedGoalIsRestoredFromPool() throws Exception {
        AllDefsCoverageTestFitness goal = createGoal();

        AllDefsCoverageTestFitness restored = (AllDefsCoverageTestFitness) deserialize(serialize(goal));

        assertEquals(goal, restored);
        assertEquals(CLASS_NAME, restored.getTargetClass());
    }

    @Test
    public void testGoalCanBeSentToSideWithoutPool() throws Exception {
        byte[] bytes = serialize(createGoal());
        DefUsePool.clear();

        assertNotNull(deserialize(bytes));
    }
}
