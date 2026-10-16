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
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.ArrayReference;
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

    @Test
    public void testReplaceUpdatesSourceOfFieldTarget() throws Exception {
        TestCaseBuilder builder = new TestCaseBuilder();
        VariableReference owner1 = builder.appendConstructor(AssignmentFixture.class.getConstructor());
        VariableReference owner2 = builder.appendConstructor(AssignmentFixture.class.getConstructor());
        VariableReference value = builder.appendStringPrimitive("v");
        DefaultTestCase test = builder.getDefaultTestCase();

        FieldReference target = new FieldReference(test,
                new GenericField(AssignmentFixture.class.getField("instanceText"), AssignmentFixture.class),
                owner1);
        AssignmentStatement assignment = new AssignmentStatement(test, target, value);
        test.addStatement(assignment);

        assignment.replace(owner1, owner2);

        Assertions.assertFalse(assignment.references(owner1), test.toCode());
        Assertions.assertTrue(assignment.references(owner2), test.toCode());
    }

    @Test
    public void testReplaceUpdatesArrayOfIndexTarget() throws Exception {
        TestCaseBuilder builder = new TestCaseBuilder();
        ArrayReference array1 = builder.appendArrayStmt(String[].class, 2);
        ArrayReference array2 = builder.appendArrayStmt(String[].class, 2);
        VariableReference value = builder.appendStringPrimitive("v");
        DefaultTestCase test = builder.getDefaultTestCase();

        AssignmentStatement assignment = new AssignmentStatement(test,
                new ArrayIndex(test, array1, 0), value);
        test.addStatement(assignment);

        assignment.replace(array1, array2);

        Assertions.assertFalse(assignment.references(array1), test.toCode());
        Assertions.assertTrue(assignment.references(array2), test.toCode());
    }

    public static class AssignmentFixture {
        public static String text;
        public String instanceText;
    }
}
