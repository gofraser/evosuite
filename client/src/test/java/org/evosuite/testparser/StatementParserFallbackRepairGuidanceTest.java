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

import org.evosuite.testcase.TestCodeVisitor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatementParserFallbackRepairGuidanceTest {

    @Test
    void llmUnresolvedVoidExpressionDoesNotPreserveRawUncompilableCode() {
        TestParser parser = new TestParser(getClass().getClassLoader());
        parser.setMarkParsedFromLlm(true);
        ParseResult r = parser.parseTestMethodBody(
                "new MissingHelper().work();",
                List.of("import java.lang.Object;"));

        assertFalse(r.hasErrors(), "LLM best-effort should avoid hard parse errors: " + r.getDiagnostics());

        TestCodeVisitor visitor = new TestCodeVisitor();
        r.getTestCase().accept(visitor);
        String code = visitor.getCode();

        assertFalse(code.contains("new MissingHelper().work();"),
                "Unresolved void expressions must not be preserved as raw code:\n" + code);
    }

    @Test
    void llmUnresolvedClassDiagnosticIncludesHelperTypeRepairActionHint() {
        TestParser parser = new TestParser(getClass().getClassLoader());
        parser.setMarkParsedFromLlm(true);
        ParseResult r = parser.parseTestMethodBody(
                "new Target();",
                List.of("import java.lang.Object;"));

        assertFalse(r.hasErrors(), "LLM best-effort should avoid hard parse errors: " + r.getDiagnostics());
        assertTrue(r.getDiagnostics().stream().anyMatch(d ->
                        (d.getMessage().toLowerCase().contains("cannot resolve class")
                                || d.getMessage().toLowerCase().contains("cannot resolve type"))
                                && d.getMessage().contains("LLM_REPAIR_ACTION_REQUIRED")
                                && d.getMessage().contains("do not invent local/helper types")),
                "Expected actionable repair hint for invented helper types: " + r.getDiagnostics());
    }
}
