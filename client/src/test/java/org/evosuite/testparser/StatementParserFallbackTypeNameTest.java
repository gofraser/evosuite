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

import org.evosuite.Properties;
import org.evosuite.testcase.TestCodeVisitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatementParserFallbackTypeNameTest {

    private String savedClassPrefix;

    @BeforeEach
    void setUp() {
        savedClassPrefix = Properties.CLASS_PREFIX;
        // The fixture is nested in a package-private test class, so it is only nameable from its package.
        Properties.CLASS_PREFIX = "org.evosuite.testparser";
    }

    @AfterEach
    void tearDown() {
        Properties.CLASS_PREFIX = savedClassPrefix;
    }

    @Test
    void fallbackErasesIllegalTypeArgsForNonGenericRawClass() {
        TestParser parser = new TestParser(getClass().getClassLoader());
        parser.setMarkParsedFromLlm(true);

        ParseResult result = parser.parseTestMethodBody(
                "org.evosuite.testparser.StatementParserTest.StaticFieldTarget<java.lang.Object> value = unknown();",
                List.of("import org.evosuite.testparser.StatementParserTest;"));

        assertFalse(result.hasErrors(), "LLM best-effort should keep parsing: " + result.getDiagnostics());

        TestCodeVisitor visitor = new TestCodeVisitor();
        result.getTestCase().accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains("org.evosuite.testparser.StatementParserTest.StaticFieldTarget __llm_fallback"),
                "Fallback declaration should erase illegal type args on non-generic raw class:\n" + code);
        assertFalse(code.contains("StaticFieldTarget<java.lang.Object> __llm_fallback"),
                "Fallback declaration must not keep illegal type args for non-generic classes:\n" + code);
    }
}

