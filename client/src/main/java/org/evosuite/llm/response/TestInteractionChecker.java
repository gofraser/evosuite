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

import org.evosuite.testcase.TestCase;

/**
 * CUT-interaction checks for parsed tests.
 */
final class TestInteractionChecker {

    private TestInteractionChecker() {
        // utility class
    }

    static boolean invokesTarget(TestCase testCase, String targetClassName) {
        if (testCase == null || targetClassName == null || targetClassName.isEmpty()) {
            return false;
        }
        for (org.evosuite.testcase.statements.Statement statement : testCase) {
            if (statement instanceof org.evosuite.testcase.statements.MethodStatement) {
                org.evosuite.testcase.statements.MethodStatement ms =
                        (org.evosuite.testcase.statements.MethodStatement) statement;
                if (ms.getMethod().getDeclaringClass().getName().equals(targetClassName)) {
                    return true;
                }
            } else if (statement instanceof org.evosuite.testcase.statements.ConstructorStatement) {
                org.evosuite.testcase.statements.ConstructorStatement cs =
                        (org.evosuite.testcase.statements.ConstructorStatement) statement;
                if (cs.getReturnValue() != null
                        && cs.getReturnValue().getVariableClass() != null
                        && cs.getReturnValue().getVariableClass().getName().equals(targetClassName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
