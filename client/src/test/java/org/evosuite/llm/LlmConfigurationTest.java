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
 */
package org.evosuite.llm;

import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmConfigurationTest {

    private final String savedOperatorModel = Properties.LLM_OPERATOR_MODEL;
    private final String savedModel = Properties.LLM_MODEL;

    @AfterEach
    void restoreProperties() {
        Properties.LLM_OPERATOR_MODEL = savedOperatorModel;
        Properties.LLM_MODEL = savedModel;
    }

    @Test
    void builderDefaultsMatchDocumentedPropertyDefaults() {
        LlmConfiguration configuration = LlmConfiguration.builder().build();

        assertEquals(0.7, configuration.getTemperature());
        assertEquals(32768, configuration.getMaxTokens());
        assertEquals(60, configuration.getTimeoutSeconds());
        assertEquals(2, configuration.getRetryMaxAttempts());
        assertEquals(250, configuration.getRetryBaseDelayMs());
    }

    @Test
    void builderRejectsInvalidProgrammaticValues() {
        assertThrows(IllegalArgumentException.class,
                () -> LlmConfiguration.builder().temperature(Double.NaN).build());
        assertThrows(IllegalArgumentException.class,
                () -> LlmConfiguration.builder().maxTokens(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> LlmConfiguration.builder().timeoutSeconds(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> LlmConfiguration.builder().retryMaxAttempts(-1).build());
        assertThrows(IllegalArgumentException.class,
                () -> LlmConfiguration.builder().retryBaseDelayMs(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> LlmConfiguration.builder().provider(null).build());
    }

    @Test
    void constructorNormalizesOptionalTextButKeepsConfigurationSnapshot() {
        LlmConfiguration configuration = new LlmConfiguration(
                Properties.LlmProvider.NONE, null, null, null, 0.7, 1, 1, 0, 1,
                false, java.nio.file.Paths.get("target"), " run ");

        assertEquals("", configuration.getModel());
        assertEquals("", configuration.getApiKey());
        assertEquals("", configuration.getBaseUrl());
        assertEquals("run", configuration.getRunId());
    }

    @Test
    void fromPropertiesDefaultOperatorModelIsEmptyAndResolvesToPrimaryModel() {
        Properties.LLM_MODEL = "primary-model";
        Properties.LLM_OPERATOR_MODEL = "";

        LlmConfiguration configuration = LlmConfiguration.fromProperties();

        assertEquals("", configuration.getOperatorModel());
        assertFalse(configuration.hasSeparateOperatorModel());
        assertEquals(configuration.getModel(), configuration.modelFor(LlmFeature.SEMANTIC_MUTATION));
        assertEquals(configuration.getModel(), configuration.modelFor(LlmFeature.SEMANTIC_CROSSOVER));
    }

    @Test
    void fromPropertiesPicksUpConfiguredOperatorModel() {
        Properties.LLM_MODEL = "primary-model";
        Properties.LLM_OPERATOR_MODEL = "operator-model";

        LlmConfiguration configuration = LlmConfiguration.fromProperties();

        assertEquals("operator-model", configuration.getOperatorModel());
        assertTrue(configuration.hasSeparateOperatorModel());
        assertEquals("operator-model", configuration.modelFor(LlmFeature.SEMANTIC_MUTATION));
        assertEquals("operator-model", configuration.modelFor(LlmFeature.SEMANTIC_CROSSOVER));
        assertEquals("primary-model", configuration.modelFor(LlmFeature.STAGNATION));
        assertEquals("primary-model", configuration.modelFor(LlmFeature.SEEDING));
    }

    @Test
    void operatorModelEqualToPrimaryModelIsNotTreatedAsSeparate() {
        Properties.LLM_MODEL = "same-model";
        Properties.LLM_OPERATOR_MODEL = "same-model";

        LlmConfiguration configuration = LlmConfiguration.fromProperties();

        assertFalse(configuration.hasSeparateOperatorModel());
        assertEquals("same-model", configuration.modelFor(LlmFeature.SEMANTIC_MUTATION));
    }

    @Test
    void builderRoundTripsOperatorModel() {
        LlmConfiguration configuration = LlmConfiguration.builder()
                .model("primary-model")
                .operatorModel("operator-model")
                .build();

        assertEquals("operator-model", configuration.getOperatorModel());
        assertTrue(configuration.hasSeparateOperatorModel());
        assertEquals("operator-model", configuration.modelFor(LlmFeature.SEMANTIC_CROSSOVER));
    }

    @Test
    void constructorTrimsOperatorModelWhitespace() {
        LlmConfiguration configuration = new LlmConfiguration(
                Properties.LlmProvider.NONE, "primary-model", "  operator-model  ", null, null,
                0.7, 1, 1, 0, 1, false, java.nio.file.Paths.get("target"), "run");

        assertEquals("operator-model", configuration.getOperatorModel());
    }
}
