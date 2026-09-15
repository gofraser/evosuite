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
 * Chat model for Anthropic's {@code /v1/messages} endpoint.
 *
 * <p>Anthropic reports prompt-cache usage separately from ordinary prompt
 * tokens: {@code input_tokens} excludes any tokens that were served from or
 * written to the cache. To match OpenAI's {@code prompt_tokens} convention —
 * where "input tokens" means the whole prompt regardless of caching — this
 * class reports {@link LlmService.LlmResponse#getInputTokens()} as
 * {@code input_tokens + cache_read_input_tokens + cache_creation_input_tokens},
 * and {@link LlmService.LlmResponse#getCachedInputTokens()} as just
 * {@code cache_read_input_tokens} (tokens actually served from the cache, as
 * opposed to tokens newly written to it).
 */
public class AnthropicChatModel implements LlmService.ChatLanguageModel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final String modelName;
    private final String apiKey;
    private final String endpoint;
    private final double temperature;
    private final int maxTokens;
    private final int timeoutSeconds;
    private final JsonHttpClient httpClient;

    public AnthropicChatModel(String modelName, String apiKey, String baseUrl,
                              double temperature, int maxTokens, int timeoutSeconds) {
        this.modelName = modelName;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeoutSeconds = timeoutSeconds;
        this.endpoint = resolveEndpoint(baseUrl);
        this.httpClient = new JsonHttpClient();
    }

    private static String resolveEndpoint(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.isEmpty()) {
            base = DEFAULT_BASE_URL;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith("/v1/messages") ? base : base + "/v1/messages";
    }

    @Override
    public LlmService.LlmResponse generate(List<LlmMessage> messages, LlmFeature feature) throws IOException {
        String requestBody = buildRequestBody(messages);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", apiKey);
        headers.put("anthropic-version", ANTHROPIC_VERSION);
        int readTimeoutMs = JsonHttpClient.boundedReadTimeoutMillis(timeoutSeconds);
        int connectTimeoutMs = JsonHttpClient.connectTimeoutMillis(readTimeoutMs);
        String responseBody = httpClient.postJson(endpoint, headers, requestBody, connectTimeoutMs, readTimeoutMs);
        return parseResponse(responseBody);
    }

    private String buildRequestBody(List<LlmMessage> messages) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", modelName);
        root.put("max_tokens", maxTokens);
        root.put("temperature", temperature);

        String systemText = concatenateSystemMessages(messages);
        if (!systemText.isEmpty()) {
            if (Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT) {
                ArrayNode systemArray = root.putArray("system");
                ObjectNode systemBlock = systemArray.addObject();
                systemBlock.put("type", "text");
                systemBlock.put("text", systemText);
                systemBlock.putObject("cache_control").put("type", "ephemeral");
            } else {
                root.put("system", systemText);
            }
        }

        root.set("messages", buildAlternatingMessages(messages));
        return root.toString();
    }

    private static String concatenateSystemMessages(List<LlmMessage> messages) {
        StringBuilder builder = new StringBuilder();
        if (messages == null) {
            return "";
        }
        for (LlmMessage message : messages) {
            if (message != null && message.getRole() == LlmMessage.Role.SYSTEM) {
                if (builder.length() > 0) {
                    builder.append("\n\n");
                }
                builder.append(message.getContent());
            }
        }
        return builder.toString();
    }

    /**
     * Anthropic requires messages to strictly alternate user/assistant,
     * starting with user. Consecutive same-role messages (after dropping
     * SYSTEM ones, which are sent separately) are merged by joining their
     * content with "\n\n"; if the first remaining message would be from the
     * assistant, a synthetic "Continue." user message is prepended so the
     * conversation still starts with a user turn.
     */
    private static ArrayNode buildAlternatingMessages(List<LlmMessage> messages) {
        ArrayNode array = MAPPER.createArrayNode();
        if (messages == null) {
            return array;
        }
        String pendingRole = null;
        StringBuilder pendingContent = new StringBuilder();
        for (LlmMessage message : messages) {
            if (message == null || message.getRole() == LlmMessage.Role.SYSTEM) {
                continue;
            }
            String role = message.getRole() == LlmMessage.Role.ASSISTANT ? "assistant" : "user";
            if (role.equals(pendingRole)) {
                pendingContent.append("\n\n").append(message.getContent());
            } else {
                appendMessage(array, pendingRole, pendingContent);
                pendingRole = role;
                pendingContent = new StringBuilder(message.getContent());
            }
        }
        appendMessage(array, pendingRole, pendingContent);

        if (array.size() > 0 && "assistant".equals(array.get(0).get("role").asText())) {
            ObjectNode continueMessage = MAPPER.createObjectNode();
            continueMessage.put("role", "user");
            continueMessage.put("content", "Continue.");
            array.insert(0, continueMessage);
        }
        return array;
    }

    private static void appendMessage(ArrayNode array, String role, StringBuilder content) {
        if (role == null) {
            return;
        }
        ObjectNode node = array.addObject();
        node.put("role", role);
        node.put("content", content.toString());
    }

    private LlmService.LlmResponse parseResponse(String responseBody) throws IOException {
        JsonNode root = MAPPER.readTree(responseBody);
        StringBuilder text = new StringBuilder();
        JsonNode content = root.path("content");
        if (content.isArray()) {
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText(""))) {
                    text.append(block.path("text").asText(""));
                }
            }
        }
        JsonNode usage = root.path("usage");
        int reportedInputTokens = usage.path("input_tokens").asInt(0);
        int outputTokens = usage.path("output_tokens").asInt(0);
        int cacheReadInputTokens = usage.path("cache_read_input_tokens").asInt(0);
        int cacheCreationInputTokens = usage.path("cache_creation_input_tokens").asInt(0);
        int inputTokens = reportedInputTokens + cacheReadInputTokens + cacheCreationInputTokens;
        return new LlmService.LlmResponse(text.toString(), inputTokens, cacheReadInputTokens, outputTokens);
    }
}
