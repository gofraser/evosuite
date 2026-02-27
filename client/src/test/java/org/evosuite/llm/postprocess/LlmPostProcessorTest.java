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
package org.evosuite.llm.postprocess;

import org.evosuite.Properties;
import org.evosuite.llm.LlmService;
import org.evosuite.testcase.variable.name.VariableNameStrategy;
import org.evosuite.testcase.variable.name.VariableNameStrategyFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LlmPostProcessorTest {

    @BeforeEach
    void setUp() {
        Properties.LLM_PROVIDER = Properties.LlmProvider.NONE;
        Properties.LLM_RENAME_TESTS = false;
        Properties.LLM_RENAME_VARIABLES = false;
        Properties.LLM_NICEIFY_LITERALS = false;
        Properties.TARGET_CLASS = "com.example.Foo";
        LlmService.resetInstanceForTesting();
    }

    @AfterEach
    void tearDown() {
        Properties.LLM_RENAME_TESTS = false;
        Properties.LLM_RENAME_VARIABLES = false;
        Properties.LLM_NICEIFY_LITERALS = false;
        Properties.TEST_NAMING_STRATEGY = Properties.TestNamingStrategy.NUMBERED;
        Properties.VARIABLE_NAMING_STRATEGY = Properties.VariableNamingStrategy.TYPE_BASED;
        LlmService.resetInstanceForTesting();
    }

    // ---- Feature toggle tests ----

    @Test
    void isAnyFeatureEnabled_allDisabled() {
        assertFalse(LlmPostProcessor.isAnyFeatureEnabled());
    }

    @Test
    void isAnyFeatureEnabled_providerNone_alwaysFalse() {
        Properties.LLM_RENAME_TESTS = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.NONE;
        assertFalse(LlmPostProcessor.isAnyFeatureEnabled());
    }

    @Test
    void isAnyFeatureEnabled_renameTestsEnabled() {
        Properties.LLM_RENAME_TESTS = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        assertTrue(LlmPostProcessor.isAnyFeatureEnabled());
    }

    @Test
    void isAnyFeatureEnabled_renameVariablesEnabled() {
        Properties.LLM_RENAME_VARIABLES = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        assertTrue(LlmPostProcessor.isAnyFeatureEnabled());
    }

    @Test
    void isAnyFeatureEnabled_assertionStrategy_handledSeparately() {
        // ASSERTION_STRATEGY=LLM is handled in the assertion pipeline, not PostProcessor
        Properties.ASSERTION_STRATEGY = Properties.AssertionStrategy.LLM;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        assertFalse(LlmPostProcessor.isAnyFeatureEnabled(),
                "ASSERTION_STRATEGY=LLM should not activate PostProcessor");
    }

    @Test
    void isAnyFeatureEnabled_niceifyEnabled() {
        Properties.LLM_NICEIFY_LITERALS = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        assertTrue(LlmPostProcessor.isAnyFeatureEnabled());
    }

    // ---- Literal niceification: disabled ----

    @Test
    void runLiteralNiceification_disabledByDefault() {
        LlmPostProcessor processor = new LlmPostProcessor();
        // Should not throw
        processor.runLiteralNiceification(null);
    }

    // ---- Variable naming strategy factory wiring ----

    @Test
    void variableNamingFactory_returnsLlmStrategy_whenLlmEnumSet() {
        Properties.VARIABLE_NAMING_STRATEGY = Properties.VariableNamingStrategy.LLM;
        VariableNameStrategy strategy = VariableNameStrategyFactory.get(Properties.VariableNamingStrategy.LLM);
        assertInstanceOf(LlmVariableNameStrategy.class, strategy);
    }

    @Test
    void variableNamingFactory_overriddenByProperty() {
        Properties.VARIABLE_NAMING_STRATEGY = Properties.VariableNamingStrategy.TYPE_BASED;
        Properties.LLM_RENAME_VARIABLES = true;
        VariableNameStrategy strategy = VariableNameStrategyFactory.get();
        assertInstanceOf(LlmVariableNameStrategy.class, strategy);
    }

    @Test
    void variableNamingFactory_noOverrideWhenPropertyFalse() {
        Properties.VARIABLE_NAMING_STRATEGY = Properties.VariableNamingStrategy.TYPE_BASED;
        Properties.LLM_RENAME_VARIABLES = false;
        VariableNameStrategy strategy = VariableNameStrategyFactory.get();
        assertFalse(strategy instanceof LlmVariableNameStrategy);
    }

    // ---- Literal niceification: docs correctness ----

    @Test
    void runLiteralNiceification_enabledButNoLlm_gracefulNoop() {
        Properties.LLM_NICEIFY_LITERALS = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        // LLM service is not truly available (no API key)
        LlmPostProcessor processor = new LlmPostProcessor();
        // Should not throw even with feature enabled but LLM unavailable
        processor.runLiteralNiceification(null);
    }
}
