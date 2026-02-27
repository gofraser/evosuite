package org.evosuite.llm;

import org.evosuite.Properties;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Immutable snapshot of runtime LLM configuration.
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
}
