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
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.testsuite;

import org.evosuite.Properties;

/** Applies a self-contained oracle arm for structural-suite replay. */
public final class OracleReplayConfiguration {

    private OracleReplayConfiguration() {
        // utility class
    }

    public static void apply(Properties.OracleReplayStrategy strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("oracle replay strategy must not be null");
        }

        // Replay arms measure assertions only. Readability edits and fallback
        // assertions would otherwise make the pure arms overlap; MUTATION_LLM
        // explicitly re-enables both assertion generators below.
        Properties.LLM_POSTPROCESSING_ENABLED = false;
        Properties.LLM_POSTPROCESSING_ASSERTIONS = false;
        Properties.LLM_POSTPROCESSING_TEST_NAMES = false;
        Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = false;
        Properties.LLM_POSTPROCESSING_COMMENTS = false;
        Properties.LLM_POSTPROCESSING_SECTION_BREAKS = false;
        Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK =
                Properties.LlmPostProcessingAssertionFallback.NONE;

        switch (strategy) {
            case NONE:
                Properties.ASSERTIONS = false;
                break;
            case ALL:
                Properties.ASSERTIONS = true;
                Properties.ASSERTION_STRATEGY = Properties.AssertionStrategy.ALL;
                break;
            case MUTATION:
                Properties.ASSERTIONS = true;
                Properties.ASSERTION_STRATEGY = Properties.AssertionStrategy.MUTATION;
                break;
            case LLM:
                Properties.ASSERTIONS = false;
                Properties.LLM_POSTPROCESSING_ENABLED = true;
                Properties.LLM_POSTPROCESSING_ASSERTIONS = true;
                break;
            case MUTATION_LLM:
                Properties.ASSERTIONS = true;
                Properties.ASSERTION_STRATEGY = Properties.AssertionStrategy.MUTATION;
                Properties.LLM_POSTPROCESSING_ENABLED = true;
                Properties.LLM_POSTPROCESSING_ASSERTIONS = true;
                break;
            default:
                throw new IllegalArgumentException("Unsupported oracle replay strategy " + strategy);
        }
    }
}
