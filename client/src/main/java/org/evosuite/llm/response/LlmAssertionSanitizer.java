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
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.UninterpretedStatement;

import java.util.regex.Pattern;

/**
 * Removes parser-retained assertion artifacts from LLM-generated test cases.
 */
public final class LlmAssertionSanitizer {

    private static final Pattern BARE_ASSERTION_CALL_PATTERN = Pattern.compile(
            "^\\s*(?:assertAll|assertArrayEquals|assertDoesNotThrow|assertEquals|assertFalse|assertInstanceOf|"
                    + "assertIterableEquals|assertLinesMatch|assertNotEquals|assertNotNull|assertNotSame|"
                    + "assertNull|assertSame|assertThat|assertThrows|assertTimeout|"
                    + "assertTimeoutPreemptively|assertTrue|fail)\\s*\\(.*",
            Pattern.DOTALL);

    private static final Pattern QUALIFIED_ASSERTION_CALL_PATTERN = Pattern.compile(
            "^\\s*(?:[A-Za-z_$][\\w$]*\\.)*(?:Assert|Assertions|MatcherAssert)\\."
                    + "(?:assert[A-Za-z0-9_]*|fail)\\s*\\(.*",
            Pattern.DOTALL);

    private LlmAssertionSanitizer() {
        // utility class
    }

    /**
     * Strips assertions from a test case in-place.
     *
     * @param testCase the test case to sanitize
     * @return number of removed assertion artifacts
     */
    public static int sanitize(TestCase testCase) {
        if (testCase == null) {
            return 0;
        }
        int removed = 0;

        // Remove attached EvoSuite assertion objects.
        int before = testCase.getAssertions().size();
        testCase.removeAssertions();
        removed += Math.max(0, before);

        // Remove raw assertion-only uninterpreted statements.
        for (int i = testCase.size() - 1; i >= 0; i--) {
            Statement statement = testCase.getStatement(i);
            if (!(statement instanceof UninterpretedStatement)) {
                continue;
            }
            String code = ((UninterpretedStatement) statement).getSourceCode();
            if (isAssertionOnlySnippet(code)) {
                testCase.remove(i);
                removed++;
            }
        }
        return removed;
    }

    static boolean isAssertionOnlySnippet(String code) {
        if (code == null) {
            return false;
        }
        String trimmed = code.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (trimmed.startsWith("assert ")) {
            return true;
        }
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return BARE_ASSERTION_CALL_PATTERN.matcher(trimmed).matches()
                || QUALIFIED_ASSERTION_CALL_PATTERN.matcher(trimmed).matches();
    }
}
