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
package org.evosuite.symbolic.vm.heap.symbolicHeapSection;

import org.evosuite.symbolic.expr.bv.IntegerConstant;
import org.evosuite.symbolic.expr.ref.ClassReferenceVariable;
import org.evosuite.symbolic.expr.ref.ReferenceExpression;
import org.evosuite.symbolic.expr.ref.array.ArrayConstant;
import org.evosuite.symbolic.expr.ref.array.ArrayVariable;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class LazyArraysImplTest {

    @Test
    void createsReferenceVariableForAnUnreadInputElement() {
        LazyArraysImpl heap = new LazyArraysImpl();
        ArrayVariable array = heap.createVariableArray(new Object[1], 1, "objects");
        IntegerConstant index = new IntegerConstant(0);
        ReferenceExpression fallback = new ClassReferenceVariable(Type.getType(Object.class), 2, "fallback", null);

        ClassReferenceVariable element = assertInstanceOf(ClassReferenceVariable.class,
                heap.arrayLoad(array, index, fallback));
        assertEquals(Type.getType(Object.class), element.getObjectType());
        assertEquals("objects_content_0", element.getName());
        assertSame(element, heap.arrayLoad(array, index, fallback));
    }

    @Test
    void retainsAnArrayEntryWhenItsReferenceIsInitialized() {
        LazyArraysImpl heap = new LazyArraysImpl();
        int[] values = {7};
        ArrayConstant array = heap.createConstantArray(Type.getType(int[].class), 1);
        IntegerConstant index = new IntegerConstant(0);
        IntegerConstant stored = new IntegerConstant(7);
        heap.arrayStore(values, array, index, stored);

        array.initializeReference(values);
        heap.initializeArrayReference(array);

        assertSame(stored, heap.arrayLoad(array, index, new IntegerConstant(0)));
        ArrayConstant sameArray = heap.createConstantArray(Type.getType(int[].class), 2);
        sameArray.initializeReference(values);
        assertEquals(array, sameArray);
    }
}
