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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StatementParserPrivateAccessRepairHintTest {

    public static class PrivateFieldTarget {
        private Object value;
    }

    @Test
    void llmPrivateAccessDiagnosticIncludesRepairActionHint() {
        TestParser parser = new TestParser(getClass().getClassLoader());
        parser.setMarkParsedFromLlm(true);
        ParseResult r = parser.parseTestMethodBody(
                "StatementParserPrivateAccessRepairHintTest.PrivateFieldTarget target = "
                        + "new StatementParserPrivateAccessRepairHintTest.PrivateFieldTarget();\n"
                        + "Object payload = new Object();\n"
                        + "target.value = payload;",
                List.of(
                        "import java.lang.Object;",
                        "import org.evosuite.testparser.StatementParserPrivateAccessRepairHintTest;"
                ));

        assertTrue(r.hasErrors(), "Expected private access parse error");
        assertTrue(r.getDiagnostics().stream().anyMatch(d ->
                        d.getSeverity() == ParseDiagnostic.Severity.ERROR
                                && d.getMessage().contains("has private access")
                                && d.getMessage().contains("LLM_REPAIR_ACTION_REQUIRED")
                                && d.getMessage().contains("do not access private/protected members directly")),
                "Expected actionable LLM repair hint for private/protected-access diagnostics: "
                        + r.getDiagnostics());
    }
}

