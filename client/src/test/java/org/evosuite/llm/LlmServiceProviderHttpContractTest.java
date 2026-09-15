/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors.
 */
package org.evosuite.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wire-level contract test for the in-house HTTP provider clients. */
class LlmServiceProviderHttpContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Properties.LlmProvider originalProvider = Properties.LLM_PROVIDER;
    private final String originalModel = Properties.LLM_MODEL;
    private final String originalApiKey = Properties.LLM_API_KEY;
    private final String originalBaseUrl = Properties.LLM_BASE_URL;
    private final int originalTimeout = Properties.LLM_TIMEOUT_SECONDS;
    private final int originalRetries = Properties.LLM_RETRY_MAX_ATTEMPTS;
    private final boolean originalTraceEnabled = Properties.LLM_TRACE_ENABLED;
    private final boolean originalPromptCacheStaticContext = Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT;

    @AfterEach
    void restoreProperties() {
        LlmService.resetInstanceForTesting();
        Properties.LLM_PROVIDER = originalProvider;
        Properties.LLM_MODEL = originalModel;
        Properties.LLM_API_KEY = originalApiKey;
        Properties.LLM_BASE_URL = originalBaseUrl;
        Properties.LLM_TIMEOUT_SECONDS = originalTimeout;
        Properties.LLM_RETRY_MAX_ATTEMPTS = originalRetries;
        Properties.LLM_TRACE_ENABLED = originalTraceEnabled;
        Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT = originalPromptCacheStaticContext;
    }

    @Test
    void openAiCompatibleEndpointReceivesMessagesAndReturnsUsage() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(readUtf8(exchange.getRequestBody()));
            byte[] response = ("{\"id\":\"chatcmpl-local\",\"object\":\"chat.completion\","
                    + "\"created\":0,\"model\":\"local-model\",\"choices\":[{\"index\":0,"
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"contract-ok\"},"
                    + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,"
                    + "\"completion_tokens\":2,\"total_tokens\":5}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
            Properties.LLM_MODEL = "local-model";
            Properties.LLM_API_KEY = "local-token";
            Properties.LLM_BASE_URL = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            Properties.LLM_TIMEOUT_SECONDS = 3;
            Properties.LLM_RETRY_MAX_ATTEMPTS = 0;
            Properties.LLM_TRACE_ENABLED = false;
            LlmService.resetInstanceForTesting();

            LlmService service = LlmService.getInstance();
            String response = service.query(Collections.singletonList(
                    LlmMessage.user("generate a test")), LlmFeature.TEST_REPAIR);

            assertEquals("contract-ok", response);
            assertEquals("/v1/chat/completions", requestPath.get());
            assertEquals("Bearer local-token", authorization.get());
            assertTrue(requestBody.get().contains("generate a test"));
            assertTrue(requestBody.get().contains("local-model"));
            assertEquals(3L, service.getStatistics().getInputTokens());
            assertEquals(2L, service.getStatistics().getOutputTokens());
            assertEquals(0L, service.getStatistics().getCachedInputTokens());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openAiCompatibleEndpointReportsCachedInputTokens() throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            readUtf8(exchange.getRequestBody());
            byte[] response = ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"cached-ok\"}}],\"usage\":{\"prompt_tokens\":10,"
                    + "\"completion_tokens\":4,\"prompt_tokens_details\":{\"cached_tokens\":2}}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
            Properties.LLM_MODEL = "local-model";
            Properties.LLM_API_KEY = "local-token";
            Properties.LLM_BASE_URL = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            Properties.LLM_TIMEOUT_SECONDS = 3;
            Properties.LLM_RETRY_MAX_ATTEMPTS = 0;
            Properties.LLM_TRACE_ENABLED = false;
            LlmService.resetInstanceForTesting();

            LlmService service = LlmService.getInstance();
            String response = service.query(Collections.singletonList(
                    LlmMessage.user("generate a test")), LlmFeature.SEEDING);

            assertEquals("cached-ok", response);
            assertEquals(10L, service.getStatistics().getInputTokens());
            assertEquals(2L, service.getStatistics().getCachedInputTokens());
            LlmStatistics.FeatureSnapshot snapshot =
                    service.getStatistics().getFeatureSnapshots().get(LlmFeature.SEEDING);
            assertEquals(2L, snapshot.getCachedInputTokens());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ollamaEndpointOmitsAuthorizationAndUsesLegacyMaxTokens() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(readUtf8(exchange.getRequestBody()));
            byte[] response = ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"ollama-ok\"}}],\"usage\":{\"prompt_tokens\":4,"
                    + "\"completion_tokens\":1}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            Properties.LLM_PROVIDER = Properties.LlmProvider.OLLAMA;
            Properties.LLM_MODEL = "llama3.1";
            Properties.LLM_API_KEY = "";
            Properties.LLM_BASE_URL = "http://127.0.0.1:" + server.getAddress().getPort();
            Properties.LLM_TIMEOUT_SECONDS = 3;
            Properties.LLM_RETRY_MAX_ATTEMPTS = 0;
            Properties.LLM_TRACE_ENABLED = false;
            LlmService.resetInstanceForTesting();

            LlmService service = LlmService.getInstance();
            String response = service.query(Collections.singletonList(
                    LlmMessage.user("generate a test")), LlmFeature.TEST_REPAIR);

            assertEquals("ollama-ok", response);
            assertEquals("/v1/chat/completions", requestPath.get());
            assertNull(authorization.get());
            assertTrue(requestBody.get().contains("\"max_tokens\""));
            assertFalse(requestBody.get().contains("max_completion_tokens"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anthropicEndpointSendsCacheControlWhenStaticContextCachingEnabled() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        AtomicReference<String> versionHeader = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            versionHeader.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            requestBody.set(readUtf8(exchange.getRequestBody()));
            byte[] response = ("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                    + "\"usage\":{\"input_tokens\":5,\"output_tokens\":2,"
                    + "\"cache_read_input_tokens\":40,\"cache_creation_input_tokens\":0}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT = true;
            Properties.LLM_PROVIDER = Properties.LlmProvider.ANTHROPIC;
            Properties.LLM_MODEL = "claude-3-5-sonnet-latest";
            Properties.LLM_API_KEY = "anthropic-token";
            Properties.LLM_BASE_URL = "http://127.0.0.1:" + server.getAddress().getPort();
            Properties.LLM_TIMEOUT_SECONDS = 3;
            Properties.LLM_RETRY_MAX_ATTEMPTS = 0;
            Properties.LLM_TRACE_ENABLED = false;
            LlmService.resetInstanceForTesting();

            LlmService service = LlmService.getInstance();
            String response = service.query(Arrays.asList(
                    LlmMessage.system("system context"),
                    LlmMessage.user("hi"),
                    LlmMessage.assistant("hello"),
                    LlmMessage.user("continue")), LlmFeature.TEST_REPAIR);

            assertEquals("ok", response);
            assertEquals("/v1/messages", requestPath.get());
            assertEquals("anthropic-token", apiKeyHeader.get());
            assertEquals("2023-06-01", versionHeader.get());
            assertTrue(requestBody.get().contains("cache_control"));
            assertTrue(requestBody.get().contains("\"system\""));
            assertEquals(45L, service.getStatistics().getInputTokens());
            assertEquals(40L, service.getStatistics().getCachedInputTokens());
            assertEquals(2L, service.getStatistics().getOutputTokens());

            JsonNode body = MAPPER.readTree(requestBody.get());
            JsonNode messages = body.path("messages");
            assertEquals(3, messages.size());
            assertEquals("user", messages.get(0).path("role").asText());
            assertEquals("assistant", messages.get(1).path("role").asText());
            assertEquals("user", messages.get(2).path("role").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anthropicEndpointOmitsCacheControlWhenStaticContextCachingDisabled() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requestBody.set(readUtf8(exchange.getRequestBody()));
            byte[] response = ("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                    + "\"usage\":{\"input_tokens\":5,\"output_tokens\":2,"
                    + "\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT = false;
            Properties.LLM_PROVIDER = Properties.LlmProvider.ANTHROPIC;
            Properties.LLM_MODEL = "claude-3-5-sonnet-latest";
            Properties.LLM_API_KEY = "anthropic-token";
            Properties.LLM_BASE_URL = "http://127.0.0.1:" + server.getAddress().getPort();
            Properties.LLM_TIMEOUT_SECONDS = 3;
            Properties.LLM_RETRY_MAX_ATTEMPTS = 0;
            Properties.LLM_TRACE_ENABLED = false;
            LlmService.resetInstanceForTesting();

            LlmService service = LlmService.getInstance();
            service.query(Arrays.asList(LlmMessage.system("system context"),
                    LlmMessage.user("hi")), LlmFeature.TEST_REPAIR);

            assertFalse(requestBody.get().contains("cache_control"));
            assertTrue(requestBody.get().contains("\"system\":\"system context\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nonSuccessResponseFailsCallAndRecordsFailure() throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            readUtf8(exchange.getRequestBody());
            byte[] response = "{\"error\":{\"message\":\"invalid api key\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
            Properties.LLM_MODEL = "local-model";
            Properties.LLM_API_KEY = "local-token";
            Properties.LLM_BASE_URL = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            Properties.LLM_TIMEOUT_SECONDS = 3;
            Properties.LLM_RETRY_MAX_ATTEMPTS = 0;
            Properties.LLM_TRACE_ENABLED = false;
            LlmService.resetInstanceForTesting();

            LlmService service = LlmService.getInstance();
            assertThrows(LlmCallFailedException.class, () -> service.query(
                    Collections.singletonList(LlmMessage.user("generate a test")),
                    LlmFeature.TEST_REPAIR));

            assertEquals(1L, service.getStatistics().getFailedCalls());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void requestBodyPicksTokenParamByModelFamily() throws Exception {
        assertRequestUsesTokenParam("gpt-5.4-nano", "max_completion_tokens");
        assertRequestUsesTokenParam("gpt-4o-mini", "max_tokens");
    }

    private void assertRequestUsesTokenParam(String model, String expectedParam) throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requestBody.set(readUtf8(exchange.getRequestBody()));
            byte[] response = ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":1,"
                    + "\"completion_tokens\":1}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
            Properties.LLM_MODEL = model;
            Properties.LLM_API_KEY = "local-token";
            Properties.LLM_BASE_URL = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            Properties.LLM_TIMEOUT_SECONDS = 3;
            Properties.LLM_RETRY_MAX_ATTEMPTS = 0;
            Properties.LLM_TRACE_ENABLED = false;
            LlmService.resetInstanceForTesting();

            LlmService service = LlmService.getInstance();
            service.query(Collections.singletonList(LlmMessage.user("generate a test")),
                    LlmFeature.TEST_REPAIR);

            assertTrue(requestBody.get().contains(expectedParam));
        } finally {
            server.stop(0);
            LlmService.resetInstanceForTesting();
        }
    }

    private static String readUtf8(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            bytes.write(buffer, 0, count);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
