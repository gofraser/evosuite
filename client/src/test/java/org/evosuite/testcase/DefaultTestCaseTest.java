/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.testcase;

import org.evosuite.contracts.ContractViolation;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.NullReference;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DefaultTestCaseTest {

    @Test
    public void testClone() {
        DefaultTestCase tc = new DefaultTestCase();
        DefaultTestCase clone = tc.clone();
        Assert.assertNotSame(tc.statements, clone.statements);
        // IDs are generated sequentially
        assertTrue(clone.getID() != tc.getID());
    }

    @Test
    public void testCloneCopiesAllFields() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.setUnstable(true);
        tc.setFailing(true);

        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        tc.addCoveredGoal(goal);

        ContractViolation violation = mock(ContractViolation.class);
        tc.addContractViolation(violation);

        ClassLoader classLoader = mock(ClassLoader.class);
        tc.changeClassLoader(classLoader);

        DefaultTestCase clone = tc.clone();

        assertTrue("Clone should be unstable", clone.isUnstable());
        assertTrue("Clone should be failing", clone.isFailing());
        assertTrue("Clone should contain covered goal", clone.getCoveredGoals().contains(goal));
        assertTrue("Clone should contain contract violation", clone.getContractViolations().contains(violation));
        assertEquals("Clone should copy changedClassLoader", classLoader, clone.getChangedClassLoader());

        // Verify independent collections
        assertNotSame("Covered goals collection should be deep copied", tc.getCoveredGoals(), clone.getCoveredGoals());
        assertNotSame("Contract violations collection should be deep copied", tc.getContractViolations(), clone.getContractViolations());
    }

    @Test
    public void testGetObjects() {
        DefaultTestCase tc = new DefaultTestCase();
        // Mock a statement that returns a String
        Statement st = mock(Statement.class);
        VariableReference var = mock(VariableReference.class);
        when(st.isValid()).thenReturn(true);
        when(st.getReturnValue()).thenReturn(var);
        // DefaultTestCase.getObjects checks:
        // 1. value != null
        // 2. value instanceof ArrayReference
        // 3. value instanceof ArrayIndex
        // 4. assignability and addFields
        when(var.isAssignableTo(String.class)).thenReturn(true);
        when(var.getVariableClass()).thenReturn((Class) String.class);

        tc.addStatement(st);

        List<VariableReference> objects = tc.getObjects(String.class, 1);
        assertEquals(1, objects.size());
        assertEquals(var, objects.get(0));
    }

    @Test
    public void testGetRandomObjectFilters() throws ConstructionFailedException {
        DefaultTestCase tc = new DefaultTestCase();

        // 1. Primitive (Index 0)
        PrimitiveStatement primitiveStmt = mock(PrimitiveStatement.class);
        VariableReference primVar = mock(VariableReference.class);
        when(primitiveStmt.isValid()).thenReturn(true);
        when(primitiveStmt.getReturnValue()).thenReturn(primVar);
        when(primVar.getStPosition()).thenReturn(0);
        when(primVar.isPrimitive()).thenReturn(true);
        when(primVar.isAssignableTo(Object.class)).thenReturn(true);
        when(primVar.getVariableClass()).thenReturn((Class) Integer.class);
        tc.addStatement(primitiveStmt);

        // 2. Null (Index 1)
        NullStatement nullStmt = mock(NullStatement.class);
        NullReference nullVar = mock(NullReference.class); // Mocking NullReference specifically
        when(nullStmt.isValid()).thenReturn(true);
        when(nullStmt.getReturnValue()).thenReturn(nullVar);
        when(nullVar.getStPosition()).thenReturn(1);
        when(nullVar.isAssignableTo(Object.class)).thenReturn(true);
        // Ensure instanceof NullReference works (it will because we mocked NullReference class)
        tc.addStatement(nullStmt);

        // 3. FunctionalMock (Index 2)
        FunctionalMockStatement mockStmt = mock(FunctionalMockStatement.class);
        VariableReference mockVar = mock(VariableReference.class);
        when(mockStmt.isValid()).thenReturn(true);
        when(mockStmt.getReturnValue()).thenReturn(mockVar);
        when(mockVar.getStPosition()).thenReturn(2);
        when(mockVar.isAssignableTo(Object.class)).thenReturn(true);
        when(mockVar.getVariableClass()).thenReturn((Class) Object.class);
        tc.addStatement(mockStmt);

        // 4. Valid Object (Index 3)
        ConstructorStatement validStmt = mock(ConstructorStatement.class);
        VariableReference validVar = mock(VariableReference.class);
        when(validStmt.isValid()).thenReturn(true);
        when(validStmt.getReturnValue()).thenReturn(validVar);
        when(validVar.getStPosition()).thenReturn(3);
        when(validVar.isAssignableTo(Object.class)).thenReturn(true);
        when(validVar.getVariableClass()).thenReturn((Class) Object.class);
        when(validVar.isPrimitive()).thenReturn(false);
        when(validVar.isWrapperType()).thenReturn(false);
        tc.addStatement(validStmt);

        // We expect only validVar to be returned by getRandomNonNullNonPrimitiveObject
        // Because:
        // primVar -> isPrimitive() is true (and stmt is PrimitiveStatement)
        // nullVar -> instanceof NullReference is true
        // mockVar -> stmt is FunctionalMockStatement
        // validVar -> passes all checks

        // getRandomNonNullNonPrimitiveObject(type, position) looks up to position (exclusive of current?) or inclusive?
        // Method signature: getRandomNonNullNonPrimitiveObject(Type type, int position)
        // Helper: getObjects(type, position) -> loops i < position.
        // So position 4 should cover indices 0, 1, 2, 3.

        VariableReference result = tc.getRandomNonNullNonPrimitiveObject(Object.class, 4);
        assertEquals(validVar, result);
    }
}
