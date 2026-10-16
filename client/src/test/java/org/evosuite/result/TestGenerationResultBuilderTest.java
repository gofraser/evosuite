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
package org.evosuite.result;

import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.mutation.MutationPool;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestGenerationResultBuilderTest {

    private MutationPool pool;

    @BeforeEach
    void addMutants() {
        pool = MutationPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT());
        pool.clear();
        BytecodeInstruction instruction = Mockito.mock(BytecodeInstruction.class);
        Mockito.when(instruction.getLineNumber()).thenReturn(7);
        for (int i = 0; i < 3; i++) {
            pool.addMutation("Foo", "bar()V", "replace " + i, instruction, new InsnNode(Opcodes.NOP), new InsnList());
        }
    }

    @AfterEach
    void clearMutants() {
        pool.clear();
        // leave no state behind for the next test of this singleton
        TestGenerationResultBuilder.buildSuccessResult();
    }

    @SuppressWarnings("unchecked")
    private static Set<MutationInfo> builderUncoveredMutants() throws Exception {
        Field f = TestGenerationResultBuilder.class.getDeclaredField("uncoveredMutants");
        f.setAccessible(true);
        return (Set<MutationInfo>) f.get(TestGenerationResultBuilder.getInstance());
    }

    @Test
    void resultListsEveryMutantOfThePool() {
        TestGenerationResult<?> result = TestGenerationResultBuilder.buildSuccessResult();

        assertEquals(3, result.getUncoveredMutants().size());
    }

    @Test
    void builderDoesNotRebuildAMutationInfoPerMutantWhenItResets() throws Exception {
        TestGenerationResultBuilder.buildSuccessResult();

        // The reset after a result used to refill this with one MutationInfo per mutant, a copy
        // nothing reads until the next result, built while the heap was at its fullest.
        assertEquals(0, builderUncoveredMutants().size());
    }

    @Test
    void mutantsAddedAfterAResetAreStillReported() {
        TestGenerationResultBuilder.buildSuccessResult();
        pool.addMutation("Foo", "baz()V", "replace x", Mockito.mock(BytecodeInstruction.class),
                new InsnNode(Opcodes.NOP), new InsnList());

        TestGenerationResult<?> result = TestGenerationResultBuilder.buildSuccessResult();

        assertEquals(4, result.getUncoveredMutants().size());
    }
}
