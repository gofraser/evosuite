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
import org.evosuite.coverage.dataflow.DefUseCoverageTestFitness.DefUsePairType;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The goals are sent from the client to the master, where the pool is empty and the definition and
 * the use cannot be looked up, but the goals still have to be told apart and printed.
 */
public class DefUseCoverageTestFitnessSerializationTest {

    private static final String CLASS_NAME = "Class1";
    private static final String METHOD_NAME = "method1";

    private ClassLoader classLoader;

    @BeforeEach
    public void setUp() {
        classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
        clearPools();
        RawControlFlowGraph cfg = mock(RawControlFlowGraph.class);
        when(cfg.getClassName()).thenReturn(CLASS_NAME);
        when(cfg.getMethodName()).thenReturn(METHOD_NAME);
        when(cfg.vertexSet()).thenReturn(new HashSet<>());
        GraphPool.getInstance(classLoader).registerRawCFG(cfg);
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

    private BytecodeInstruction instruction(int opcode, int id, int slot) {
        BasicBlock block = mock(BasicBlock.class);
        when(block.getClassName()).thenReturn(CLASS_NAME);
        when(block.getMethodName()).thenReturn(METHOD_NAME);
        when(block.getControlDependencies()).thenReturn(Collections.emptySet());
        when(block.getControlDependentBranchIds()).thenReturn(Collections.emptySet());
        BytecodeInstruction instruction = new BytecodeInstruction(classLoader, CLASS_NAME, METHOD_NAME, id, id,
                new VarInsnNode(opcode, slot), -1, block);
        BytecodeInstructionPool.getInstance(classLoader).registerInstruction(instruction);
        return instruction;
    }

    private DefUseCoverageTestFitness createGoal(int id, int slot) {
        BytecodeInstruction def = instruction(Opcodes.ISTORE, id, slot);
        BytecodeInstruction use = instruction(Opcodes.ILOAD, id + 1, slot);
        DefUsePool.addAsDefinition(def);
        DefUsePool.addAsUse(use);
        return new DefUseCoverageTestFitness(DefUsePool.getDefinitionByInstruction(def),
                DefUsePool.getUseByInstruction(use), DefUsePairType.INTRA_METHOD);
    }

    private static byte[] serialize(Object o) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(o);
        }
        return bytes.toByteArray();
    }

    private static DefUseCoverageTestFitness deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (DefUseCoverageTestFitness) in.readObject();
        }
    }

    @Test
    public void testSerializedGoalIsRestoredFromPool() throws Exception {
        DefUseCoverageTestFitness goal = createGoal(10, 1);

        DefUseCoverageTestFitness restored = deserialize(serialize(goal));

        assertEquals(goal, restored);
        assertEquals(goal.hashCode(), restored.hashCode());
        assertEquals(goal.toString(), restored.toString());
    }

    @Test
    public void testGoalRestoredWithoutPoolKeepsIdentity() throws Exception {
        DefUseCoverageTestFitness goal = createGoal(10, 1);
        byte[] bytes = serialize(goal);
        DefUsePool.clear();

        DefUseCoverageTestFitness first = deserialize(bytes);
        DefUseCoverageTestFitness second = deserialize(bytes);

        assertEquals(goal.toString(), first.toString());
        assertEquals(CLASS_NAME, first.getTargetClass());
        assertEquals(METHOD_NAME, first.getTargetMethod());
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(0, first.compareTo(second));
        assertEquals(1, new HashSet<>(Arrays.asList(first, second)).size());
    }

    @Test
    public void testGoalsOfDifferentPairsDifferWithoutPool() throws Exception {
        byte[] bytes = serialize(createGoal(10, 1));
        byte[] otherBytes = serialize(createGoal(20, 2));
        DefUsePool.clear();

        DefUseCoverageTestFitness restored = deserialize(bytes);
        DefUseCoverageTestFitness otherRestored = deserialize(otherBytes);

        assertNotEquals(restored, otherRestored);
        assertNotEquals(0, restored.compareTo(otherRestored));
        assertEquals(2, new HashSet<>(Arrays.asList(restored, otherRestored)).size());
    }
}
