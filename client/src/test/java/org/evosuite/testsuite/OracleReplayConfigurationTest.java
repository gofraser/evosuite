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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OracleReplayConfigurationTest {

    private boolean assertions;
    private Properties.AssertionStrategy assertionStrategy;
    private boolean llmEnabled;
    private boolean llmAssertions;
    private boolean llmTestNames;
    private boolean llmVariableNames;
    private boolean llmComments;
    private boolean llmSectionBreaks;
    private Properties.LlmPostProcessingAssertionFallback fallback;

    @BeforeEach
    void saveProperties() {
        assertions = Properties.ASSERTIONS;
        assertionStrategy = Properties.ASSERTION_STRATEGY;
        llmEnabled = Properties.LLM_POSTPROCESSING_ENABLED;
        llmAssertions = Properties.LLM_POSTPROCESSING_ASSERTIONS;
        llmTestNames = Properties.LLM_POSTPROCESSING_TEST_NAMES;
        llmVariableNames = Properties.LLM_POSTPROCESSING_VARIABLE_NAMES;
        llmComments = Properties.LLM_POSTPROCESSING_COMMENTS;
        llmSectionBreaks = Properties.LLM_POSTPROCESSING_SECTION_BREAKS;
        fallback = Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK;
    }

    @AfterEach
    void restoreProperties() {
        Properties.ASSERTIONS = assertions;
        Properties.ASSERTION_STRATEGY = assertionStrategy;
        Properties.LLM_POSTPROCESSING_ENABLED = llmEnabled;
        Properties.LLM_POSTPROCESSING_ASSERTIONS = llmAssertions;
        Properties.LLM_POSTPROCESSING_TEST_NAMES = llmTestNames;
        Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = llmVariableNames;
        Properties.LLM_POSTPROCESSING_COMMENTS = llmComments;
        Properties.LLM_POSTPROCESSING_SECTION_BREAKS = llmSectionBreaks;
        Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK = fallback;
    }

    @Test
    void allArmEnablesOnlyAllAssertions() {
        OracleReplayConfiguration.apply(Properties.OracleReplayStrategy.ALL);
        assertTrue(Properties.ASSERTIONS);
        assertEquals(Properties.AssertionStrategy.ALL, Properties.ASSERTION_STRATEGY);
        assertOracleOnlyLlmDisabled();
    }

    @Test
    void mutationArmEnablesOnlyMutationAssertions() {
        OracleReplayConfiguration.apply(Properties.OracleReplayStrategy.MUTATION);
        assertTrue(Properties.ASSERTIONS);
        assertEquals(Properties.AssertionStrategy.MUTATION, Properties.ASSERTION_STRATEGY);
        assertOracleOnlyLlmDisabled();
    }

    @Test
    void llmArmEnablesOnlyLlmAssertionsWithoutFallback() {
        OracleReplayConfiguration.apply(Properties.OracleReplayStrategy.LLM);
        assertFalse(Properties.ASSERTIONS);
        assertTrue(Properties.LLM_POSTPROCESSING_ENABLED);
        assertTrue(Properties.LLM_POSTPROCESSING_ASSERTIONS);
        assertFalse(Properties.LLM_POSTPROCESSING_TEST_NAMES);
        assertFalse(Properties.LLM_POSTPROCESSING_VARIABLE_NAMES);
        assertFalse(Properties.LLM_POSTPROCESSING_COMMENTS);
        assertFalse(Properties.LLM_POSTPROCESSING_SECTION_BREAKS);
        assertEquals(Properties.LlmPostProcessingAssertionFallback.NONE,
                Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK);
    }

    @Test
    void noneArmDisablesEveryOracle() {
        OracleReplayConfiguration.apply(Properties.OracleReplayStrategy.NONE);
        assertFalse(Properties.ASSERTIONS);
        assertOracleOnlyLlmDisabled();
    }

    private static void assertOracleOnlyLlmDisabled() {
        assertFalse(Properties.LLM_POSTPROCESSING_ENABLED);
        assertFalse(Properties.LLM_POSTPROCESSING_ASSERTIONS);
        assertFalse(Properties.LLM_POSTPROCESSING_TEST_NAMES);
        assertFalse(Properties.LLM_POSTPROCESSING_VARIABLE_NAMES);
        assertFalse(Properties.LLM_POSTPROCESSING_COMMENTS);
        assertFalse(Properties.LLM_POSTPROCESSING_SECTION_BREAKS);
        assertEquals(Properties.LlmPostProcessingAssertionFallback.NONE,
                Properties.LLM_POSTPROCESSING_ASSERTION_FALLBACK);
    }
}
