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
package org.evosuite.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.evosuite.Properties;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat model for OpenAI's {@code /v1/chat/completions} endpoint and for
 * Ollama, which serves the same OpenAI-compatible API under {@code /v1}.
 */
public class OpenAiCompatibleChatModel implements LlmService.ChatLanguageModel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";

    private final String modelName;
    private final String apiKey;
    private final String endpoint;
    private final double temperature;
    private final int maxTokens;
    private final int timeoutSeconds;
    private final JsonHttpClient httpClient;

    public OpenAiCompatibleChatModel(Properties.LlmProvider provider, String modelName, String apiKey,
                                     String baseUrl, double temperature, int maxTokens, int timeoutSeconds) {
        this.modelName = modelName;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeoutSeconds = timeoutSeconds;
        this.endpoint = resolveEndpoint(provider, baseUrl);
        this.httpClient = new JsonHttpClient();
    }

    private static String resolveEndpoint(Properties.LlmProvider provider, String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (provider == Properties.LlmProvider.OLLAMA) {
            base = stripTrailingSlash(base);
            if (!base.endsWith("/v1")) {
                base = base + "/v1";
            }
        } else {
            if (base.isEmpty()) {
                base = DEFAULT_OPENAI_BASE_URL;
            }
            base = stripTrailingSlash(base);
        }
        return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @Override
    public LlmService.LlmResponse generate(List<LlmMessage> messages, LlmFeature feature) throws IOException {
        String requestBody = buildRequestBody(messages);
        Map<String, String> headers = new LinkedHashMap<>();
        if (!apiKey.isEmpty()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        int readTimeoutMs = JsonHttpClient.boundedReadTimeoutMillis(timeoutSeconds);
        int connectTimeoutMs = JsonHttpClient.connectTimeoutMillis(readTimeoutMs);
        String responseBody = httpClient.postJson(endpoint, headers, requestBody, connectTimeoutMs, readTimeoutMs);
        return parseResponse(responseBody);
    }

    private String buildRequestBody(List<LlmMessage> messages) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", modelName);
        ArrayNode messagesNode = root.putArray("messages");
        if (messages != null) {
            for (LlmMessage message : messages) {
                if (message == null) {
                    continue;
                }
                ObjectNode messageNode = messagesNode.addObject();
                messageNode.put("role", roleName(message.getRole()));
                messageNode.put("content", message.getContent());
            }
        }
        root.put("temperature", temperature);
        root.put("stream", false);
        if (LlmService.usesMaxCompletionTokens(modelName)) {
            root.put("max_completion_tokens", maxTokens);
        } else {
            root.put("max_tokens", maxTokens);
        }
        return root.toString();
    }

    private static String roleName(LlmMessage.Role role) {
        switch (role) {
            case SYSTEM:
                return "system";
            case ASSISTANT:
                return "assistant";
            case USER:
            default:
                return "user";
        }
    }

    /**
     * Parses an OpenAI-compatible chat completion body, rejecting anything
     * that does not conform to the expected shape rather than silently
     * falling back to an empty response. Package-private and static so tests
     * can exercise the parsing rules directly, without a network round trip.
     */
    static LlmService.LlmResponse parseResponse(String responseBody) throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(responseBody);
        } catch (IOException e) {
            throw new LlmProtocolException("could not parse OpenAI response as JSON (" + e.getMessage() + ")",
                    responseBody);
        }
        if (root == null || !root.isObject()) {
            throw new LlmProtocolException("OpenAI response is not a JSON object", responseBody);
        }
        JsonNode error = root.path("error");
        if (error.isObject()) {
            throw new LlmProtocolException("OpenAI response reported an error: " + errorMessage(error),
                    responseBody);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() == 0) {
            throw new LlmProtocolException("OpenAI response has no choices", responseBody);
        }
        JsonNode content = choices.get(0).path("message").path("content");
        if (!content.isTextual()) {
            throw new LlmProtocolException("OpenAI response choice has no textual message content", responseBody);
        }
        String text = content.asText();

        JsonNode usage = root.path("usage");
        int inputTokens = 0;
        int outputTokens = 0;
        int cachedInputTokens = 0;
        if (!usage.isMissingNode() && !usage.isNull()) {
            // Some OpenAI-compatible servers omit usage entirely; that is
            // tolerated as zero counts, but a present-and-wrong-shaped usage
            // block means the server sent something we cannot trust.
            if (!usage.isObject()) {
                throw new LlmProtocolException("OpenAI response usage is not a JSON object", responseBody);
            }
            inputTokens = requireNumericIfPresent(usage, "prompt_tokens", responseBody);
            outputTokens = requireNumericIfPresent(usage, "completion_tokens", responseBody);
            JsonNode promptDetails = usage.path("prompt_tokens_details");
            if (promptDetails.isObject()) {
                cachedInputTokens = requireNumericIfPresent(promptDetails, "cached_tokens", responseBody);
            }
        }
        return new LlmService.LlmResponse(text, inputTokens, cachedInputTokens, outputTokens);
    }

    private static int requireNumericIfPresent(JsonNode parent, String field, String responseBody)
            throws LlmProtocolException {
        JsonNode value = parent.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return 0;
        }
        if (!value.isNumber()) {
            throw new LlmProtocolException("OpenAI response field '" + field + "' is not numeric", responseBody);
        }
        return value.asInt();
    }

    private static String errorMessage(JsonNode error) {
        String message = error.path("message").asText("");
        return message.isEmpty() ? error.path("type").asText("unknown error") : message;
    }
}
