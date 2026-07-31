/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 */
package org.evosuite.llm.postprocess;

import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostProcessingOptionsTest {

    private boolean originalEnabled;
    private Properties.LlmProvider originalProvider;
    private int originalCallTimeout;
    private int originalObservationChars;
    private int originalMinimumFreeMemory;
    private String originalStaticAllowlist;

    @BeforeEach
    void saveProperties() {
        originalEnabled = Properties.LLM_POSTPROCESSING_ENABLED;
        originalProvider = Properties.LLM_PROVIDER;
        originalCallTimeout = Properties.LLM_TIMEOUT_SECONDS;
        originalObservationChars = Properties.LLM_POSTPROCESSING_MAX_OBSERVATION_CHARS;
        originalMinimumFreeMemory = Properties.MIN_FREE_MEM;
        originalStaticAllowlist = Properties.LLM_POSTPROCESSING_PURE_STATIC_ALLOWLIST;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_POSTPROCESSING_ENABLED = originalEnabled;
        Properties.LLM_PROVIDER = originalProvider;
        Properties.LLM_TIMEOUT_SECONDS = originalCallTimeout;
        Properties.LLM_POSTPROCESSING_MAX_OBSERVATION_CHARS = originalObservationChars;
        Properties.MIN_FREE_MEM = originalMinimumFreeMemory;
        Properties.LLM_POSTPROCESSING_PURE_STATIC_ALLOWLIST = originalStaticAllowlist;
    }

    @Test
    void fromPropertiesFreezesPhaseBoundaryValuesAndNormalizesAllowlists() {
        Properties.LLM_POSTPROCESSING_ENABLED = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_TIMEOUT_SECONDS = 17;
        Properties.LLM_POSTPROCESSING_MAX_OBSERVATION_CHARS = 321;
        Properties.MIN_FREE_MEM = 1234;
        Properties.LLM_POSTPROCESSING_PURE_STATIC_ALLOWLIST = " max, ,min,max ";

        PostProcessingOptions options = PostProcessingOptions.fromProperties();

        Properties.LLM_POSTPROCESSING_ENABLED = false;
        Properties.LLM_PROVIDER = Properties.LlmProvider.NONE;
        Properties.LLM_TIMEOUT_SECONDS = 0;
        Properties.LLM_POSTPROCESSING_MAX_OBSERVATION_CHARS = 1;
        Properties.MIN_FREE_MEM = 1;
        Properties.LLM_POSTPROCESSING_PURE_STATIC_ALLOWLIST = "changed";

        assertTrue(options.enabled());
        assertEquals(Properties.LlmProvider.OPENAI, options.provider());
        assertEquals(17, options.callTimeoutSeconds());
        assertEquals(1234L, options.minimumFreeMemoryBytes());
        assertEquals(321, options.contextLimits().observationChars());
        assertEquals(2, options.assertionPolicy().pureStaticAllowlist().size());
        assertTrue(options.assertionPolicy().pureStaticAllowlist().contains("max"));
        assertTrue(options.assertionPolicy().pureStaticAllowlist().contains("min"));
    }

    @Test
    void groupedConstructorsClampNegativeSafetyBounds() {
        PostProcessingOptions.AssertionPolicy policy = new PostProcessingOptions.AssertionPolicy(
                -1, -2, null, false, -3, -4, -5, null, false, null,
                -6, -7, -8, -9, -10, -11);
        PostProcessingOptions.ContextLimits limits = new PostProcessingOptions.ContextLimits(
                -1, -2, -3, -4, -5, -6, -7, -8, -9, -10);

        assertEquals(0, policy.maxAssertions());
        assertEquals(0, policy.maxExpressionNodes());
        assertEquals(0, policy.compileTimeoutMs());
        assertEquals(0, limits.observationChars());
        assertFalse(policy.allowChainedCalls());
    }
}
