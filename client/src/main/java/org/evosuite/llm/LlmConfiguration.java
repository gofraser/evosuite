/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.llm;

import org.evosuite.Properties;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Immutable snapshot of runtime LLM configuration.
 * Use {@link Builder} for constructing instances programmatically.
 */
public class LlmConfiguration {

    private final Properties.LlmProvider provider;
    private final String model;
    private final String apiKey;
    private final String baseUrl;
    private final double temperature;
    private final int maxTokens;
    private final int timeoutSeconds;
    private final int retryMaxAttempts;
    private final int retryBaseDelayMs;
    private final boolean traceEnabled;
    private final Path traceDir;
    private final String runId;

    /** Constructs an immutable LLM configuration snapshot with all fields. */
    public LlmConfiguration(Properties.LlmProvider provider,
                            String model,
                            String apiKey,
                            String baseUrl,
                            double temperature,
                            int maxTokens,
                            int timeoutSeconds,
                            int retryMaxAttempts,
                            int retryBaseDelayMs,
                            boolean traceEnabled,
                            Path traceDir,
                            String runId) {
        this.provider = provider;
        this.model = model;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeoutSeconds = timeoutSeconds;
        this.retryMaxAttempts = retryMaxAttempts;
        this.retryBaseDelayMs = retryBaseDelayMs;
        this.traceEnabled = traceEnabled;
        this.traceDir = traceDir;
        this.runId = runId;
    }

    /** Returns a new builder for constructing LlmConfiguration instances. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates an {@link LlmConfiguration} from the current EvoSuite {@link Properties}.
     */
    public static LlmConfiguration fromProperties() {
        String model = readWithEnvFallback(Properties.LLM_MODEL, "EVOSUITE_LLM_MODEL");
        String apiKey = readWithEnvFallback(Properties.LLM_API_KEY, "EVOSUITE_LLM_API_KEY");
        String baseUrl = readWithEnvFallback(Properties.LLM_BASE_URL, "EVOSUITE_LLM_BASE_URL");
        String configuredTraceDir = Properties.LLM_TRACE_DIR == null ? "" : Properties.LLM_TRACE_DIR.trim();
        Path traceDir = configuredTraceDir.isEmpty()
                ? Paths.get("evosuite-report", "llm-traces")
                : Paths.get(configuredTraceDir);
        return new LlmConfiguration(
                Properties.LLM_PROVIDER,
                model,
                apiKey,
                baseUrl,
                Properties.LLM_TEMPERATURE,
                Properties.LLM_MAX_TOKENS,
                Properties.LLM_TIMEOUT_SECONDS,
                Properties.LLM_RETRY_MAX_ATTEMPTS,
                Properties.LLM_RETRY_BASE_DELAY_MS,
                Properties.LLM_TRACE_ENABLED,
                traceDir,
                UUID.randomUUID().toString());
    }

    private static String readWithEnvFallback(String configuredValue, String envKey) {
        String trimmed = configuredValue == null ? "" : configuredValue.trim();
        if (!trimmed.isEmpty()) {
            return trimmed;
        }
        String env = System.getenv(envKey);
        return env == null ? "" : env.trim();
    }

    public Properties.LlmProvider getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public double getTemperature() {
        return temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public int getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public int getRetryBaseDelayMs() {
        return retryBaseDelayMs;
    }

    public boolean isTraceEnabled() {
        return traceEnabled;
    }

    public Path getTraceDir() {
        return traceDir;
    }

    public String getRunId() {
        return runId;
    }

    /** Fluent builder for {@link LlmConfiguration}. */
    public static class Builder {
        private Properties.LlmProvider provider = Properties.LlmProvider.NONE;
        private String model = "";
        private String apiKey = "";
        private String baseUrl = "";
        private double temperature = 0.0;
        private int maxTokens = 1024;
        private int timeoutSeconds = 30;
        private int retryMaxAttempts = 2;
        private int retryBaseDelayMs = 1000;
        private boolean traceEnabled = false;
        private Path traceDir = Paths.get("evosuite-report", "llm-traces");
        private String runId = UUID.randomUUID().toString();

        public Builder provider(Properties.LlmProvider provider) { this.provider = provider; return this; }
        public Builder model(String model) { this.model = model; return this; }
        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        public Builder temperature(double temperature) { this.temperature = temperature; return this; }
        public Builder maxTokens(int maxTokens) { this.maxTokens = maxTokens; return this; }
        public Builder timeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; return this; }
        public Builder retryMaxAttempts(int retryMaxAttempts) { this.retryMaxAttempts = retryMaxAttempts; return this; }
        public Builder retryBaseDelayMs(int retryBaseDelayMs) { this.retryBaseDelayMs = retryBaseDelayMs; return this; }
        public Builder traceEnabled(boolean traceEnabled) { this.traceEnabled = traceEnabled; return this; }
        public Builder traceDir(Path traceDir) { this.traceDir = traceDir; return this; }
        public Builder runId(String runId) { this.runId = runId; return this; }

        public LlmConfiguration build() {
            return new LlmConfiguration(provider, model, apiKey, baseUrl, temperature,
                    maxTokens, timeoutSeconds, retryMaxAttempts, retryBaseDelayMs,
                    traceEnabled, traceDir, runId);
        }
    }
}
