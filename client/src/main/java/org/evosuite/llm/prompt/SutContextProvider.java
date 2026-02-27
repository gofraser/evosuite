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

import org.evosuite.setup.TestCluster;

import java.util.Optional;

/**
 * Abstraction for extracting CUT context to include in LLM prompts.
 * Each implementation provides a different representation of the class under test.
 */
public interface SutContextProvider {

    /**
     * Extract context for the given class.
     *
     * @param className fully qualified class name
     * @param cluster   current test cluster (may be null)
     * @return context text, or empty if extraction fails
     */
    Optional<String> getContext(String className, TestCluster cluster);

    /**
     * Human-readable label for the context mode (used in prompt formatting).
     */
    String modeLabel();
}
