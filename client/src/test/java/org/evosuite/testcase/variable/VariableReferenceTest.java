package org.evosuite.testcase.variable;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.ArrayStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericField;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class VariableReferenceTest {

    @Test
    public void testGetStPositionCaching() {
        DefaultTestCase tc = new DefaultTestCase();
        StringPrimitiveStatement st1 = new StringPrimitiveStatement(tc, "test1");
        tc.addStatement(st1);
        VariableReference var1 = st1.getReturnValue();

        Assertions.assertEquals(0, var1.getStPosition());

        // Check if caching works (we can't easily check internal state, but we check behavior)
        Assertions.assertEquals(0, var1.getStPosition());

        StringPrimitiveStatement st2 = new StringPrimitiveStatement(tc, "test2");
        tc.addStatement(st2);
        VariableReference var2 = st2.getReturnValue();

        Assertions.assertEquals(1, var2.getStPosition());
        Assertions.assertEquals(0, var1.getStPosition());

        // Remove first statement, shifting indices
        tc.remove(0);
        // Now st2 is at 0
        Assertions.assertEquals(0, var2.getStPosition());
    }

    @Test
    public void testGetStPositionNotFound() {
        assertThrows(AssertionError.class, () -> {
            DefaultTestCase tc = new DefaultTestCase();
            StringPrimitiveStatement st1 = new StringPrimitiveStatement(tc, "test1");
            // Don't add to test case
            VariableReference var1 = st1.getReturnValue();
            // This should fail
            var1.getStPosition();
        });
    }

    @Test
    public void testArrayReferenceLength() {
        DefaultTestCase tc = new DefaultTestCase();
        ArrayReference arrayRef = new ArrayReference(tc, GenericClassFactory.get(int.class), new int[]{5});
        Assertions.assertEquals(5, arrayRef.getArrayLength());
        Assertions.assertEquals(1, arrayRef.getArrayDimensions());
    }

    @Test
    public void testArrayIndexSetObjectInt() throws CodeUnderTestException {
        DefaultTestCase tc = new DefaultTestCase();
        // Create an array statement to produce an ArrayReference
        ArrayStatement arrayStmt = new ArrayStatement(tc, int.class, 5);
        tc.addStatement(arrayStmt);
        ArrayReference arrayRef = (ArrayReference) arrayStmt.getReturnValue();

        ArrayIndex arrayIndex = new ArrayIndex(tc, arrayRef, 2);

        Scope scope = new Scope();
        int[] actualArray = new int[5];
        scope.setObject(arrayRef, actualArray);

        arrayIndex.setObject(scope, 42);

        Assertions.assertEquals(42, actualArray[2]);
    }

    @Test
    public void testArrayIndexSetObjectInteger() throws CodeUnderTestException {
        DefaultTestCase tc = new DefaultTestCase();
        ArrayStatement arrayStmt = new ArrayStatement(tc, Integer.class, 5);
        tc.addStatement(arrayStmt);
        ArrayReference arrayRef = (ArrayReference) arrayStmt.getReturnValue();

        ArrayIndex arrayIndex = new ArrayIndex(tc, arrayRef, 2);

        Scope scope = new Scope();
        Integer[] actualArray = new Integer[5];
        scope.setObject(arrayRef, actualArray);

        arrayIndex.setObject(scope, 42);

        Assertions.assertEquals(Integer.valueOf(42), actualArray[2]);
    }

}
