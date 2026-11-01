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

package org.evosuite.testcase.statements;

import org.evosuite.symbolic.TestCaseBuilder;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

/**
 * Statements of two distinct test cases are {@code same} when they have the same structure, but
 * only {@code equals} when they refer to the very same variables.
 */
public class StatementSameStructureTest {

    @Test
    public void primitivesInDifferentTestsAreSameButNotEqual() {
        TestCaseBuilder first = new TestCaseBuilder();
        TestCaseBuilder second = new TestCaseBuilder();
        first.appendIntPrimitive(7);
        second.appendIntPrimitive(7);
        second.appendIntPrimitive(8);

        Statement a = first.getDefaultTestCase().getStatement(0);
        Statement b = second.getDefaultTestCase().getStatement(0);
        Statement c = second.getDefaultTestCase().getStatement(1);

        Assertions.assertTrue(a.same(b));
        Assertions.assertFalse(a.equals(b));
        Assertions.assertTrue(a.same(a));
        Assertions.assertTrue(a.equals(a));
        Assertions.assertFalse(a.same(c));
        Assertions.assertFalse(a.equals(c));
        Assertions.assertFalse(a.same(null));
        Assertions.assertFalse(a.equals(null));
    }

    @Test
    public void arraysAndAssignmentsInDifferentTestsAreSameButNotEqual() {
        TestCaseBuilder first = new TestCaseBuilder();
        TestCaseBuilder second = new TestCaseBuilder();
        for (TestCaseBuilder builder : new TestCaseBuilder[]{first, second}) {
            ArrayReference array = builder.appendArrayStmt(int[].class, 3);
            VariableReference value = builder.appendIntPrimitive(1);
            builder.appendAssignment(array, 0, value);
        }

        DefaultTestCase a = first.getDefaultTestCase();
        DefaultTestCase b = second.getDefaultTestCase();
        for (int i = 0; i < a.size(); i++) {
            Assertions.assertTrue(a.getStatement(i).same(b.getStatement(i)), "same at " + i);
            Assertions.assertFalse(a.getStatement(i).equals(b.getStatement(i)), "equals at " + i);
        }
        Assertions.assertFalse(a.getStatement(0).same(b.getStatement(1)));
    }

    @Test
    public void methodsDifferByCalleeAndParameters() throws Exception {
        Method concat = String.class.getMethod("concat", String.class);
        TestCaseBuilder first = new TestCaseBuilder();
        TestCaseBuilder second = new TestCaseBuilder();
        for (TestCaseBuilder builder : new TestCaseBuilder[]{first, second}) {
            VariableReference callee = builder.appendStringPrimitive("a");
            VariableReference arg = builder.appendStringPrimitive("b");
            builder.appendMethod(callee, concat, arg);
        }
        Statement firstCall = first.getDefaultTestCase().getStatement(2);
        Statement secondCall = second.getDefaultTestCase().getStatement(2);
        Assertions.assertTrue(firstCall.same(secondCall));
        Assertions.assertFalse(firstCall.equals(secondCall));

        TestCaseBuilder third = new TestCaseBuilder();
        VariableReference callee = third.appendStringPrimitive("a");
        VariableReference other = third.appendStringPrimitive("b");
        third.appendMethod(other, concat, callee);
        Assertions.assertFalse(firstCall.same(third.getDefaultTestCase().getStatement(2)));
    }
}
