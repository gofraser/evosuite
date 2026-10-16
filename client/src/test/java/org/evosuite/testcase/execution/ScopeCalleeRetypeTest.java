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
package org.evosuite.testcase.execution;

import com.examples.with.different.packagename.SiblingVerbs.ListRecords;
import com.examples.with.different.packagename.SiblingVerbs.Verb;
import org.evosuite.symbolic.TestCaseBuilder;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A variable typed as a supertype may receive a subclass method; executing the test must not
 * narrow it to a sibling subclass, or the test can no longer be copied.
 */
class ScopeCalleeRetypeTest {

    @Test
    void receiverHoldingSiblingKeepsSupertypeWhenReturnedByMethod() throws Exception {
        TestCaseBuilder builder = new TestCaseBuilder();
        VariableReference verb = builder.appendMethod(null, Verb.class.getMethod("listMetadataFormats"));
        builder.appendMethod(verb, ListRecords.class.getMethod("execute"));

        assertExecutedTestStillClones(builder.getDefaultTestCase(), verb);
    }

    @Test
    void receiverHoldingSiblingKeepsSupertypeWhenReadFromStaticField() throws Exception {
        TestCaseBuilder builder = new TestCaseBuilder();
        VariableReference verb = builder.appendStaticFieldStmt(Verb.class.getField("LIST_METADATA_FORMATS"));
        builder.appendMethod(verb, ListRecords.class.getMethod("execute"));

        assertExecutedTestStillClones(builder.getDefaultTestCase(), verb);
    }

    private static void assertExecutedTestStillClones(DefaultTestCase test, VariableReference verb)
            throws Exception {
        Scope scope = new Scope();
        for (Statement statement : test) {
            statement.execute(scope, System.out);
        }

        assertEquals(Verb.class, verb.getType());
        assertNotNull(test.clone());
    }
}
