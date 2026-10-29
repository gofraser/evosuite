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

import org.evosuite.Properties;
import org.evosuite.testcase.statements.FieldStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.utils.generic.GenericField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A field read that threw is rendered like any other call that threw: the exception policy of
 * the call visitors decides whether it is caught, and what the catch block contains.
 */
public class FieldStatementExceptionRenderingTest {

    public static class Holder {
        public static String value = "x";
        public static int count = 1;
    }

    /** A package-private exception: a test cannot name it, so it is caught as its public parent. */
    static class HiddenException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
    }

    private String originalTargetClass;
    private boolean originalCatchUndeclared;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        originalCatchUndeclared = Properties.CATCH_UNDECLARED_EXCEPTIONS;
        Properties.TARGET_CLASS = FieldStatementExceptionRenderingTest.class.getName();
        Properties.resetTargetClass();
        Properties.CATCH_UNDECLARED_EXCEPTIONS = true;
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.CATCH_UNDECLARED_EXCEPTIONS = originalCatchUndeclared;
        Properties.resetTargetClass();
    }

    private static String render(String field, Throwable exception, boolean failing) throws Exception {
        TestCase tc = new DefaultTestCase();
        FieldStatement read = new FieldStatement(tc,
                new GenericField(Holder.class.getField(field), Holder.class), null);
        tc.addStatement(read);
        if (failing) {
            tc.setFailing();
        }
        TestCodeVisitor visitor = new TestCodeVisitor();
        if (exception != null) {
            visitor.setException(read, exception);
        }
        tc.accept(visitor);
        return visitor.getCode();
    }

    private static Throwable withoutTrace(Throwable exception) {
        exception.setStackTrace(new StackTraceElement[0]);
        return exception;
    }

    @Test
    public void aReadThatDidNotThrowIsAPlainAssignment() throws Exception {
        assertEquals("String string0 = FieldStatementExceptionRenderingTest.Holder.value;\n",
                render("value", null, false));
    }

    @Test
    public void aThrowingReadIsCaughtWithTheFailAssertionAndTheMessage() throws Exception {
        assertEquals("String string0 = null;\n"
                + "try {\n"
                + "    string0 = FieldStatementExceptionRenderingTest.Holder.value;\n"
                + "    fail(\"Expecting exception: IllegalStateException\");\n"
                + "\n"
                + "} catch(IllegalStateException e) {\n"
                + "    //\n"
                + "    // boom\n"
                + "    //\n"
                + "}\n\n",
                render("value", withoutTrace(new IllegalStateException("boom")), false));
    }

    @Test
    public void aPrimitiveFieldIsDeclaredWithItsDefaultBeforeTheTry() throws Exception {
        assertEquals("int int0 = 0;\n"
                + "try {\n"
                + "    int0 = FieldStatementExceptionRenderingTest.Holder.count;\n"
                + "    fail(\"Expecting exception: IllegalStateException\");\n"
                + "\n"
                + "} catch(IllegalStateException e) {\n"
                + "    //\n"
                + "    // boom\n"
                + "    //\n"
                + "}\n\n",
                render("count", withoutTrace(new IllegalStateException("boom")), false));
    }

    @Test
    public void anExceptionATestCannotNameIsCaughtAsItsPublicParent() throws Exception {
        assertEquals("String string0 = null;\n"
                + "try {\n"
                + "    string0 = FieldStatementExceptionRenderingTest.Holder.value;\n"
                + "    fail(\"Expecting exception: IllegalStateException\");\n"
                + "\n"
                + "} catch(IllegalStateException e) {\n"
                + "    //\n"
                + "    // no message in exception (getMessage() returned null)\n"
                + "    //\n"
                + "}\n\n",
                render("value", withoutTrace(new HiddenException()), false));
    }

    @Test
    public void aClassInitializerFailureIsNotEncodedAsExpectedBehaviour() throws Exception {
        assertEquals("String string0 = null;\n"
                + "string0 = FieldStatementExceptionRenderingTest.Holder.value;\n",
                render("value", withoutTrace(new ExceptionInInitializerError("boom")), false));
    }

    @Test
    public void aFailingTestDoesNotCatchTheException() throws Exception {
        assertEquals("String string0 = null;\n"
                + "string0 = FieldStatementExceptionRenderingTest.Holder.value;\n",
                render("value", withoutTrace(new IllegalStateException("boom")), true));
    }

    @Test
    public void undeclaredExceptionsAreNotCaughtWhenTheyAreNotToBe() throws Exception {
        Properties.CATCH_UNDECLARED_EXCEPTIONS = false;
        assertEquals("String string0 = null;\n"
                + "string0 = FieldStatementExceptionRenderingTest.Holder.value;\n",
                render("value", withoutTrace(new IllegalStateException("boom")), false));
    }

    @Test
    public void anUnrelatedStatementIsUnaffected() throws Exception {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 1));
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        assertEquals("int int0 = 1;\n", visitor.getCode());
    }
}
