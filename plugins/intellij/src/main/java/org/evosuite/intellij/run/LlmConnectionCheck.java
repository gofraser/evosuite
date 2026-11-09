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
package org.evosuite.intellij.run;

import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import org.evosuite.ide.*;
import org.evosuite.intellij.settings.LlmApiKeyStore;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Metadata-only probe: never sends prompts, source code or a test-generation request. */
public final class LlmConnectionCheck {
    private LlmConnectionCheck() {}

    public static String probe(GenerationOptions options, String key, ProgressIndicator indicator)
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
                int status = IdeThread.await(future, indicator).statusCode();
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

    public static void show(Project project, GenerationOptions draft, String draftKey) {
        new Task.Backgroundable(project, "EvoSuite: checking provider metadata", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                String result;
                try {
                    String provider = draft.effectiveLlmProvider();
                    boolean cloud = provider.equals("OPENAI") || provider.equals("ANTHROPIC");
                    String key = draft.effectiveLlmApiKey();
                    if (key.isBlank()) key = draftKey;
                    if (cloud && key.isBlank()) {
                        try {
                            key = LlmApiKeyStore.get(provider);
                        } catch (RuntimeException ignored) {
                            /* Try the inherited environment below. */
                        }
                    }
                    if (cloud && key.isBlank())
                        key = System.getenv().getOrDefault(EvoSuiteCommand.API_KEY_VARIABLE, "");
                    result = probe(draft, key, indicator);
                } catch (ProcessCanceledException ex) {
                    throw ex;
                } catch (Exception ex) {
                    result =
                            "Connection check failed. Check the endpoint, network access and"
                                    + " provider credentials. No generation request was sent.";
                }
                String message = result;
                IdeThread.edt(
                        () -> {
                            Messages.showInfoMessage(project, message, "EvoSuite Connection Check");
                            return null;
                        });
            }
        }.queue();
    }
}
