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

import org.evosuite.Properties;
import org.evosuite.llm.provider.AnthropicChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explains a call that failed because the server rejected the configured model,
 * by listing the models the server does offer.
 */
final class LlmModelListDiagnostics {

    private static final Logger logger = LoggerFactory.getLogger(LlmModelListDiagnostics.class);

    private static final Pattern MODEL_ID_PATTERN =
            Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    private LlmModelListDiagnostics() {
    }

    /**
     * Logs which models the server offers when {@code friendlyMessage} says that
     * the model configured for {@code feature} was rejected; does nothing otherwise.
     */
    static void logIfModelRejected(LlmConfiguration configuration, LlmFeature feature,
                                   String friendlyMessage, long deadlineNanos) {
        if (!LlmErrorClassifier.isInvalidModelError(friendlyMessage)) {
            return;
        }
        List<String> available = fetchAvailableModelIds(configuration, deadlineNanos);
        if (available.isEmpty()) {
            return;
        }
        String configuredModel = configuration.modelFor(feature);
        if (configuredModel != null && available.contains(configuredModel.trim())) {
            logger.warn("Model '{}' exists at {} but the request was rejected"
                            + " — check your API key and permissions",
                    configuredModel, configuration.getBaseUrl());
        } else {
            logger.warn("Available models at {}: {}", configuration.getBaseUrl(),
                    String.join(", ", available));
        }
    }

    /**
     * Queries the {@code /v1/models} endpoint and returns a sorted list of
     * available model IDs.  Returns an empty list on any failure (network
     * error, non-OpenAI provider, etc.) — this is a best-effort helper for
     * diagnostics only.
     */
    static List<String> fetchAvailableModelIds(LlmConfiguration configuration, long deadlineNanos) {
        String base = configuration.getBaseUrl();
        if (isBlank(base)) {
            return Collections.emptyList();
        }
        base = base.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        // The base URL may already include /v1 (e.g. "https://host/v1")
        String endpoint;
        if (base.endsWith("/v1")) {
            endpoint = base + "/models";
        } else {
            endpoint = base + "/v1/models";
        }

        HttpURLConnection conn = null;
        try {
            int timeoutMs = diagnosticTimeoutMillis(deadlineNanos);
            if (timeoutMs <= 0) {
                return Collections.emptyList();
            }
            logger.debug("Querying available models at {}", endpoint);
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            if (!isBlank(configuration.getApiKey())) {
                String apiKey = configuration.getApiKey().trim();
                if (configuration.getProvider() == Properties.LlmProvider.ANTHROPIC) {
                    AnthropicChatModel.authenticationHeaders(apiKey).forEach(conn::setRequestProperty);
                } else {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                }
            }

            int status = conn.getResponseCode();
            if (status != 200) {
                logger.debug("Models endpoint returned HTTP {}", status);
                return Collections.emptyList();
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (PhaseDeadline.remainingNanos(deadlineNanos) <= 0) {
                        return Collections.emptyList();
                    }
                    sb.append(line);
                }
            }

            // Minimal JSON extraction — pull all "id" values from the response
            // without requiring a JSON library.  The /v1/models response looks
            // like: {"data":[{"id":"model-1",...},{"id":"model-2",...},...]}
            List<String> ids = new ArrayList<>();
            Matcher m = MODEL_ID_PATTERN.matcher(sb.toString());
            while (m.find()) {
                ids.add(m.group(1));
            }
            Collections.sort(ids);
            return ids;
        } catch (Exception e) {
            logger.debug("Failed to fetch available models from {}: {}", endpoint, e.getMessage());
            return Collections.emptyList();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static int diagnosticTimeoutMillis(long deadlineNanos) {
        if (deadlineNanos == PhaseDeadline.NONE) {
            return 5_000;
        }
        long remaining = PhaseDeadline.remainingNanos(deadlineNanos);
        if (remaining <= 0L) {
            return 0;
        }
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remaining);
        return (int) Math.min(5_000L, Math.max(1L, remainingMillis));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
