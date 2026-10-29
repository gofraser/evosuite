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
package org.evosuite.eclipse.launch;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Metadata-only check: sends no project source, prompt or generation request. */
public final class LlmConnectionCheck {
    private LlmConnectionCheck() {}

    public static String probe(
            GenerationOptions options,
            String key,
            org.eclipse.core.runtime.IProgressMonitor monitor)
            throws Exception {
        String provider = options.effectiveLlmProvider();
        if (provider.equals("NONE"))
            return "Search only is active. No provider connection is required.";
        String base = options.effectiveLlmBaseUrl();
        if (base.isBlank())
            base =
                    switch (provider) {
                        case "OPENAI" -> "https://api.openai.com/v1";
                        case "ANTHROPIC" -> "https://api.anthropic.com/v1";
                        case "OLLAMA" -> "http://localhost:11434";
                        default -> throw new IllegalArgumentException("Unsupported provider");
                    };
        URI endpoint = URI.create(base);
        if (!("https".equalsIgnoreCase(endpoint.getScheme())
                        || "http".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null)
            throw new IllegalArgumentException(
                    "Use an HTTP(S) endpoint without credentials, query or fragment.");
        String model = options.effectiveLlmModel();
        if (model.isBlank()) model = System.getenv().getOrDefault("EVOSUITE_LLM_MODEL", "");
        String path = provider.equals("OLLAMA") ? "/api/tags" : "/models";
        if (!provider.equals("OLLAMA") && !model.isBlank())
            path +=
                    "/"
                            + java.net.URLEncoder.encode(model, StandardCharsets.UTF_8)
                                    .replace("+", "%20");
        var request =
                HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + path))
                        .timeout(Duration.ofSeconds(10))
                        .GET();
        if (!key.isBlank())
            request.header(
                    provider.equals("ANTHROPIC") ? "x-api-key" : "Authorization",
                    provider.equals("ANTHROPIC") ? key : "Bearer " + key);
        if (provider.equals("ANTHROPIC")) request.header("anthropic-version", "2023-06-01");
        try (var client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()) {
            var future = client.sendAsync(request.build(), HttpResponse.BodyHandlers.discarding());
            try {
                int status = await(future, monitor).statusCode();
                if (status >= 200 && status < 300)
                    return "Connection succeeded. "
                            + (!provider.equals("OLLAMA") && !model.isBlank()
                                    ? "Configured model metadata is available."
                                    : "Select a model before generation if your provider requires"
                                            + " one.")
                            + " No generation request was sent.";
                return switch (status) {
                    case 401, 403 ->
                            "Authentication or access was rejected (HTTP "
                                    + status
                                    + "). Check this provider's API key and permissions.";
                    case 404 ->
                            "Endpoint or model metadata was not found (HTTP 404). Check the"
                                    + " endpoint and model name.";
                    case 429 ->
                            "Provider rate limit reached (HTTP 429). Retry later or check your"
                                    + " account limits.";
                    default ->
                            "Metadata request returned HTTP "
                                    + status
                                    + ". Check the endpoint. No generation request was sent.";
                };
            } finally {
                future.cancel(true);
            }
        }
    }

    private static <T> T await(
            java.util.concurrent.CompletableFuture<T> future,
            org.eclipse.core.runtime.IProgressMonitor monitor)
            throws Exception {
        while (true) {
            if (monitor != null && monitor.isCanceled())
                throw new org.eclipse.core.runtime.OperationCanceledException();
            try {
                return future.get(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException ignored) {
            }
        }
    }
}
