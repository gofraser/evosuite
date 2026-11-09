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
package org.evosuite.eclipse.preferences;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.evosuite.ide.EvoSuiteCommand;
import org.evosuite.ide.GenerationOptions;

final class LlmConnectionCheck {
    private LlmConnectionCheck() {}

    static void show(Shell shell, GenerationOptions draft, String draftKey) {
        var display = shell.getDisplay();
        Job.create("Checking EvoSuite provider", monitor -> {
            String result;
            try {
                result = probe(draft, draftKey);
            } catch (IOException | IllegalArgumentException e) {
                result = "Connection check failed. Check the endpoint, network access and provider credentials.";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Status.CANCEL_STATUS;
            }
            String message = result;
            if (!monitor.isCanceled() && !display.isDisposed()) {
                display.asyncExec(() -> {
                    if (!shell.isDisposed()) MessageDialog.openInformation(shell, "EvoSuite Connection Check", message);
                });
            }
            return Status.OK_STATUS;
        }).schedule();
    }

    private static String probe(GenerationOptions draft, String draftKey) throws IOException, InterruptedException {
        String provider = draft.effectiveLlmProvider();
        boolean local = GenerationOptions.LLM_OLLAMA.equals(provider);
        boolean anthropic = GenerationOptions.LLM_ANTHROPIC.equals(provider);
        String base = draft.effectiveLlmBaseUrl();
        if (GenerationOptions.LLM_NONE.equals(provider)) return "Search only is active.";
        if (base.isBlank()) {
            base = switch (provider) {
                case GenerationOptions.LLM_OPENAI -> "https://api.openai.com/v1";
                case GenerationOptions.LLM_ANTHROPIC -> "https://api.anthropic.com/v1";
                case GenerationOptions.LLM_OLLAMA -> GenerationOptions.OLLAMA_DEFAULT_URL;
                default -> throw new IllegalArgumentException("Unsupported provider");
            };
        }
        URI endpoint = URI.create(base);
        if (!("https".equalsIgnoreCase(endpoint.getScheme()) || "http".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null) {
            return "Use an HTTP(S) endpoint without credentials, a query, or a fragment.";
        }
        String model = draft.effectiveLlmModel();
        if (model.isBlank()) model = System.getenv().getOrDefault("EVOSUITE_LLM_MODEL", "");
        String path = local ? "/api/tags" : "/models";
        if (!local && !model.isBlank()) {
            path += "/" + URLEncoder.encode(model, StandardCharsets.UTF_8).replace("+", "%20");
        }
        var request = HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + path))
                .timeout(Duration.ofSeconds(10)).GET();
        if (!local) {
            String key = draftKey.isBlank() ? LlmApiKey.get(provider) : draftKey;
            if (key.isBlank()) key = System.getenv().getOrDefault(EvoSuiteCommand.API_KEY_VARIABLE, "");
            if (!key.isBlank()) {
                request.header(anthropic ? "x-api-key" : "Authorization", anthropic ? key : "Bearer " + key);
            }
        }
        if (anthropic) request.header("anthropic-version", "2023-06-01");
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            int status = client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) return "The provider returned metadata successfully.";
            return switch (status) {
                case 401, 403 -> "The provider rejected authentication. Check its API key and permissions.";
                case 404 -> "The metadata endpoint or model was not found.";
                case 429 -> "The provider returned HTTP 429. Check its rate and account limits.";
                default -> "The provider returned HTTP " + status + " for the metadata request.";
            };
        }
    }
}
