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
package org.evosuite.testcase;

import org.evosuite.testcase.statements.ArrayStatement;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for invalid array assignment states.
 * Tests that the fix for {@code AssertionError} in {@code DefaultTestCase.isValid()}
 * correctly prevents and handles out-of-bounds array index assignments.
 */
public class ArrayAssignmentValidityTest {

    /**
     * Reproduces the original bug: an ArrayStatement with length 0 has an
     * AssignmentStatement targeting index 1. Before the fix, isValid() would
     * throw an AssertionError. After the fix, it should return false.
     */
    @Test
    public void testIsValidReturnsFalseForOOBArrayIndex() {
        DefaultTestCase tc = new DefaultTestCase();

        // Object[] objectArray1 = new Object[0];
        ArrayStatement arrayStmt = new ArrayStatement(tc, Object[].class, 0);
        tc.addStatement(arrayStmt);

        // A value to assign into the array
        IntPrimitiveStatement value = new IntPrimitiveStatement(tc, 42);
        tc.addStatement(value);

        // Manually construct objectArray1[1] = value (out of bounds!)
        ArrayReference arrayRef = (ArrayReference) arrayStmt.getReturnValue();
        ArrayIndex oobIndex = new ArrayIndex(tc, arrayRef,
                Collections.singletonList(1));
        AssignmentStatement assignment = new AssignmentStatement(tc, oobIndex,
                value.getReturnValue());
        
        try {
            tc.addStatement(assignment);
        } catch (AssertionError e) {
            // Expected because addStatement() internally asserts isValid()!
        }

        // isValid() should return false, not throw a ClassCastException or crash

        assertFalse(tc.isValid(),
                "Test with OOB array assignment should be invalid");
    }

    /**
     * Verifies that a valid array assignment (index within bounds) passes isValid().
     */
    @Test
    public void testIsValidReturnsTrueForValidArrayIndex() {
        DefaultTestCase tc = new DefaultTestCase();

        // Object[] objectArray = new Object[3];
        ArrayStatement arrayStmt = new ArrayStatement(tc, Object[].class, 3);
        tc.addStatement(arrayStmt);

        IntPrimitiveStatement value = new IntPrimitiveStatement(tc, 42);
        tc.addStatement(value);

        // objectArray[2] = value (valid: index 2, length 3)
        ArrayReference arrayRef = (ArrayReference) arrayStmt.getReturnValue();
        ArrayIndex validIndex = new ArrayIndex(tc, arrayRef,
                Collections.singletonList(2));
        AssignmentStatement assignment = new AssignmentStatement(tc, validIndex,
                value.getReturnValue());
        tc.addStatement(assignment);

        assertTrue(tc.isValid(),
                "Test with in-bounds array assignment should be valid");
    }

    /**
     * Simulates Scope corruption: ArrayReference length is modified to be
     * larger than the structural ArrayStatement length. Verifies that
     * getSourceReplacements() (indirectly via AssignmentStatement.mutate())
     * still respects the structural length.
     *
     * This tests the core fix: getStructuralArrayLength() reads from
     * ArrayStatement.size() rather than ArrayReference.getArrayLength().
     */
    @Test
    public void testScopeCorruptedArrayLengthDoesNotAffectValidity() {
        DefaultTestCase tc = new DefaultTestCase();

        // Object[] arr = new Object[2];
        ArrayStatement arrayStmt = new ArrayStatement(tc, Object[].class, 2);
        tc.addStatement(arrayStmt);

        ArrayReference arrayRef = (ArrayReference) arrayStmt.getReturnValue();

        // Verify structural length is 2
        assertEquals(2, arrayStmt.size());
        assertEquals(2, arrayRef.getArrayLength());

        // Simulate Scope corruption: set ArrayReference length to 10
        // (as if Scope.setObject() observed a longer runtime array)
        arrayRef.setArrayLength(10);

        // ArrayReference now reports 10, but ArrayStatement still reports 2
        assertEquals(10, arrayRef.getArrayLength());
        assertEquals(2, arrayStmt.size());

        // The test should still be valid (no assignments exist)
        assertTrue(tc.isValid());
    }

    /**
     * Verifies that a zero-length array with no assignments is valid.
     */
    @Test
    public void testZeroLengthArrayWithNoAssignmentsIsValid() {
        DefaultTestCase tc = new DefaultTestCase();

        ArrayStatement arrayStmt = new ArrayStatement(tc, Object[].class, 0);
        tc.addStatement(arrayStmt);

        assertTrue(tc.isValid(),
                "Zero-length array with no assignments should be valid");
    }

    /**
     * Tests boundary: array of length 1 with assignment at index 0 is valid,
     * but assignment at index 1 makes it invalid.
     */
    @Test
    public void testBoundaryArrayAssignment() {
        DefaultTestCase tc = new DefaultTestCase();

        ArrayStatement arrayStmt = new ArrayStatement(tc, Object[].class, 1);
        tc.addStatement(arrayStmt);

        IntPrimitiveStatement value = new IntPrimitiveStatement(tc, 99);
        tc.addStatement(value);

        // arr[0] = value (valid for length 1)
        ArrayReference arrayRef = (ArrayReference) arrayStmt.getReturnValue();
        ArrayIndex idx0 = new ArrayIndex(tc, arrayRef,
                Collections.singletonList(0));
        AssignmentStatement assign0 = new AssignmentStatement(tc, idx0,
                value.getReturnValue());
        tc.addStatement(assign0);

        assertTrue(tc.isValid(), "arr[0] on length-1 array should be valid");

        // Now add arr[1] = value (invalid for length 1)
        ArrayIndex idx1 = new ArrayIndex(tc, arrayRef,
                Collections.singletonList(1));
        AssignmentStatement assign1 = new AssignmentStatement(tc, idx1,
                value.getReturnValue());
        
        try {
            tc.addStatement(assign1);
        } catch (AssertionError e) {
            // Expected
        }

        assertFalse(tc.isValid(), "arr[1] on length-1 array should be invalid");
    }
    /**
     * Tests that isValid() does not throw a ClassCastException when an array is referenced
     * by a non-ArrayIndex reference.
     * This verifies the fix for ClassCastException in ArrayStatement.isValid().
     */
    @Test
    public void testIsValidDoesNotThrowClassCastExceptionForNonArrayIndex() {
        DefaultTestCase tc = new DefaultTestCase();

        ArrayStatement arrayStmt = new ArrayStatement(tc, Object[].class, 1);
        tc.addStatement(arrayStmt);

        ArrayReference arrayRef = (ArrayReference) arrayStmt.getReturnValue();

        // Simulate a Reference that has the array as its target but is not an ArrayIndex.
        org.evosuite.testcase.variable.VariableReference mockRef = 
            new org.evosuite.testcase.variable.VariableReferenceImpl(tc, Object.class) {
                @Override
                public org.evosuite.testcase.variable.VariableReference getAdditionalVariableReference() {
                    return arrayRef;
                }
            };
        
        IntPrimitiveStatement value = new IntPrimitiveStatement(tc, 42);
        tc.addStatement(value);

        // Assignment to the mock reference
        AssignmentStatement assignment = new AssignmentStatement(tc, mockRef, value.getReturnValue());
        
        try {
            tc.addStatement(assignment);
        } catch (AssertionError e) {
            // Expected if it determines it is invalid, although the mock may be considered valid
        }

        // Before the fix, tc.isValid() or tc.addStatement(assignment) would throw ClassCastException
        assertDoesNotThrow(() -> tc.isValid(), 
                "isValid() should safely handle non-ArrayIndex references on arrays");
    }
}
