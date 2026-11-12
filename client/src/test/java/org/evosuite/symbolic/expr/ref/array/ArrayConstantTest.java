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
package org.evosuite.symbolic.expr.ref.array;

import org.evosuite.symbolic.expr.ref.ReferenceExpression;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ArrayConstantTest {

    @Test
    void staysAHashMapKeyWhenItsReferenceIsInitialized() {
        ArrayConstant array = new ArrayConstant.IntegerArrayConstant(Type.getType(int[].class), 1);
        Map<ReferenceExpression, String> heap = new HashMap<>();
        heap.put(array, "contents");

        array.initializeReference(new int[]{7});

        assertEquals("contents", heap.get(array));
    }

    @Test
    void equalsAnotherConstantForTheSameConcreteArray() {
        int[] values = {7};
        ArrayConstant array = new ArrayConstant.IntegerArrayConstant(Type.getType(int[].class), 1);
        ArrayConstant sameArray = new ArrayConstant.IntegerArrayConstant(Type.getType(int[].class), 2);
        ArrayConstant otherArray = new ArrayConstant.IntegerArrayConstant(Type.getType(int[].class), 3);
        array.initializeReference(values);
        sameArray.initializeReference(values);
        otherArray.initializeReference(new int[]{7});

        assertEquals(array, sameArray);
        assertNotEquals(array, otherArray);
    }
}
