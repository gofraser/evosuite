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
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.fm.EvoInvocationListener;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.variable.VariableReferenceImpl;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockSettings;

import java.io.PrintStream;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State of a functional mock statement that its equality and its execution have to keep.
 */
public class FunctionalMockStatementStateTest {

    @AfterEach
    public void tearDown() {
        FunctionalMockStatement.resetMockingFailoverStateForTests();
    }

    private static FunctionalMockStatement mock(boolean lenient) {
        TestCase tc = new DefaultTestCase();
        GenericClass<?> target = GenericClassFactory.get(Callable.class);
        FunctionalMockStatement statement = new FunctionalMockStatement(tc, Callable.class, target);
        tc.addStatement(statement);
        statement.setUseLenientDefaultAnswer(lenient);
        return statement;
    }

    @Test
    public void lenientAndStrictMocksOfTheSameTypeAreDifferent() {
        FunctionalMockStatement strict = mock(false);
        FunctionalMockStatement otherStrict = (FunctionalMockStatement) strict.copy(strict.getTestCase(), 0);
        FunctionalMockStatement lenient = (FunctionalMockStatement) strict.copy(strict.getTestCase(), 0);
        lenient.setUseLenientDefaultAnswer(true);
        // variable references are compared by identity
        otherStrict.retval = strict.retval;
        lenient.retval = strict.retval;

        // sanity: without the flag they are the same statement
        assertEquals(strict, otherStrict);
        assertEquals(strict.hashCode(), otherStrict.hashCode());
        assertTrue(strict.same(otherStrict));

        // the flag changes what the mock does and how it is rendered
        assertNotEquals(strict, lenient);
        assertNotEquals(strict.hashCode(), lenient.hashCode());
        assertFalse(strict.same(lenient));
    }

    /** A mock whose set-up fails after the listener has been created. */
    private static class FailingSetup extends FunctionalMockStatement {
        FailingSetup(TestCase tc, VariableReference retval, GenericClass<?> target) {
            super(tc, retval, target);
        }

        @Override
        protected MockSettings createMockSettings() {
            throw new IllegalStateException("set-up failed");
        }
    }

    @Test
    public void failedSetUpKeepsTheListenerOfTheLastGoodExecution() throws Exception {
        TestCase tc = new DefaultTestCase();
        GenericClass<?> target = GenericClassFactory.get(Callable.class);
        VariableReference retval = new VariableReferenceImpl(tc, target);
        FailingSetup statement = new FailingSetup(tc, retval, target);
        EvoInvocationListener lastGood = new EvoInvocationListener(retval.getGenericClass());
        statement.listener = lastGood;

        statement.execute(new Scope(), new PrintStream(new java.io.ByteArrayOutputStream()));

        assertNotNull(statement.listener);
        assertSame(lastGood, statement.listener,
                "the stubs are rebuilt from the listener, so an empty one drops them all");
    }
}
