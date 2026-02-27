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

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutableSnippetEngine;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CodeAssertionTest {

    @Test
    void getCode_returnsCodeStringVerbatim() {
        CodeAssertion a = new CodeAssertion("assertEquals(42, result);");
        assertEquals("assertEquals(42, result);", a.getCode());
    }

    @Test
    void evaluate_trueWhenAssertionHolds() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference ref = tc.addStatement(new IntPrimitiveStatement(tc, 42));

        CodeAssertion a = new CodeAssertion("assertEquals(42, int0);");
        a.setSource(ref);
        tc.getStatement(0).addAssertion(a);

        Scope scope = new Scope();
        ref.setObject(scope, 42);
        assertTrue(a.evaluate(scope));
    }

    @Test
    void evaluate_falseWhenAssertionFails() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference ref = tc.addStatement(new IntPrimitiveStatement(tc, 42));

        CodeAssertion a = new CodeAssertion("assertEquals(7, int0);");
        a.setSource(ref);
        tc.getStatement(0).addAssertion(a);

        Scope scope = new Scope();
        ref.setObject(scope, 42);
        assertFalse(a.evaluate(scope));
    }

    @Test
    void evaluate_compileFailure_updatesSnippetFailureMetrics() throws Exception {
        ExecutableSnippetEngine.INSTANCE.resetMetricsForTesting();

        TestCase tc = new DefaultTestCase();
        VariableReference ref = tc.addStatement(new IntPrimitiveStatement(tc, 42));

        CodeAssertion a = new CodeAssertion("assertEquals(42, );");
        a.setSource(ref);
        tc.getStatement(0).addAssertion(a);

        Scope scope = new Scope();
        ref.setObject(scope, 42);
        assertFalse(a.evaluate(scope));

        assertEquals(1, ExecutableSnippetEngine.INSTANCE.getCompileFailures());
        assertEquals(1, ExecutableSnippetEngine.INSTANCE.getAssertionEvaluationFailures());
    }

    @Test
    void isValid_trueWhenSourceAndCodeSet() {
        TestCase tc = new DefaultTestCase();
        VariableReference ref = tc.addStatement(new IntPrimitiveStatement(tc, 42));

        CodeAssertion a = new CodeAssertion("assertEquals(42, int0);");
        a.setSource(ref);
        assertTrue(a.isValid());
    }

    @Test
    void isValid_falseWhenSourceNull() {
        CodeAssertion a = new CodeAssertion("assertEquals(42, int0);");
        assertFalse(a.isValid());
    }

    @Test
    void isValid_falseWhenCodeStringNull() {
        TestCase tc = new DefaultTestCase();
        VariableReference ref = tc.addStatement(new IntPrimitiveStatement(tc, 42));

        CodeAssertion a = new CodeAssertion(null);
        a.setSource(ref);
        assertFalse(a.isValid());
    }

    @Test
    void isValid_falseWhenCodeStringEmpty() {
        TestCase tc = new DefaultTestCase();
        VariableReference ref = tc.addStatement(new IntPrimitiveStatement(tc, 42));

        CodeAssertion a = new CodeAssertion("");
        a.setSource(ref);
        assertFalse(a.isValid());
    }

    @Test
    void copy_preservesCodeString() {
        TestCase tc = new DefaultTestCase();
        VariableReference ref = tc.addStatement(new IntPrimitiveStatement(tc, 42));

        CodeAssertion original = new CodeAssertion("assertNotNull(obj);");
        original.setSource(ref);
        original.setComment("test comment");

        Assertion copy = original.copy(tc, 0);
        assertInstanceOf(CodeAssertion.class, copy);
        assertEquals("assertNotNull(obj);", copy.getCode());
        assertEquals("test comment", ((CodeAssertion) copy).comment);
    }

    @Test
    void getCodeString_returnsRawCode() {
        CodeAssertion a = new CodeAssertion("assertTrue(flag);");
        assertEquals("assertTrue(flag);", a.getCodeString());
    }

    @Test
    void equals_sameCodeString() {
        TestCase tc = new DefaultTestCase();
        VariableReference ref = tc.addStatement(new IntPrimitiveStatement(tc, 42));

        CodeAssertion a1 = new CodeAssertion("assertEquals(42, x);");
        a1.setSource(ref);
        CodeAssertion a2 = new CodeAssertion("assertEquals(42, x);");
        a2.setSource(ref);

        assertEquals(a1, a2);
        assertEquals(a1.hashCode(), a2.hashCode());
    }

    @Test
    void equals_differentCodeString() {
        TestCase tc = new DefaultTestCase();
        VariableReference ref = tc.addStatement(new IntPrimitiveStatement(tc, 42));

        CodeAssertion a1 = new CodeAssertion("assertEquals(42, x);");
        a1.setSource(ref);
        CodeAssertion a2 = new CodeAssertion("assertEquals(99, x);");
        a2.setSource(ref);

        assertNotEquals(a1, a2);
    }

    @Test
    void notEqualToPrimitiveAssertion() {
        TestCase tc = new DefaultTestCase();
        VariableReference ref = tc.addStatement(new IntPrimitiveStatement(tc, 42));

        CodeAssertion ca = new CodeAssertion("assertEquals(42, x);");
        ca.setSource(ref);

        PrimitiveAssertion pa = new PrimitiveAssertion();
        pa.setSource(ref);
        pa.setValue(42);

        assertNotEquals(ca, pa);
    }

    @Test
    void canBeAttachedToStatement() {
        TestCase tc = new DefaultTestCase();
        VariableReference ref = tc.addStatement(new IntPrimitiveStatement(tc, 42));

        CodeAssertion a = new CodeAssertion("assertEquals(42, int0);");
        a.setSource(ref);

        tc.getStatement(0).addAssertion(a);
        assertEquals(1, tc.getStatement(0).getAssertions().size());
        assertTrue(tc.getStatement(0).getAssertions().contains(a));
    }
}
