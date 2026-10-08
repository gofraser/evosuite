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

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.numeric.BooleanPrimitiveStatement;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Observed on weka's {@code RandomSplitResultProducer.m_randomize}: a null written into a primitive
 * boolean field, which neither compiles nor can have executed without an exception.
 */
public class AssignmentStatementNullPrimitiveTest {

    public static class Holder {
        public boolean flag;
    }

    private static FieldReference flagOf(TestCase tc, VariableReference holder) throws Exception {
        return new FieldReference(tc, new GenericField(Holder.class.getField("flag"), Holder.class), holder);
    }

    private static VariableReference holder(TestCase tc) throws Exception {
        return tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Holder.class.getConstructor(), Holder.class), Collections.emptyList()));
    }

    @Test
    public void constructorRejectsNullStatementValueForPrimitiveField() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference holder = holder(tc);
        VariableReference nullValue = tc.addStatement(new NullStatement(tc, Boolean.class));
        FieldReference flag = flagOf(tc, holder);
        assertThrows(IllegalArgumentException.class, () -> new AssignmentStatement(tc, flag, nullValue));
    }

    @Test
    public void constructorRejectsNullStatementValueForPrimitiveArrayElement() {
        TestCase tc = new DefaultTestCase();
        ArrayStatement array = new ArrayStatement(tc, boolean[].class, 2);
        tc.addStatement(array);
        VariableReference nullValue = tc.addStatement(new NullStatement(tc, Boolean.class));
        ArrayIndex element = new ArrayIndex(tc, (ArrayReference) array.getReturnValue(), 0);
        assertThrows(IllegalArgumentException.class, () -> new AssignmentStatement(tc, element, nullValue));
    }

    @Test
    public void nullIsStillAcceptedForWrapperTargetsAndNonNullForPrimitives() throws Exception {
        TestCase tc = new DefaultTestCase();
        ArrayStatement wrappers = new ArrayStatement(tc, Boolean[].class, 1);
        tc.addStatement(wrappers);
        VariableReference nullValue = tc.addStatement(new NullStatement(tc, Boolean.class));
        VariableReference truth = tc.addStatement(new BooleanPrimitiveStatement(tc, true));
        tc.addStatement(new AssignmentStatement(tc,
                new ArrayIndex(tc, (ArrayReference) wrappers.getReturnValue(), 0), nullValue));
        VariableReference holder = holder(tc);
        AssignmentStatement ok = new AssignmentStatement(tc, flagOf(tc, holder), truth);
        tc.addStatement(ok);
        assertTrue(ok.isValid());
    }

    @Test
    public void mutationNeverWritesNullIntoPrimitiveField() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference holder = holder(tc);
        VariableReference nullValue = tc.addStatement(new NullStatement(tc, Boolean.class));
        VariableReference truth = tc.addStatement(new BooleanPrimitiveStatement(tc, true));
        AssignmentStatement assignment = new AssignmentStatement(tc, flagOf(tc, holder), truth);
        tc.addStatement(assignment);
        for (int i = 0; i < 300; i++) {
            assignment.mutate(tc, org.evosuite.testcase.TestFactory.getInstance());
            assertFalse(AssignmentStatement.isNullForPrimitiveTarget(tc, assignment.getReturnValue(),
                    assignment.getValue()));
        }
        // replace() must not substitute the null either.
        assignment.replace(truth, nullValue);
        assertTrue(assignment.getValue() == truth);
    }

    @Test
    public void isValidRejectsNullForPrimitiveTarget() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference holder = holder(tc);
        VariableReference truth = tc.addStatement(new BooleanPrimitiveStatement(tc, true));
        VariableReference nullValue = tc.addStatement(new NullStatement(tc, Boolean.class));
        AssignmentStatement assignment = new AssignmentStatement(tc, flagOf(tc, holder), truth);
        tc.addStatement(assignment);
        assignment.setValue(nullValue);
        assertFalse(assignment.isValid());
    }
}
