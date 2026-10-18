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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.llm;

import org.evosuite.Properties;
import org.evosuite.llm.postprocess.PostProcessingOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmRunFeaturesTest {

    private Properties.LlmProvider provider;
    private boolean enabled;
    private boolean assertions;
    private boolean testNames;
    private boolean variableNames;
    private boolean comments;
    private boolean sectionBreaks;
    private boolean constantPool;
    private boolean objectPool;
    private boolean castClasses;

    @BeforeEach
    void save() {
        provider = Properties.LLM_PROVIDER;
        enabled = Properties.LLM_POSTPROCESSING_ENABLED;
        assertions = Properties.LLM_POSTPROCESSING_ASSERTIONS;
        testNames = Properties.LLM_POSTPROCESSING_TEST_NAMES;
        variableNames = Properties.LLM_POSTPROCESSING_VARIABLE_NAMES;
        comments = Properties.LLM_POSTPROCESSING_COMMENTS;
        sectionBreaks = Properties.LLM_POSTPROCESSING_SECTION_BREAKS;
        constantPool = Properties.LLM_ENRICH_CONSTANT_POOL;
        objectPool = Properties.LLM_ENRICH_OBJECT_POOL;
        castClasses = Properties.LLM_ENRICH_CAST_CLASSES;
    }

    @AfterEach
    void restore() {
        Properties.LLM_PROVIDER = provider;
        Properties.LLM_POSTPROCESSING_ENABLED = enabled;
        Properties.LLM_POSTPROCESSING_ASSERTIONS = assertions;
        Properties.LLM_POSTPROCESSING_TEST_NAMES = testNames;
        Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = variableNames;
        Properties.LLM_POSTPROCESSING_COMMENTS = comments;
        Properties.LLM_POSTPROCESSING_SECTION_BREAKS = sectionBreaks;
        Properties.LLM_ENRICH_CONSTANT_POOL = constantPool;
        Properties.LLM_ENRICH_OBJECT_POOL = objectPool;
        Properties.LLM_ENRICH_CAST_CLASSES = castClasses;
    }

    @Test
    void defaultsHaveNoLlmFeature() {
        assertFalse(LlmRunFeatures.providerConfigured());
        assertFalse(LlmRunFeatures.parsePathPossible());
        assertFalse(LlmRunFeatures.postProcessing());
        assertFalse(LlmRunFeatures.enrichment());
    }

    @Test
    void providerEnablesParsePath() {
        Properties.LLM_PROVIDER = Properties.LlmProvider.OLLAMA;

        assertTrue(LlmRunFeatures.providerConfigured());
        assertTrue(LlmRunFeatures.parsePathPossible());
    }

    @Test
    void enrichmentNeedsProviderAndOneSelectedKind() {
        Properties.LLM_ENRICH_OBJECT_POOL = true;
        assertFalse(LlmRunFeatures.enrichment());

        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        assertTrue(LlmRunFeatures.enrichment());

        Properties.LLM_ENRICH_OBJECT_POOL = false;
        assertFalse(LlmRunFeatures.enrichment());
    }

    @Test
    void postProcessingAgreesWithTheOptionsSnapshotForEveryCombination() {
        for (Properties.LlmProvider p : Properties.LlmProvider.values()) {
            for (int bits = 0; bits < 64; bits++) {
                Properties.LLM_PROVIDER = p;
                Properties.LLM_POSTPROCESSING_ENABLED = (bits & 1) != 0;
                Properties.LLM_POSTPROCESSING_ASSERTIONS = (bits & 2) != 0;
                Properties.LLM_POSTPROCESSING_TEST_NAMES = (bits & 4) != 0;
                Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = (bits & 8) != 0;
                Properties.LLM_POSTPROCESSING_COMMENTS = (bits & 16) != 0;
                Properties.LLM_POSTPROCESSING_SECTION_BREAKS = (bits & 32) != 0;

                PostProcessingOptions options = PostProcessingOptions.fromProperties();
                boolean expected = options.enabled()
                        && options.provider() != Properties.LlmProvider.NONE
                        && options.features().any();
                assertEquals(expected, LlmRunFeatures.postProcessing(), p + " " + bits);
            }
        }
    }
}
