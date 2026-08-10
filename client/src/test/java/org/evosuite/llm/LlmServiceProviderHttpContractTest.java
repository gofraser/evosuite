/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors.
 */
package org.evosuite.llm;

import com.sun.net.httpserver.HttpServer;
import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wire-level contract test for OpenAI-compatible local endpoints. */
class LlmServiceProviderHttpContractTest {

    private final Properties.LlmProvider originalProvider = Properties.LLM_PROVIDER;
    private final String originalModel = Properties.LLM_MODEL;
    private final String originalApiKey = Properties.LLM_API_KEY;
    private final String originalBaseUrl = Properties.LLM_BASE_URL;
    private final int originalTimeout = Properties.LLM_TIMEOUT_SECONDS;
    private final int originalRetries = Properties.LLM_RETRY_MAX_ATTEMPTS;
    private final boolean originalTraceEnabled = Properties.LLM_TRACE_ENABLED;

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
        } finally {
            server.stop(0);
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
