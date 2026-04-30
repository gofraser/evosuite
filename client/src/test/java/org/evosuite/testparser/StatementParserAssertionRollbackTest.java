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
package org.evosuite.testparser;

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.UninterpretedStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class StatementParserAssertionRollbackTest {

    public static class RollbackTarget {
        public int number() {
            return 7;
        }

        public Object object() {
            return new Object();
        }
    }

    private TestParser parser;

    @BeforeEach
    void setUp() {
        parser = new TestParser(getClass().getClassLoader());
    }

    @Test
    void parseAssertEqualsFallbackRollsBackInlineMethodMaterialization() {
        assertFallbackRollbackClearsInlineMethodCall(
                "Object expected = new Object();\n"
                        + "StatementParserAssertionRollbackTest.RollbackTarget target = "
                        + "new StatementParserAssertionRollbackTest.RollbackTarget();\n",
                "assertEquals(expected, target.number());");
    }

    @Test
    void parseAssertNotEqualsFallbackRollsBackInlineMethodMaterialization() {
        assertFallbackRollbackClearsInlineMethodCall(
                "StatementParserAssertionRollbackTest.RollbackTarget target = "
                        + "new StatementParserAssertionRollbackTest.RollbackTarget();\n",
                "assertNotEquals(0, target.number());");
    }

    @Test
    void parseAssertSameFallbackRollsBackInlineMethodMaterialization() {
        assertFallbackRollbackClearsInlineMethodCall(
                "StatementParserAssertionRollbackTest.RollbackTarget target = "
                        + "new StatementParserAssertionRollbackTest.RollbackTarget();\n",
                "assertSame(new Object(), target.object());");
    }

    @Test
    void parseAssertNotSameFallbackRollsBackInlineMethodMaterialization() {
        assertFallbackRollbackClearsInlineMethodCall(
                "StatementParserAssertionRollbackTest.RollbackTarget target = "
                        + "new StatementParserAssertionRollbackTest.RollbackTarget();\n",
                "assertNotSame(new Object(), target.object());");
    }

    private void assertFallbackRollbackClearsInlineMethodCall(String setup, String assertionSource) {
        ParseResult result = parser.parseTestMethodBody(
                setup + assertionSource,
                List.of(
                        "import java.util.*;",
                        "import static org.junit.Assert.*;",
                        "import static org.junit.jupiter.api.Assertions.*;",
                        "import org.evosuite.testparser.StatementParserAssertionRollbackTest;"
                ));
        TestCase testCase = result.getTestCase();

        assertFalse(result.hasErrors(), "Errors: " + result.getDiagnostics());
        assertEquals(0, countMethodStatements(testCase),
                "Assertion fallback should not leak materialized inline method calls:\n" + testCase.toCode());

        Statement preserved = testCase.getStatement(testCase.size() - 1);
        assertInstanceOf(UninterpretedStatement.class, preserved);
        assertEquals(assertionSource, ((UninterpretedStatement) preserved).getSourceCode());
    }

    private int countMethodStatements(TestCase testCase) {
        int count = 0;
        for (int i = 0; i < testCase.size(); i++) {
            if (testCase.getStatement(i) instanceof MethodStatement) {
                count++;
            }
        }
        return count;
    }
}
