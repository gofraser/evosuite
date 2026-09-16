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
package org.evosuite.llm;

import org.evosuite.Properties;
import org.evosuite.llm.provider.OpenAiCompatibleChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link LlmService} routes {@link LlmFeature#SEMANTIC_MUTATION} and
 * {@link LlmFeature#SEMANTIC_CROSSOVER} queries to the optional operator model while
 * every other feature (and every feature, when no operator model is configured) uses
 * the primary model.
 */
class LlmServiceOperatorModelTest {

    private Properties.LlmProvider originalProvider;
    private String originalModel;
    private String originalOperatorModel;
    private String originalApiKey;
    private String originalBaseUrl;

    @BeforeEach
    void setUp() {
        originalProvider = Properties.LLM_PROVIDER;
        originalModel = Properties.LLM_MODEL;
        originalOperatorModel = Properties.LLM_OPERATOR_MODEL;
        originalApiKey = Properties.LLM_API_KEY;
        originalBaseUrl = Properties.LLM_BASE_URL;
    }

    @AfterEach
    void tearDown() {
        Properties.LLM_PROVIDER = originalProvider;
        Properties.LLM_MODEL = originalModel;
        Properties.LLM_OPERATOR_MODEL = originalOperatorModel;
        Properties.LLM_API_KEY = originalApiKey;
        Properties.LLM_BASE_URL = originalBaseUrl;
        LlmService.setCompilerAvailableForTesting(null);
        LlmService.resetInstanceForTesting();
    }

    private static LlmConfiguration testConfiguration(String runId) {
        return new LlmConfiguration(
                Properties.LlmProvider.NONE, "primary-model", "", "", 0.0, 1024, 2, 0, 1,
                false, Paths.get("target/llm-test-traces"), runId);
    }

    private static RecordingChatLanguageModel recordingModel() {
        return new RecordingChatLanguageModel();
    }

    @Test
    void semanticMutationAndCrossoverHitOperatorModelOnly() {
        RecordingChatLanguageModel primary = recordingModel();
        RecordingChatLanguageModel operator = recordingModel();
        LlmConfiguration configuration = testConfiguration("operator-routing-test");
        LlmService service = new LlmService(primary, operator,
                new LlmBudgetCoordinator.Local(10), configuration,
                new LlmStatistics(), new LlmTraceRecorder(configuration));

        try {
            service.query(oneMessage(), LlmFeature.SEMANTIC_MUTATION);
            service.query(oneMessage(), LlmFeature.SEMANTIC_CROSSOVER);

            assertEquals(0, primary.callCount());
            assertEquals(2, operator.callCount());
            assertEquals(LlmFeature.SEMANTIC_MUTATION, operator.featuresCalled().get(0));
            assertEquals(LlmFeature.SEMANTIC_CROSSOVER, operator.featuresCalled().get(1));
        } finally {
            service.close();
        }
    }

    @Test
    void stagnationAndSeedingHitPrimaryModelOnly() {
        RecordingChatLanguageModel primary = recordingModel();
        RecordingChatLanguageModel operator = recordingModel();
        LlmConfiguration configuration = testConfiguration("primary-routing-test");
        LlmService service = new LlmService(primary, operator,
                new LlmBudgetCoordinator.Local(10), configuration,
                new LlmStatistics(), new LlmTraceRecorder(configuration));

        try {
            service.query(oneMessage(), LlmFeature.STAGNATION);
            service.query(oneMessage(), LlmFeature.SEEDING);

            assertEquals(2, primary.callCount());
            assertEquals(0, operator.callCount());
        } finally {
            service.close();
        }
    }

    @Test
    void everyFeatureHitsPrimaryModelWhenNoOperatorModelConfigured() {
        RecordingChatLanguageModel primary = recordingModel();
        LlmConfiguration configuration = testConfiguration("no-operator-model-test");
        LlmService service = new LlmService(primary,
                new LlmBudgetCoordinator.Local(10), configuration,
                new LlmStatistics(), new LlmTraceRecorder(configuration));

        try {
            service.query(oneMessage(), LlmFeature.SEMANTIC_MUTATION);
            service.query(oneMessage(), LlmFeature.SEMANTIC_CROSSOVER);
            service.query(oneMessage(), LlmFeature.STAGNATION);

            assertEquals(3, primary.callCount());
        } finally {
            service.close();
        }
    }

    @Test
    void realConstructionPathBuildsDistinctProviderInstancesForOperatorModel() {
        Properties.LLM_PROVIDER = Properties.LlmProvider.OLLAMA;
        Properties.LLM_MODEL = "primary";
        Properties.LLM_OPERATOR_MODEL = "operator";
        Properties.LLM_API_KEY = "";
        Properties.LLM_BASE_URL = "http://localhost:1";
        LlmService.setCompilerAvailableForTesting(Boolean.TRUE);
        LlmService.resetInstanceForTesting();

        LlmService service = LlmService.getInstance();

        assertTrue(service.isAvailable());
        LlmService.ChatLanguageModel primaryModel = service.modelForTesting(LlmFeature.STAGNATION);
        LlmService.ChatLanguageModel operatorModel = service.modelForTesting(LlmFeature.SEMANTIC_MUTATION);
        assertNotSame(primaryModel, operatorModel);
        assertTrue(primaryModel instanceof OpenAiCompatibleChatModel);
        assertTrue(operatorModel instanceof OpenAiCompatibleChatModel);
        assertEquals("primary", ((OpenAiCompatibleChatModel) primaryModel).getModelName());
        assertEquals("operator", ((OpenAiCompatibleChatModel) operatorModel).getModelName());
    }

    private static List<LlmMessage> oneMessage() {
        return Collections.singletonList(LlmMessage.user("hello"));
    }

    private static final class RecordingChatLanguageModel implements LlmService.ChatLanguageModel {
        private final List<LlmFeature> featuresCalled = new CopyOnWriteArrayList<>();

        @Override
        public LlmService.LlmResponse generate(List<LlmMessage> messages, LlmFeature feature) {
            featuresCalled.add(feature);
            return new LlmService.LlmResponse("ok", 1, 0, 1);
        }

        private int callCount() {
            return featuresCalled.size();
        }

        private List<LlmFeature> featuresCalled() {
            return featuresCalled;
        }
    }
}
