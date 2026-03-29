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
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericField;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class AssignmentStatementTest {

    @Test
    public void testReplaceParameterRejectsIncompatibleType() throws Exception {
        TestCaseBuilder builder = new TestCaseBuilder();
        VariableReference stringValue = builder.appendStringPrimitive("ok");
        VariableReference intValue = builder.appendIntPrimitive(7);
        DefaultTestCase test = builder.getDefaultTestCase();

        FieldReference stringField = new FieldReference(test,
                new GenericField(AssignmentFixture.class.getField("text"), AssignmentFixture.class));
        AssignmentStatement assignment = new AssignmentStatement(test, stringField, stringValue);

        assignment.replace(stringValue, intValue);

        Assertions.assertSame(stringValue, assignment.getValue());
    }

    @Test
    public void testReplaceParameterAcceptsCompatibleType() throws Exception {
        TestCaseBuilder builder = new TestCaseBuilder();
        VariableReference oldValue = builder.appendStringPrimitive("old");
        VariableReference newValue = builder.appendStringPrimitive("new");
        DefaultTestCase test = builder.getDefaultTestCase();

        FieldReference stringField = new FieldReference(test,
                new GenericField(AssignmentFixture.class.getField("text"), AssignmentFixture.class));
        AssignmentStatement assignment = new AssignmentStatement(test, stringField, oldValue);

        assignment.replace(oldValue, newValue);

        Assertions.assertSame(newValue, assignment.getValue());
    }

    public static class AssignmentFixture {
        public static String text;
    }
}
