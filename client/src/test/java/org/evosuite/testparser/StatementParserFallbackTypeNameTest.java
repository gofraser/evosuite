/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
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

