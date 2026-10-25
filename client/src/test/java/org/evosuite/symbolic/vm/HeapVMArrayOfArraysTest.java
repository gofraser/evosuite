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
package org.evosuite.symbolic.vm;

import org.evosuite.Properties;
import org.evosuite.symbolic.expr.bv.IntegerConstant;
import org.evosuite.symbolic.expr.ref.ReferenceExpression;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Stores into an array that was itself loaded from an array of arrays, as in
 * {@code maze[y][x] = 'X'}, under the select/store array memory model.
 */
public class HeapVMArrayOfArraysTest {

    private Properties.DseArraysMemoryModelVersion savedModel;

    @BeforeEach
    public void selectStoreModel() {
        savedModel = Properties.SELECTED_DseArraysMemoryModelVersion;
        Properties.SELECTED_DseArraysMemoryModelVersion = Properties.DseArraysMemoryModelVersion.SELECT_STORE_EXPRESSIONS;
    }

    @AfterEach
    public void restoreModel() {
        Properties.SELECTED_DseArraysMemoryModelVersion = savedModel;
    }

    @Test
    public void storeIntoRowLoadedFromArrayOfArrays() throws Exception {
        SymbolicEnvironment env = new SymbolicEnvironment(null);
        HeapVM vm = new HeapVM(env, new PathConditionCollector(), null);
        env.pushFrame(new MethodFrame(
                HeapVMArrayOfArraysTest.class.getDeclaredMethod("storeIntoRowLoadedFromArrayOfArrays"), 4));

        char[] row = new char[3];
        char[][] maze = new char[][]{row};

        // maze[0]
        env.topFrame().operandStack.pushRef(env.heap.getReference(maze));
        env.topFrame().operandStack.pushBv32(new IntegerConstant(0));
        vm.AALOAD(maze, 0, "C", "m");

        ReferenceExpression loadedRow = env.topFrame().operandStack.peekRef();
        assertNotNull(loadedRow);
        assertSame(env.heap.getReference(row), loadedRow);

        // maze[0][1] = 'X'
        env.topFrame().operandStack.pushBv32(new IntegerConstant(1));
        env.topFrame().operandStack.pushBv32(new IntegerConstant('X'));
        assertDoesNotThrow(() -> vm.CASTORE(row, 1, "C", "m"));
    }
}
