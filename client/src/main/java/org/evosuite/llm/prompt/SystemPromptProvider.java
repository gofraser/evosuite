/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.llm.prompt;

import org.evosuite.Properties;

/**
 * Produces the shared system prompt for all LLM interactions.
 */
public class SystemPromptProvider {

    /** Returns the system prompt string configured for the current output format. */
    public String getSystemPrompt() {
        String coverageDirective = " Your goal is to maximize code coverage of the class under test: "
                + "exercise every reachable method, branch, and edge case. "
                + "Include boundary values, null inputs, exception paths, and typical usage.";
        if (Properties.TEST_FORMAT == Properties.OutputFormat.JUNIT5) {
            return "You are an expert Java test generation assistant integrated into EvoSuite. "
                    + "Generate only valid Java JUnit5 test code using org.junit.jupiter.api annotations. "
                    + "Return code only."
                    + coverageDirective;
        }
        return "You are an expert Java test generation assistant integrated into EvoSuite. "
                + "Generate only valid Java JUnit4 test code using org.junit.Test annotations. "
                + "Return code only. Follow method signatures and generic types from the provided context strictly."
                + coverageDirective;
    }
}
