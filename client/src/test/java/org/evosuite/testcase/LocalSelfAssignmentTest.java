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

import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A local assigned to itself is rendered as "T x_1 = x_1;" (javac: "might not have been
 * initialized") when the local was declared by a functional mock: the mock statement named its
 * variable without registering the declaration, so the assignment looked like a first
 * declaration, minted the fresh name x_1 and redirected every later use to it (SF110 vuze
 * CacheFileWithCache, "TOTorrentFile tOTorrentFile0_1 = tOTorrentFile0_1;").
 */
public class LocalSelfAssignmentTest {

    private static DefaultTestCase testWithTwoMocks(VariableReference[] mocks) {
        DefaultTestCase tc = new DefaultTestCase();
        for (int i = 0; i < mocks.length; i++) {
            mocks[i] = tc.addStatement(new FunctionalMockStatement(
                    tc, Runnable.class, GenericClassFactory.get(Runnable.class)));
        }
        return tc;
    }

    @Test
    public void assignmentToMockLocalIsNotRedeclared() {
        VariableReference[] mocks = new VariableReference[2];
        DefaultTestCase tc = testWithTwoMocks(mocks);
        tc.addStatement(new AssignmentStatement(tc, mocks[0], mocks[1]));

        String code = tc.toCode();

        assertFalse(code.contains("_1"), code);
        assertTrue(code.contains("runnable0 = runnable1;"), code);
        assertFalse(code.contains("Runnable runnable0 = runnable1;"), code);
    }

    @Test
    public void selfAssignmentOfMockLocalIsNotRendered() {
        VariableReference[] mocks = new VariableReference[1];
        DefaultTestCase tc = testWithTwoMocks(mocks);
        tc.addStatement(new AssignmentStatement(tc, mocks[0], mocks[0]));

        String code = tc.toCode();

        assertFalse(code.contains("_1"), code);
        assertFalse(code.contains("runnable0 = runnable0"), code);
    }

    @Test
    public void replacingOneSideNeverAliasesTheOther() {
        DefaultTestCase tc = new DefaultTestCase();
        VariableReference target = tc.addStatement(new StringPrimitiveStatement(tc, "a"));
        VariableReference value = tc.addStatement(new StringPrimitiveStatement(tc, "b"));
        AssignmentStatement assignment = new AssignmentStatement(tc, target, value);
        tc.addStatement(assignment);

        // Making the value the retval's replacement would write the value into itself.
        assignment.replace(target, value);
        assertNotSame(assignment.getReturnValue(), assignment.getValue());

        assignment.replace(value, target);
        assertNotSame(assignment.getReturnValue(), assignment.getValue());
    }
}
