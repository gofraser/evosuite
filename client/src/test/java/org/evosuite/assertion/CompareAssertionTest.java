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
package org.evosuite.assertion;

import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CompareAssertionTest {

    @Test
    public void testGetCodeForIntegerEquality() {
        CompareAssertion assertion = new CompareAssertion();
        VariableReference source = mock(VariableReference.class);
        VariableReference dest = mock(VariableReference.class);

        doReturn(Integer.class).when(source).getType();
        when(source.getName()).thenReturn("var0");
        when(dest.getName()).thenReturn("var1");

        assertion.source = source;
        assertion.dest = dest;
        assertion.value = 0; // Equality

        String code = assertion.getCode();
        assertEquals("assertEquals(var0, var1);", code);
    }

    @Test
    public void testGetCodeForIntegerLessThan() {
        CompareAssertion assertion = new CompareAssertion();
        VariableReference source = mock(VariableReference.class);
        VariableReference dest = mock(VariableReference.class);

        doReturn(Integer.class).when(source).getType();
        when(source.getName()).thenReturn("var0");
        when(dest.getName()).thenReturn("var1");

        assertion.source = source;
        assertion.dest = dest;
        assertion.value = -1; // Less than

        String code = assertion.getCode();
        assertEquals("assertTrue(var0 < var1);", code);
    }

    @Test
    public void testEvaluateIntegerEquality() throws Exception {
        CompareAssertion assertion = new CompareAssertion();
        VariableReference source = mock(VariableReference.class);
        VariableReference dest = mock(VariableReference.class);
        Scope scope = new Scope();

        assertion.source = source;
        assertion.dest = dest;
        assertion.value = 0;

        // Both non-null and equal
        when(source.getObject(scope)).thenReturn(10);
        when(dest.getObject(scope)).thenReturn(10);
        assertTrue(assertion.evaluate(scope));

        // Both non-null and not equal
        when(source.getObject(scope)).thenReturn(10);
        when(dest.getObject(scope)).thenReturn(20);
        assertFalse(assertion.evaluate(scope));
    }

    @Test
    public void testEvaluateNullSource() throws Exception {
        CompareAssertion assertion = new CompareAssertion();
        VariableReference source = mock(VariableReference.class);
        VariableReference dest = mock(VariableReference.class);
        Scope scope = new Scope();

        assertion.source = source;
        assertion.dest = dest;
        assertion.value = 0; // Equality

        when(source.getObject(scope)).thenReturn(null);
        when(dest.getObject(scope)).thenReturn(null);
        assertTrue(assertion.evaluate(scope)); // null == null

        when(dest.getObject(scope)).thenReturn(10);
        assertFalse(assertion.evaluate(scope)); // null != 10
    }

    @Test
    public void testEvaluateNullSourceInequality() throws Exception {
        CompareAssertion assertion = new CompareAssertion();
        VariableReference source = mock(VariableReference.class);
        VariableReference dest = mock(VariableReference.class);
        Scope scope = new Scope();

        assertion.source = source;
        assertion.dest = dest;
        assertion.value = -1; // Less than

        when(source.getObject(scope)).thenReturn(null);
        when(dest.getObject(scope)).thenReturn(10);
        assertFalse(assertion.evaluate(scope)); // null < 10 is invalid/false
    }
}
