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

import com.sun.net.httpserver.HttpServer;
import org.evosuite.Properties;
import org.evosuite.llm.LlmBudgetCoordinator;
import org.evosuite.llm.LlmCallFailedException;
import org.evosuite.llm.LlmConfiguration;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.LlmStatistics;
import org.evosuite.llm.LlmTraceRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit tests for the response validation performed by
 * {@link OpenAiCompatibleChatModel#parseResponse} and
 * {@link AnthropicChatModel#parseResponse}: a 2xx response with a body that
 * does not conform to the expected shape must raise {@link LlmProtocolException}
 * rather than silently returning empty text and zero usage.
 */
class ProviderResponseParsingTest {

    @TempDir
    Path tempDir;

    // ---- OpenAI-compatible parsing -------------------------------------

    @Test
    void openAiRejectsEmptyObjectBody() {
        assertThrows(LlmProtocolException.class, () -> OpenAiCompatibleChatModel.parseResponse("{}"));
    }

    @Test
    void openAiRejectsNullBody() {
        assertThrows(LlmProtocolException.class, () -> OpenAiCompatibleChatModel.parseResponse("null"));
    }

    @Test
    void openAiRejectsUnparsableBody() {
        assertThrows(LlmProtocolException.class, () -> OpenAiCompatibleChatModel.parseResponse("not json"));
    }

    @Test
    void openAiRejectsErrorShapedBody() {
        LlmProviderErrorException e = assertThrows(LlmProviderErrorException.class, () -> OpenAiCompatibleChatModel.parseResponse(
                "{\"error\":{\"message\":\"model overloaded\",\"type\":\"overloaded_error\"}}"));
        assertTrue(e.getMessage().contains("model overloaded"));
        assertTrue(e.isRetryable(), "overloaded_error should be retryable");
    }

    @Test
    void openAiInvalidRequestErrorIsNotRetryable() {
        LlmProviderErrorException e = assertThrows(LlmProviderErrorException.class, () -> OpenAiCompatibleChatModel.parseResponse(
                "{\"error\":{\"message\":\"missing required field\",\"type\":\"invalid_request_error\"}}"));
        assertFalse(e.isRetryable(), "invalid_request_error should not be retryable");
    }

    @Test
    void openAiRateLimitErrorIsRetryable() {
        LlmProviderErrorException e = assertThrows(LlmProviderErrorException.class, () -> OpenAiCompatibleChatModel.parseResponse(
                "{\"error\":{\"message\":\"slow down\",\"type\":\"rate_limit_error\"}}"));
        assertTrue(e.isRetryable(), "rate_limit_error should be retryable");
    }

    @Test
    void openAiTypelessErrorObjectIsNotRetryable() {
        LlmProviderErrorException e = assertThrows(LlmProviderErrorException.class, () -> OpenAiCompatibleChatModel.parseResponse(
                "{\"error\":{\"message\":\"something went wrong\"}}"));
        assertFalse(e.isRetryable(), "an error object without type or code should not be retryable");
    }

    @Test
    void openAiRejectsMissingChoices() {
        assertThrows(LlmProtocolException.class,
                () -> OpenAiCompatibleChatModel.parseResponse("{\"choices\":[]}"));
    }

    @Test
    void openAiRejectsNullMessageContent() {
        assertThrows(LlmProtocolException.class, () -> OpenAiCompatibleChatModel.parseResponse(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null}}]}"));
    }

    @Test
    void openAiRejectsMissingMessageContent() {
        assertThrows(LlmProtocolException.class, () -> OpenAiCompatibleChatModel.parseResponse(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\"}}]}"));
    }

    @Test
    void openAiRejectsUsageThatIsNotAnObject() {
        assertThrows(LlmProtocolException.class, () -> OpenAiCompatibleChatModel.parseResponse(
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":\"nope\"}"));
    }

    @Test
    void openAiRejectsNonNumericUsageField() {
        assertThrows(LlmProtocolException.class, () -> OpenAiCompatibleChatModel.parseResponse(
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":\"x\"}}"));
    }

    @Test
    void openAiToleratesMissingUsageAsZero() throws Exception {
        LlmService.LlmResponse response = OpenAiCompatibleChatModel.parseResponse(
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        assertEquals("ok", response.getText());
        assertEquals(0, response.getInputTokens());
        assertEquals(0, response.getOutputTokens());
    }

    @Test
    void openAiAcceptsWellFormedResponse() throws Exception {
        LlmService.LlmResponse response = OpenAiCompatibleChatModel.parseResponse(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}],"
                        + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}");
        assertEquals("hello", response.getText());
        assertEquals(3, response.getInputTokens());
        assertEquals(2, response.getOutputTokens());
    }

    // ---- Anthropic parsing ----------------------------------------------

    @Test
    void anthropicRejectsEmptyObjectBody() {
        assertThrows(LlmProtocolException.class, () -> AnthropicChatModel.parseResponse("{}"));
    }

    @Test
    void anthropicRejectsNullBody() {
        assertThrows(LlmProtocolException.class, () -> AnthropicChatModel.parseResponse("null"));
    }

    @Test
    void anthropicRejectsErrorShapedBody() {
        LlmProviderErrorException e = assertThrows(LlmProviderErrorException.class, () -> AnthropicChatModel.parseResponse(
                "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"));
        assertTrue(e.getMessage().contains("Overloaded"));
        assertTrue(e.isRetryable(), "overloaded_error should be retryable");
    }

    @Test
    void anthropicInvalidRequestErrorIsNotRetryable() {
        LlmProviderErrorException e = assertThrows(LlmProviderErrorException.class, () -> AnthropicChatModel.parseResponse(
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad params\"}}"));
        assertFalse(e.isRetryable(), "invalid_request_error should not be retryable");
    }

    @Test
    void anthropicRateLimitErrorIsRetryable() {
        LlmProviderErrorException e = assertThrows(LlmProviderErrorException.class, () -> AnthropicChatModel.parseResponse(
                "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}"));
        assertTrue(e.isRetryable(), "rate_limit_error should be retryable");
    }

    @Test
    void anthropicTypelessErrorObjectIsNotRetryable() {
        LlmProviderErrorException e = assertThrows(LlmProviderErrorException.class, () -> AnthropicChatModel.parseResponse(
                "{\"type\":\"error\",\"error\":{\"message\":\"something went wrong\"}}"));
        assertFalse(e.isRetryable(), "an error object without type or code should not be retryable");
    }

    @Test
    void anthropicRejectsMissingContent() {
        assertThrows(LlmProtocolException.class, () -> AnthropicChatModel.parseResponse("{\"usage\":{}}"));
    }

    @Test
    void anthropicRejectsContentWithoutTextBlock() {
        assertThrows(LlmProtocolException.class, () -> AnthropicChatModel.parseResponse(
                "{\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\"}]}"));
    }

    @Test
    void anthropicRejectsNullText() {
        assertThrows(LlmProtocolException.class, () -> AnthropicChatModel.parseResponse(
                "{\"content\":[{\"type\":\"text\",\"text\":null}]}"));
    }

    @Test
    void anthropicRejectsUsageThatIsNotAnObject() {
        assertThrows(LlmProtocolException.class, () -> AnthropicChatModel.parseResponse(
                "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"usage\":\"nope\"}"));
    }

    @Test
    void anthropicRejectsNonNumericUsageField() {
        assertThrows(LlmProtocolException.class, () -> AnthropicChatModel.parseResponse(
                "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"usage\":{\"input_tokens\":\"x\"}}"));
    }

    @Test
    void anthropicAcceptsWellFormedResponse() throws Exception {
        LlmService.LlmResponse response = AnthropicChatModel.parseResponse(
                "{\"content\":[{\"type\":\"text\",\"text\":\"hi there\"}],"
                        + "\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}");
        assertEquals("hi there", response.getText());
        assertEquals(5, response.getInputTokens());
        assertEquals(2, response.getOutputTokens());
    }

    // ---- End-to-end through LlmService: malformed 2xx is retryable and not
    //      recorded as a successful call. -------------------------------

    @Test
    void malformedTwoHundredResponseIsRetryableAndNotRecordedAsSuccess() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            readUtf8(exchange.getRequestBody());
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        OpenAiCompatibleChatModel model = new OpenAiCompatibleChatModel(
                Properties.LlmProvider.OPENAI, "local-model", "local-token", baseUrl, 0.0, 1024, 3);
        LlmConfiguration configuration = new LlmConfiguration(
                Properties.LlmProvider.OPENAI, "local-model", "local-token", baseUrl,
                0.0, 1024, 3, 0, 1, false, tempDir, "malformed-run");
        LlmStatistics statistics = new LlmStatistics();
        LlmService service = new LlmService(model, new LlmBudgetCoordinator.Local(1), configuration,
                statistics, new LlmTraceRecorder(configuration));

        try {
            LlmCallFailedException failure = assertThrows(LlmCallFailedException.class,
                    () -> service.query(Collections.singletonList(LlmMessage.user("generate a test")),
                            LlmFeature.TEST_REPAIR));
            assertTrue(failure.isRetryable());
            assertEquals(0, statistics.getSuccessfulCalls());
        } finally {
            service.close();
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
