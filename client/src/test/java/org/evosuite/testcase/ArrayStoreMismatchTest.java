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

import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.ArrayStatement;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.numeric.ShortPrimitiveStatement;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.runtime.mock.java.io.MockFile;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An array variable declared as Object[] can hold a String[] at runtime. Storing a Short into it throws an ArrayStoreException in
 * the generated JUnit test, so executing the assignment must report that as the
 * statement's exception rather than failing with an IllegalArgumentException from
 * reflection.
 */
public class ArrayStoreMismatchTest {

    @Test
    public void testStoreIntoNarrowerRuntimeArrayYieldsArrayStoreException() {
        DefaultTestCase tc = new DefaultTestCase();
        ArrayStatement arrayStmt = new ArrayStatement(tc, Object[].class, 5);
        tc.addStatement(arrayStmt);
        ShortPrimitiveStatement value = new ShortPrimitiveStatement(tc, (short) 3);
        tc.addStatement(value);
        ArrayIndex index = new ArrayIndex(tc, (ArrayReference) arrayStmt.getReturnValue(),
                Collections.singletonList(4));
        AssignmentStatement assignment = new AssignmentStatement(tc, index, value.getReturnValue());
        tc.addStatement(assignment);

        Scope scope = new Scope();
        scope.setObject(arrayStmt.getReturnValue(), new String[5]);
        scope.setObject(value.getReturnValue(), Short.valueOf((short) 3));

        Throwable[] thrown = new Throwable[1];
        assertDoesNotThrow(() -> thrown[0] = assignment.execute(scope,
                new PrintStream(new ByteArrayOutputStream())));
        assertNotNull(thrown[0]);
        Throwable cause = thrown[0] instanceof CodeUnderTestException ? thrown[0].getCause() : thrown[0];
        assertTrue(cause instanceof ArrayStoreException, String.valueOf(thrown[0]));
    }

    /**
     * The generated test runs with the mocking instrumentation, so the File it stores into the
     * MockFile[] returned by MockFile.listRoots() is a MockFile and no exception occurs. The
     * search holds the real File, which cannot be stored; that must not be reported as an
     * ArrayStoreException the generated test would never throw.
     */
    @Test
    public void testRealFileIntoMockFileArrayIsNotReportedAsArrayStoreException() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();
        ArrayStatement arrayStmt = new ArrayStatement(tc, java.io.File[].class, 1);
        tc.addStatement(arrayStmt);
        VariableReference path = tc.addStatement(new StringPrimitiveStatement(tc, "foo"));
        ConstructorStatement ctor = new ConstructorStatement(tc,
                new GenericConstructor(java.io.File.class.getConstructor(String.class), java.io.File.class),
                Collections.singletonList(path));
        tc.addStatement(ctor);
        ArrayIndex index = new ArrayIndex(tc, (ArrayReference) arrayStmt.getReturnValue(),
                Collections.singletonList(0));
        AssignmentStatement assignment = new AssignmentStatement(tc, index, ctor.getReturnValue());
        tc.addStatement(assignment);

        Scope scope = new Scope();
        scope.setObject(arrayStmt.getReturnValue(), new MockFile[1]);
        scope.setObject(ctor.getReturnValue(), new java.io.File("foo"));

        Throwable thrown = null;
        try {
            thrown = assignment.execute(scope, new PrintStream(new ByteArrayOutputStream()));
        } catch (IllegalArgumentException e) {
            thrown = e;
        }
        assertNotNull(thrown);
        assertTrue(thrown instanceof IllegalArgumentException, String.valueOf(thrown));
    }

    private Throwable store(Class<?> staticArrayType, Object runtimeArray, Object value) {
        DefaultTestCase tc = new DefaultTestCase();
        ArrayStatement arrayStmt = new ArrayStatement(tc, staticArrayType, 2);
        tc.addStatement(arrayStmt);
        ShortPrimitiveStatement source = new ShortPrimitiveStatement(tc, (short) 3);
        tc.addStatement(source);
        ArrayIndex index = new ArrayIndex(tc, (ArrayReference) arrayStmt.getReturnValue(),
                Collections.singletonList(1));
        AssignmentStatement assignment = new AssignmentStatement(tc, index, source.getReturnValue());
        tc.addStatement(assignment);
        Scope scope = new Scope();
        scope.setObject(arrayStmt.getReturnValue(), runtimeArray);
        scope.setObject(source.getReturnValue(), value);
        try {
            return assignment.execute(scope, new PrintStream(new ByteArrayOutputStream()));
        } catch (Exception e) {
            return e;
        }
    }

    /** A Short stored through an Object[] into a Long[] throws; the generated test does not convert it. */
    @Test
    public void testOtherWrapperIntoWiderStaticArrayYieldsArrayStoreException() {
        Long[] runtime = new Long[2];
        Throwable thrown = store(Object[].class, runtime, Short.valueOf((short) 3));
        assertNotNull(thrown);
        Throwable cause = thrown instanceof CodeUnderTestException ? thrown.getCause() : thrown;
        assertTrue(cause instanceof ArrayStoreException, String.valueOf(thrown));
    }

    @Test
    public void testSameWrapperIntoWiderStaticArrayStores() {
        Long[] runtime = new Long[2];
        assertNull(store(Object[].class, runtime, Long.valueOf(3)));
        assertEquals(Long.valueOf(3), runtime[1]);
    }

    /** A Long[] static type renders the conversion cast "(Long) (long) short0", so the Long is stored. */
    @Test
    public void testOtherWrapperIntoWrapperStaticArrayIsConverted() {
        Long[] runtime = new Long[2];
        assertNull(store(Long[].class, runtime, Short.valueOf((short) 3)));
        assertEquals(Long.valueOf(3), runtime[1]);
    }

    /** Primitive widening into a primitive array is legal Java and stays. */
    @Test
    public void testWideningIntoPrimitiveArrayStaysLegal() {
        long[] runtime = new long[2];
        assertNull(store(long[].class, runtime, Short.valueOf((short) 3)));
        assertEquals(3L, runtime[1]);
    }
}
