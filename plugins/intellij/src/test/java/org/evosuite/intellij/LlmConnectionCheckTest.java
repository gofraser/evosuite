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
package org.evosuite.intellij;

import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.sun.net.httpserver.HttpServer;

import org.evosuite.ide.GenerationOptions;
import org.evosuite.intellij.run.LlmConnectionCheck;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

public class LlmConnectionCheckTest
        extends com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase {
    public void testMetadataCheckUsesEffectiveProviderAndSendsNoSourceOrPrompt() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> request = new AtomicReference<>();
        server.createContext(
                "/v1/models/test-model",
                exchange -> {
                    request.set(
                            exchange.getRequestMethod()
                                    + ":"
                                    + exchange.getRequestURI()
                                    + ":"
                                    + exchange.getRequestHeaders().getFirst("x-api-key")
                                    + ":"
                                    + new String(exchange.getRequestBody().readAllBytes()));
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        server.start();
        try {
            var options = new GenerationOptions();
            options.llmProvider = "OPENAI";
            options.extraArguments =
                    "-Dllm_provider=ANTHROPIC -Dllm_model=test-model"
                            + " -Dllm_base_url=http://127.0.0.1:"
                            + server.getAddress().getPort()
                            + "/v1";
            String result =
                    LlmConnectionCheck.probe(options, "fake-key", new EmptyProgressIndicator());
            assertTrue(result, result.startsWith("Connection succeeded"));
            assertEquals("GET:/v1/models/test-model:fake-key:", request.get());
            options.llmApproach = "SEARCH_ONLY";
            request.set(null);
            assertTrue(
                    LlmConnectionCheck.probe(options, "", new EmptyProgressIndicator())
                            .contains("No provider connection"));
            assertNull(request.get());
        } finally {
            server.stop(0);
        }
    }
}
