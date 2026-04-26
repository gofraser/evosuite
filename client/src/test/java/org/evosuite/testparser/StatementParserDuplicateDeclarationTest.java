/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.testparser;

import org.evosuite.testcase.TestCodeVisitor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatementParserDuplicateDeclarationTest {

    @Test
    void llmModeRenamesDuplicateLocalDeclarationsToKeepCodeCompilable() {
        TestParser parser = new TestParser(getClass().getClassLoader());
        parser.setMarkParsedFromLlm(true);

        ParseResult result = parser.parseTestMethodBody(
                "int[] values = null;\n" +
                        "int[] values = null;",
                List.of("import java.lang.*;"));

        assertFalse(result.hasErrors(), "LLM best-effort should keep parsing: " + result.getDiagnostics());

        TestCodeVisitor visitor = new TestCodeVisitor();
        result.getTestCase().accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains("int[] values = null;"), "First declaration should keep source name:\n" + code);
        assertTrue(code.matches("(?s).*int\\[\\] values_\\d+ = null;.*"),
                "Second declaration should be renamed to unique alias:\n" + code);
        assertFalse(code.contains("int[] values = null;\nint[] values = null;"),
                "Duplicate local declarations with the same name must be avoided:\n" + code);
    }
}

