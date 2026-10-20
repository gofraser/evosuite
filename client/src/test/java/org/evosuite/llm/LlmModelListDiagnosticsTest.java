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

import com.sun.net.httpserver.HttpServer;
import org.evosuite.Properties;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmModelListDiagnosticsTest {

    @Test
    void listsTheServersModelIdsSortedFromTheModelsEndpoint() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            byte[] response = "{\"data\":[{\"id\":\"model-b\"},{\"id\":\"model-a\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            LlmConfiguration configuration = LlmConfiguration.builder()
                    .provider(Properties.LlmProvider.OPENAI)
                    .model("model-c")
                    .apiKey("key")
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/")
                    .build();

            assertEquals(Arrays.asList("model-a", "model-b"),
                    LlmModelListDiagnostics.fetchAvailableModelIds(configuration, Long.MAX_VALUE));
            assertEquals("/v1/models", requestPath.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsNoModelsWithoutABaseUrlOrOnceTheDeadlineHasPassed() {
        LlmConfiguration withoutBaseUrl = LlmConfiguration.builder()
                .provider(Properties.LlmProvider.OPENAI)
                .model("model")
                .build();
        assertEquals(Collections.emptyList(),
                LlmModelListDiagnostics.fetchAvailableModelIds(withoutBaseUrl, Long.MAX_VALUE));

        LlmConfiguration unreachable = LlmConfiguration.builder()
                .provider(Properties.LlmProvider.OPENAI)
                .model("model")
                .baseUrl("http://127.0.0.1:1")
                .build();
        assertEquals(Collections.emptyList(),
                LlmModelListDiagnostics.fetchAvailableModelIds(unreachable, System.nanoTime() - 1L));
    }
}
