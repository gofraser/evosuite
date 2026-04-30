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

import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticKindTest {

    @Test
    void everyDiagnosticKindRendersTemplateAndRepairAction() {
        for (DiagnosticKind kind : DiagnosticKind.values()) {
            String rendered = kind.format("details");
            String enriched = kind.appendRepairAction(rendered);

            assertTrue(enriched.contains(kind.template()),
                    "Rendered diagnostic should include template for " + kind + ": " + enriched);
            assertTrue(enriched.contains(DiagnosticKind.ACTION_REQUIRED_PREFIX),
                    "Rendered diagnostic should include repair-action marker for " + kind + ": " + enriched);
        }
    }
}
