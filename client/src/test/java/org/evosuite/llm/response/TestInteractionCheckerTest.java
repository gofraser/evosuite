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
package org.evosuite.llm.response;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestInteractionCheckerTest {

    @Test
    void returnsTrueWhenTestInvokesTargetMethod() throws Exception {
        DefaultTestCase testCase = new DefaultTestCase();
        VariableReference string0 = testCase.addStatement(new StringPrimitiveStatement(testCase, "seed"));
        Method lengthMethod = String.class.getMethod("length");
        GenericMethod length = new GenericMethod(lengthMethod, String.class);
        testCase.addStatement(new MethodStatement(
                testCase, length, string0, Collections.<VariableReference>emptyList()));

        assertTrue(TestInteractionChecker.invokesTarget(testCase, String.class.getName()));
    }

    @Test
    void returnsFalseWhenTestDoesNotInvokeTarget() {
        DefaultTestCase testCase = new DefaultTestCase();
        testCase.addStatement(new IntPrimitiveStatement(testCase, 7));

        assertFalse(TestInteractionChecker.invokesTarget(testCase, String.class.getName()));
    }
}
