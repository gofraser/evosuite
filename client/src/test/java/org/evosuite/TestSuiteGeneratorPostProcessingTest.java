/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3.0 of the License, or (at your
 * option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSuiteGeneratorPostProcessingTest {

    private boolean originalAssertions;
    private boolean originalPostProcessingEnabled;
    private boolean originalPostProcessingAssertions;
    private Properties.LlmProvider originalProvider;

    @BeforeEach
    void saveProperties() {
        originalAssertions = Properties.ASSERTIONS;
        originalPostProcessingEnabled = Properties.LLM_POSTPROCESSING_ENABLED;
        originalPostProcessingAssertions = Properties.LLM_POSTPROCESSING_ASSERTIONS;
        originalProvider = Properties.LLM_PROVIDER;
    }

    @AfterEach
    void restoreProperties() {
        Properties.ASSERTIONS = originalAssertions;
        Properties.LLM_POSTPROCESSING_ENABLED = originalPostProcessingEnabled;
        Properties.LLM_POSTPROCESSING_ASSERTIONS = originalPostProcessingAssertions;
        Properties.LLM_PROVIDER = originalProvider;
    }

    @Test
    void unifiedAssertionsSuppressEagerStandardAssertionGeneration() {
        Properties.ASSERTIONS = true;
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_POSTPROCESSING_ASSERTIONS = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;

        assertFalse(TestSuiteGenerator.shouldGenerateStandardAssertions());

        Properties.LLM_POSTPROCESSING_ASSERTIONS = false;
        assertTrue(TestSuiteGenerator.shouldGenerateStandardAssertions());
    }

    @Test
    void unavailableUnifiedAssertionsDoNotSuppressStandardAssertions() {
        Properties.ASSERTIONS = true;
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_POSTPROCESSING_ASSERTIONS = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.NONE;

        assertTrue(TestSuiteGenerator.shouldGenerateStandardAssertions());
    }
}
