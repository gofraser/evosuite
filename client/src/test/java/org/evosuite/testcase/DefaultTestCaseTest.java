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
package org.evosuite.testcase;

import org.evosuite.testcase.statements.ArrayStatement;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.ConstantValue;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Collections;

public class DefaultTestCaseTest {

    @Test
    public void testClone() {

        DefaultTestCase tc = new DefaultTestCase();
        DefaultTestCase clone = tc.clone();
        Assertions.assertNotSame(tc.statements, clone.statements);
    }

    @Test
    public void testCloneWithForwardReferenceInAssignmentStatement() {
        DefaultTestCase tc = new DefaultTestCase();

        ArrayStatement arrayStmt = new ArrayStatement(tc, int[].class, 1);
        tc.addStatement(arrayStmt);

        IntPrimitiveStatement valueBefore = new IntPrimitiveStatement(tc, 1);
        tc.addStatement(valueBefore);

        IntPrimitiveStatement valueAfter = new IntPrimitiveStatement(tc, 2);
        VariableReference forwardValue = tc.addStatement(valueAfter);

        ArrayReference arrayRef = (ArrayReference) arrayStmt.getReturnValue();
        ArrayIndex target = new ArrayIndex(tc, arrayRef, Collections.singletonList(0));
        AssignmentStatement assignment = new AssignmentStatement(tc, target, forwardValue);
        tc.addStatement(assignment, 2);

        DefaultTestCase clone = tc.clone();
        Assertions.assertEquals(tc.size(), clone.size());
        Assertions.assertDoesNotThrow(() -> clone.toCode());
    }

    @Test
    public void testHasReferencesIgnoresUnboundConstantComparison() {
        DefaultTestCase tc = new DefaultTestCase();
        VariableReference target = tc.addStatement(new IntPrimitiveStatement(tc, 1));

        ConstantValue unboundConstant = new ConstantValue(tc, GenericClassFactory.get(int.class), 7);
        Statement mockStatement = Mockito.mock(Statement.class);
        Mockito.when(mockStatement.getVariableReferences())
                .thenReturn(Collections.singleton(unboundConstant));
        tc.statements.add(mockStatement);

        Assertions.assertDoesNotThrow(() -> tc.hasReferences(target));
        Assertions.assertFalse(tc.hasReferences(target));
    }

    @Test
    public void testGetObjectsHandlesMultiDimensionalArrayIndexAssignments() {
        DefaultTestCase tc = new DefaultTestCase();

        ArrayStatement arrayStmt = new ArrayStatement(tc, String[][].class, new int[]{2, 2});
        tc.addStatement(arrayStmt);

        StringPrimitiveStatement value = new StringPrimitiveStatement(tc, "x");
        VariableReference valueRef = tc.addStatement(value);

        ArrayReference arrayRef = (ArrayReference) arrayStmt.getReturnValue();
        ArrayIndex nestedIndex = new ArrayIndex(tc, arrayRef, Arrays.asList(0, 1));
        tc.addStatement(new AssignmentStatement(tc, nestedIndex, valueRef));

        Assertions.assertDoesNotThrow(() -> tc.getObjects(Object.class, tc.size()));
        Assertions.assertTrue(arrayRef.isInitialized(0, tc.size()));
    }
}
