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

class StatementParserStrictRepairHintTest {

    @Test
    void strictModeUnresolvedTypeIncludesRepairActionHint() {
        TestParser parser = new TestParser(getClass().getClassLoader());
        ParseResult result = parser.parseTestMethodBody(
                "InventedType value = null;",
                List.of("import java.lang.Object;"));

        assertTrue(result.hasErrors(), "Expected unresolved-type parse error");
        assertTrue(result.getDiagnostics().stream().anyMatch(d ->
                        d.getSeverity() == ParseDiagnostic.Severity.ERROR
                                && d.getMessage().contains("Cannot resolve type")
                                && d.getMessage().contains("LLM_REPAIR_ACTION_REQUIRED")
                                && d.getMessage().contains("do not invent local/helper types")),
                "Expected actionable repair hint for strict-mode unresolved type diagnostics: "
                        + result.getDiagnostics());
    }
}
