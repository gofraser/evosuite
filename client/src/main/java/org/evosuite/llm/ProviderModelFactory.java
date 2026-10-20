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
import org.evosuite.llm.LlmService.ChatLanguageModel;
import org.evosuite.llm.provider.AnthropicChatModel;
import org.evosuite.llm.provider.OpenAiCompatibleChatModel;

/**
 * Builds the provider clients an {@link LlmService} sends its calls to, both at
 * start-up and when a stuck call forces the clients to be rebuilt.
 */
final class ProviderModelFactory {

    private ProviderModelFactory() {
    }

    /**
     * The primary model and, when the configuration declares a separate
     * operator model, the operator model; {@code operator} is {@code null}
     * otherwise, which is the pairing the {@link LlmService} constructor requires.
     */
    static final class Models {
        final ChatLanguageModel primary;
        final ChatLanguageModel operator;

        private Models(ChatLanguageModel primary, ChatLanguageModel operator) {
            this.primary = primary;
            this.operator = operator;
        }
    }

    /**
     * Builds both models of {@code config}; nothing is returned unless every
     * model could be constructed.
     *
     * @throws IllegalArgumentException if the provider is unsupported or a
     *                                  required setting is missing
     */
    static Models createModels(LlmConfiguration config) {
        ChatLanguageModel primary = createModel(config, config.getModel());
        ChatLanguageModel operator = config.hasSeparateOperatorModel()
                ? createModel(config, config.getOperatorModel())
                : null;
        return new Models(primary, operator);
    }

    private static ChatLanguageModel createModel(LlmConfiguration config, String modelName) {
        switch (config.getProvider()) {
            case OPENAI:
                return createOpenAiModel(config, modelName);
            case OLLAMA:
                return createOllamaModel(config, modelName);
            case ANTHROPIC:
                return createAnthropicModel(config, modelName);
            default:
                throw new IllegalArgumentException("Unsupported LLM provider: " + config.getProvider());
        }
    }

    private static ChatLanguageModel createOpenAiModel(LlmConfiguration config, String modelName) {
        String checkedModelName = requireNonBlank(modelName, "LLM model must be configured for OPENAI");
        String apiKey = requireNonBlank(config.getApiKey(), "LLM API key must be configured for OPENAI");
        return new OpenAiCompatibleChatModel(Properties.LlmProvider.OPENAI, checkedModelName, apiKey,
                config.getBaseUrl(), config.getTemperature(), config.getMaxTokens(),
                config.getTimeoutSeconds());
    }

    private static ChatLanguageModel createOllamaModel(LlmConfiguration config, String modelName) {
        String checkedModelName = requireNonBlank(modelName, "LLM model must be configured for OLLAMA");
        String baseUrl = requireNonBlank(config.getBaseUrl(), "LLM base URL must be configured for OLLAMA");
        return new OpenAiCompatibleChatModel(Properties.LlmProvider.OLLAMA, checkedModelName, config.getApiKey(),
                baseUrl, config.getTemperature(), config.getMaxTokens(), config.getTimeoutSeconds());
    }

    private static ChatLanguageModel createAnthropicModel(LlmConfiguration config, String modelName) {
        String checkedModelName = requireNonBlank(modelName, "LLM model must be configured for ANTHROPIC");
        String apiKey = requireNonBlank(config.getApiKey(), "LLM API key must be configured for ANTHROPIC");
        return new AnthropicChatModel(checkedModelName, apiKey, config.getBaseUrl(), config.getTemperature(),
                config.getMaxTokens(), config.getTimeoutSeconds());
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
