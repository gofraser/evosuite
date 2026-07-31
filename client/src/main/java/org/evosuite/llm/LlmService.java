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

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.evosuite.Properties;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.runtime.sandbox.Sandbox;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.tools.ToolProvider;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entry-point for all LLM calls, including budget and retry enforcement.
 */
public class LlmService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(LlmService.class);
    private static final Object INSTANCE_LOCK = new Object();
    private static volatile LlmService instance;
    private static volatile Boolean compilerAvailableOverrideForTesting = null;

    private volatile ChatLanguageModel model;
    private final LlmBudgetCoordinator budgetCoordinator;
    private final LlmConfiguration configuration;
    private final LlmStatistics statistics;
    private final LlmTraceRecorder traceRecorder;
    private final Random jitterRandom;
    private volatile ExecutorService executorService;
    private final boolean available;

    public LlmService(ChatLanguageModel model,
                      LlmBudgetCoordinator budgetCoordinator,
                      LlmConfiguration configuration,
                      LlmStatistics statistics,
                      LlmTraceRecorder traceRecorder) {
        this(model, budgetCoordinator, configuration, statistics, traceRecorder, new Random(), true);
    }

    public LlmService(ChatLanguageModel model,
                      LlmBudgetCoordinator budgetCoordinator,
                      LlmConfiguration configuration,
                      LlmStatistics statistics,
                      LlmTraceRecorder traceRecorder,
                      Random jitterRandom) {
        this(model, budgetCoordinator, configuration, statistics, traceRecorder, jitterRandom, true);
    }

    LlmService(ChatLanguageModel model,
               LlmBudgetCoordinator budgetCoordinator,
               LlmConfiguration configuration,
               LlmStatistics statistics,
               LlmTraceRecorder traceRecorder,
               Random jitterRandom,
               boolean available) {
        this.model = model;
        this.budgetCoordinator = budgetCoordinator;
        this.configuration = configuration;
        this.statistics = statistics;
        this.traceRecorder = traceRecorder;
        this.jitterRandom = jitterRandom;
        this.executorService = createExecutorService();
        this.available = available;
    }

    /** Returns the process-scoped singleton instance, creating it lazily on first access. */
    public static LlmService getInstance() {
        LlmService local = instance;
        if (local != null) {
            return local;
        }
        synchronized (INSTANCE_LOCK) {
            if (instance == null) {
                instance = createDefaultInstance();
            }
            return instance;
        }
    }

    /** Replaces the singleton instance for use in tests. */
    public static void setInstanceForTesting(LlmService service) {
        synchronized (INSTANCE_LOCK) {
            if (instance != null && instance != service) {
                instance.close();
            }
            instance = service;
        }
    }

    /** Resets the singleton instance to null, forcing re-initialisation on next access. */
    public static void resetInstanceForTesting() {
        synchronized (INSTANCE_LOCK) {
            if (instance != null) {
                instance.close();
            }
            instance = null;
        }
    }

    private static LlmService createDefaultInstance() {
        LlmConfiguration config = LlmConfiguration.fromProperties();
        warnIfTimeoutTooLarge(config.getTimeoutSeconds());
        LlmBudgetCoordinator budget = LlmBudgetCoordinator.fromProperties();
        LlmStatistics stats = new LlmStatistics();
        LlmTraceRecorder recorder = new LlmTraceRecorder(config);
        logger.info("LLM trace configuration: enabled={}, dir={}",
                config.isTraceEnabled(), recorder.getTraceFile());

        if (config.getProvider() == Properties.LlmProvider.NONE) {
            return new LlmService(new UnavailableChatLanguageModel(), budget, config, stats, recorder, new Random(),
                    false);
        }

        if (!isJdkCompilerAvailable()) {
            String message = "LLM requires jdk.compiler but no system Java compiler is available";
            if (Properties.LLM_REQUIRE_JDK_COMPILER) {
                throw new IllegalStateException(message + " (set -Dllm_require_jdk_compiler=false for soft fallback)");
            }
            logger.warn("{}; disabling LLM features for this run", message);
            return new LlmService(new UnavailableChatLanguageModel(), budget, config, stats, recorder, new Random(),
                    false);
        }

        try {
            ChatLanguageModel model = createProviderModel(config);
            logActiveLlmConfiguration(config);
            return new LlmService(model, budget, config, stats, recorder, new Random(), true);
        } catch (IllegalArgumentException e) {
            logger.warn("LLM provider '{}' misconfigured: {}", config.getProvider(), e.getMessage());
        } catch (RuntimeException e) {
            logger.warn("Failed to initialize LLM provider '{}': {}", config.getProvider(), e.getMessage());
        }
        return new LlmService(new UnavailableChatLanguageModel(), budget, config, stats, recorder, new Random(), false);
    }

    private static void logActiveLlmConfiguration(LlmConfiguration config) {
        String baseUrl = config.getBaseUrl();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            baseUrl = "<provider default>";
        }
        LoggingUtils.getEvoLogger().info("* LLM: provider={}, model={}, baseUrl={}",
                config.getProvider(), config.getModel(), baseUrl);
    }

    private static void warnIfTimeoutTooLarge(int timeoutSeconds) {
        // LangChain4j 0.35.0 maps the single timeout() Duration to all four
        // OkHttp timeouts (call/connect/read/write). A long value here means
        // any LLM call can stall a worker — and any sandbox-lock holder —
        // for that long. 300s is already very generous for a single LLM call.
        if (timeoutSeconds > 300) {
            logger.warn("llm_timeout_seconds={} is unusually large; a slow LLM call will block "
                    + "the calling thread for that long. Recommended <=300s.", timeoutSeconds);
        }
    }

    private static boolean isJdkCompilerAvailable() {
        Boolean override = compilerAvailableOverrideForTesting;
        if (override != null) {
            return override;
        }
        return ToolProvider.getSystemJavaCompiler() != null;
    }

    static void setCompilerAvailableForTesting(Boolean available) {
        compilerAvailableOverrideForTesting = available;
    }

    private static ChatLanguageModel createProviderModel(LlmConfiguration config) {
        switch (config.getProvider()) {
            case OPENAI:
                checkClassAvailable("dev.langchain4j.model.openai.OpenAiChatModel",
                        "langchain4j-open-ai", "OPENAI");
                return createOpenAiModel(config);
            case OLLAMA:
                checkClassAvailable("dev.langchain4j.model.ollama.OllamaChatModel",
                        "langchain4j-ollama", "OLLAMA");
                return createOllamaModel(config);
            case ANTHROPIC:
                checkClassAvailable("dev.langchain4j.model.anthropic.AnthropicChatModel",
                        "langchain4j-anthropic", "ANTHROPIC");
                return createAnthropicModel(config);
            default:
                throw new IllegalArgumentException("Unsupported LLM provider: " + config.getProvider());
        }
    }

    private static void checkClassAvailable(String className, String artifactId, String provider) {
        try {
            Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "LLM provider " + provider + " requires dev.langchain4j:" + artifactId
                    + " on the classpath. Add it as a dependency or use the shaded uber-jar.");
        }
    }

    private static ChatLanguageModel createOpenAiModel(LlmConfiguration config) {
        String modelName = requireNonBlank(config.getModel(), "LLM model must be configured for OPENAI");
        String apiKey = requireNonBlank(config.getApiKey(), "LLM API key must be configured for OPENAI");
        // LangChain4j 0.35.0 fans the single timeout() Duration out to OkHttp's
        // callTimeout, connectTimeout, readTimeout, writeTimeout — so this one
        // value bounds the entire HTTP call. pingInterval (which would detect a
        // silently-dead HTTP/2 connection sooner) is not exposed by this
        // version of the builder and would require swapping the internal
        // OkHttpClient AND rebuilding the Retrofit-bound OpenAiApi; that's
        // out of scope here. invokeWithTimeout() provides the secondary
        // safety net via FutureTask cancellation + executor replacement.
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .modelName(modelName)
                .apiKey(apiKey)
                .temperature(config.getTemperature())
                .timeout(Duration.ofSeconds(Math.max(1, config.getTimeoutSeconds())));
        if (usesMaxCompletionTokens(modelName)) {
            builder.maxCompletionTokens(config.getMaxTokens());
        } else {
            builder.maxTokens(config.getMaxTokens());
        }
        if (!isBlank(config.getBaseUrl())) {
            builder.baseUrl(config.getBaseUrl().trim());
        }
        return new LangChain4jChatLanguageModel(builder.build());
    }

    static boolean usesMaxCompletionTokens(String modelName) {
        if (isBlank(modelName)) {
            return false;
        }
        String name = modelName.trim().toLowerCase();
        return name.startsWith("gpt-5")
                || name.startsWith("o1")
                || name.startsWith("o3")
                || name.startsWith("o4");
    }

    private static ChatLanguageModel createOllamaModel(LlmConfiguration config) {
        String modelName = requireNonBlank(config.getModel(), "LLM model must be configured for OLLAMA");
        String baseUrl = requireNonBlank(config.getBaseUrl(), "LLM base URL must be configured for OLLAMA");
        OllamaChatModel.OllamaChatModelBuilder builder = OllamaChatModel.builder()
                .modelName(modelName)
                .baseUrl(baseUrl)
                .temperature(config.getTemperature())
                .numPredict(config.getMaxTokens())
                .timeout(Duration.ofSeconds(Math.max(1, config.getTimeoutSeconds())));
        return new LangChain4jChatLanguageModel(builder.build());
    }

    private static ChatLanguageModel createAnthropicModel(LlmConfiguration config) {
        String modelName = requireNonBlank(config.getModel(), "LLM model must be configured for ANTHROPIC");
        String apiKey = requireNonBlank(config.getApiKey(), "LLM API key must be configured for ANTHROPIC");
        AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
                .modelName(modelName)
                .apiKey(apiKey)
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .timeout(Duration.ofSeconds(Math.max(1, config.getTimeoutSeconds())));
        if (!isBlank(config.getBaseUrl())) {
            builder.baseUrl(config.getBaseUrl().trim());
        }
        return new LangChain4jChatLanguageModel(builder.build());
    }

    private static String requireNonBlank(String value, String message) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** Returns true if this service has a configured and available LLM provider. */
    public boolean isAvailable() {
        return available;
    }

    /** Returns true if the service has remaining LLM call budget. */
    public boolean hasBudget() {
        if (!available) {
            return false;
        }
        long remaining = budgetCoordinator.getRemaining();
        return remaining < 0 || remaining > 0;
    }

    public LlmStatistics getStatistics() {
        return statistics;
    }

    public String query(List<LlmMessage> messages, LlmFeature feature) {
        return queryInternal(messages, feature, new QueryOptions());
    }

    /**
     * Query bounded by an absolute {@link System#nanoTime()} deadline.
     */
    public String query(List<LlmMessage> messages, LlmFeature feature,
                        long deadlineNanos) {
        return queryInternal(messages, feature,
                new QueryOptions().withDeadlineNanos(deadlineNanos));
    }

    /**
     * Query with an explicit repair attempt number for trace recording.
     * Use this when calling the LLM as part of a repair loop so that the
     * trace correctly records which repair iteration produced this call.
     *
     * @param repairAttempt the 1-based repair attempt number (1 = initial, 2+ = repair)
     */
    public String query(List<LlmMessage> messages, LlmFeature feature, int repairAttempt) {
        return queryInternal(messages, feature, new QueryOptions().withRepairAttempt(repairAttempt));
    }

    /**
     * Query with repair attempt number and cluster expansion metadata for trace recording.
     *
     * @param repairAttempt      the 1-based repair attempt number
     * @param expansionAttempted whether cluster expansion was attempted before this call
     * @param expandedClasses    classes that were expanded into the cluster
     */
    public String query(List<LlmMessage> messages, LlmFeature feature,
                        int repairAttempt, boolean expansionAttempted,
                        List<String> expandedClasses) {
        return queryInternal(messages, feature, new QueryOptions()
                .withRepairAttempt(repairAttempt)
                .withExpansion(expansionAttempted, expandedClasses));
    }

    /**
     * Query with repair metadata, bounded by an absolute
     * {@link System#nanoTime()} deadline.
     */
    public String query(List<LlmMessage> messages, LlmFeature feature,
                        int repairAttempt, boolean expansionAttempted,
                        List<String> expandedClasses, long deadlineNanos) {
        return queryInternal(messages, feature, new QueryOptions()
                .withRepairAttempt(repairAttempt)
                .withExpansion(expansionAttempted, expandedClasses)
                .withDeadlineNanos(deadlineNanos));
    }

    /**
     * Query with context metadata propagated to trace recording.
     * Prefer this overload when building prompts via {@link PromptResult}.
     */
    public String query(PromptResult promptResult, LlmFeature feature) {
        if (promptResult == null) {
            throw new IllegalArgumentException("promptResult must not be null");
        }
        return queryInternal(promptResult.getMessages(), feature,
                QueryOptions.fromPromptResult(promptResult));
    }

    /**
     * Query with prompt metadata, bounded by an absolute
     * {@link System#nanoTime()} deadline.
     */
    public String query(PromptResult promptResult, LlmFeature feature,
                        long deadlineNanos) {
        if (promptResult == null) {
            throw new IllegalArgumentException("promptResult must not be null");
        }
        return queryInternal(promptResult.getMessages(), feature,
                QueryOptions.fromPromptResult(promptResult)
                        .withDeadlineNanos(deadlineNanos));
    }

    /**
     * Mutable option bag threaded through {@link #queryInternal}. Replaces the
     * former chain of overloaded private {@code queryInternal} methods with a
     * single entry point that consumes a typed options object. All fields have
     * safe defaults, so callers only set the values they care about.
     */
    private static final class QueryOptions {
        Properties.LlmSutContextMode sutContextMode = null;
        boolean contextUnavailable = false;
        boolean contextTruncated = false;
        boolean contextCommentsStripped = false;
        boolean contextSelectivelyTruncated = false;
        boolean clusterSummaryTruncated = false;
        int clusterSummaryChars = 0;
        PromptResult.DependencySummaryMetadata dependencySummaryMetadata =
                PromptResult.DependencySummaryMetadata.empty();
        int repairAttempt = 1;
        boolean expansionAttempted = false;
        List<String> expandedClasses = Collections.emptyList();
        long deadlineNanos = Long.MAX_VALUE;

        QueryOptions withRepairAttempt(int attempt) {
            this.repairAttempt = attempt;
            return this;
        }

        QueryOptions withExpansion(boolean attempted, List<String> classes) {
            this.expansionAttempted = attempted;
            this.expandedClasses = classes == null ? Collections.<String>emptyList() : classes;
            return this;
        }

        QueryOptions withDeadlineNanos(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos > 0 ? deadlineNanos : Long.MAX_VALUE;
            return this;
        }

        static QueryOptions fromPromptResult(PromptResult promptResult) {
            QueryOptions options = new QueryOptions();
            options.sutContextMode = promptResult.getSutContextMode();
            options.contextUnavailable = promptResult.isContextUnavailable();
            options.contextTruncated = promptResult.isContextTruncated();
            options.contextCommentsStripped = promptResult.isContextCommentsStripped();
            options.contextSelectivelyTruncated = promptResult.isContextSelectivelyTruncated();
            options.clusterSummaryTruncated = promptResult.isClusterSummaryTruncated();
            options.clusterSummaryChars = promptResult.getClusterSummaryChars();
            options.dependencySummaryMetadata = promptResult.getDependencySummaryMetadata();
            return options;
        }
    }

    private String queryInternal(List<LlmMessage> messages, LlmFeature feature, QueryOptions options) {
        if (!available) {
            throw new LlmCallFailedException("LLM service is unavailable",
                    new IllegalStateException("LLM provider is not configured"), false);
        }
        List<String> safeExpandedClasses = options.expandedClasses == null
                ? Collections.<String>emptyList() : options.expandedClasses;
        int maxTries = Math.max(1, configuration.getRetryMaxAttempts() + 1);
        Throwable lastError = null;

        for (int attempt = 1; attempt <= maxTries; attempt++) {
            ensureBeforeDeadline(options.deadlineNanos);
            if (!budgetCoordinator.tryAcquire()) {
                throw new LlmBudgetExceededException("LLM call budget exhausted on attempt " + attempt);
            }
            long start = System.currentTimeMillis();
            try {
                LlmResponse response = invokeWithTimeout(
                        messages, feature, options.deadlineNanos);
                long latency = System.currentTimeMillis() - start;
                boolean truncated = response.getOutputTokens() > 0
                        && response.getOutputTokens() >= configuration.getMaxTokens() - 1;
                if (truncated) {
                    logger.warn("LLM response likely truncated: outputTokens={} >= maxTokens={}. "
                                    + "Consider increasing -Dllm_max_tokens.",
                            response.getOutputTokens(), configuration.getMaxTokens());
                }
                String status = truncated ? "TRUNCATED" : "SUCCESS";
                statistics.recordCall(feature, response.getInputTokens(), response.getOutputTokens(), latency);
                safeRecordTrace(feature, messages, response.getText(), response.getInputTokens(),
                        response.getOutputTokens(), latency, status, options.repairAttempt,
                        options.expansionAttempted, safeExpandedClasses, "", options);
                return response.getText();
            } catch (Exception e) {
                lastError = unwrap(e);
                boolean retryable = isRetryable(lastError);
                if (!retryable || attempt == maxTries) {
                    String friendly = friendlyMessage(lastError);
                    if (!retryable) {
                        logger.warn("LLM call failed (attempt {}/{}): {}", attempt, maxTries, friendly);
                    } else {
                        logger.warn("LLM call failed after {} attempts: {}", attempt, friendly);
                    }
                    if (isInvalidModelError(friendly)) {
                        List<String> available = fetchAvailableModelIds();
                        if (!available.isEmpty()) {
                            String configuredModel = configuration.getModel();
                            if (configuredModel != null && available.contains(configuredModel.trim())) {
                                logger.warn("Model '{}' exists at {} but the request was rejected"
                                                + " — check your API key and permissions",
                                        configuredModel, configuration.getBaseUrl());
                            } else {
                                logger.warn("Available models at {}: {}", configuration.getBaseUrl(),
                                        String.join(", ", available));
                            }
                        }
                    }
                    logger.debug("Full LLM error detail", lastError);
                    if (lastError instanceof TimeoutException) {
                        statistics.recordTimeout(feature);
                    } else {
                        statistics.recordFailure(feature);
                    }
                    safeRecordTrace(feature, messages, "", 0, 0,
                            System.currentTimeMillis() - start, "FAILED", options.repairAttempt,
                            options.expansionAttempted, safeExpandedClasses,
                            lastError.getClass().getSimpleName(), options);
                    throw new LlmCallFailedException(
                            "LLM query failed after " + attempt + " attempt(s): " + friendly, lastError, retryable);
                }
                logger.debug("LLM call failed (attempt {}/{}): {}; retrying...",
                        attempt, maxTries, friendlyMessage(lastError));
                sleepBackoff(attempt, options.deadlineNanos);
            }
        }

        throw new LlmCallFailedException("LLM query failed", lastError, false);
    }

    private LlmResponse invokeWithTimeout(List<LlmMessage> messages, LlmFeature feature,
                                          long deadlineNanos) throws Exception {
        ExecutorService runner = executorService;
        ChatLanguageModel snapshot = model;
        Future<LlmResponse> future = runner.submit(new Callable<LlmResponse>() {
            @Override
            public LlmResponse call() throws Exception {
                return snapshot.generate(messages, feature);
            }
        });
        try {
            long configuredNanos = TimeUnit.SECONDS.toNanos(
                    Math.max(1, configuration.getTimeoutSeconds()));
            long remainingNanos = remainingNanos(deadlineNanos);
            if (remainingNanos <= 0) {
                future.cancel(true);
                throw new TimeoutException("LLM phase deadline exhausted");
            }
            return future.get(Math.min(configuredNanos, remainingNanos),
                    TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            // Interrupt the worker first — OkHttp's Http2Stream.waitForIo uses
            // Object.wait, which IS interruptible, so this normally tears down
            // the call.
            future.cancel(true);
            if (!waitForWorkerToFinish(future, 2_000L)) {
                // Worker is still running — either OkHttp is in a non-interruptible
                // path (Net.poll on the dispatcher thread) or langchain4j swallowed
                // the interrupt. Drop the executor entirely so the leaked worker
                // can't keep the sandbox lock (or anything else) alive forever.
                // A fresh executor is created lazily on the next call.
                logger.warn("LLM worker did not terminate after cancel; shutting down executor to drop the stuck call");
                replaceExecutorService(runner);
            }
            throw e;
        }
    }

    private boolean waitForWorkerToFinish(Future<?> future, long waitMs) {
        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline) {
            if (future.isDone()) {
                return true;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return future.isDone();
            }
        }
        return future.isDone();
    }

    /**
     * Replaces the per-service executor with a fresh one when its worker is
     * stuck inside an unresponsive HTTP call. {@link ExecutorService#shutdownNow()}
     * interrupts all workers; on the stuck OkHttp threads this won't return
     * immediately, but the new executor lets subsequent calls proceed
     * unimpeded.
     *
     * <p>Also rebuilds the {@link ChatLanguageModel} so the next call uses a
     * fresh OkHttpClient/connection pool. Without this, a dead HTTP/2
     * connection (one whose reader is parked in non-interruptible
     * {@code Net.poll}) would still be reused by the new executor's workers,
     * and they would hang the same way.
     */
    private synchronized void replaceExecutorService(ExecutorService stuck) {
        if (this.executorService != stuck) {
            // Already replaced by a concurrent call.
            return;
        }
        try {
            stuck.shutdownNow();
        } catch (Throwable ignored) {
            // Best effort — even if shutdown throws, we still want a fresh executor below.
        }
        this.executorService = createExecutorService();
        if (available && configuration.getProvider() != Properties.LlmProvider.NONE) {
            try {
                this.model = createProviderModel(configuration);
                LoggingUtils.getEvoLogger().info(
                        "* LLM: rebuilt {} client after stuck call (model={})",
                        configuration.getProvider(), configuration.getModel());
            } catch (Throwable t) {
                logger.warn("Failed to rebuild LLM model after stuck call; keeping existing instance: {}",
                        t.getMessage());
            }
        }
    }

    private static ExecutorService createExecutorService() {
        return Executors.newCachedThreadPool(r -> {
            Thread worker = new Thread(r, "llm-timeout-guard");
            worker.setDaemon(true);
            if (Sandbox.isSecurityManagerInitialized()) {
                try {
                    // The request runs after SUT observation has initialized
                    // the sandbox. Register the EvoSuite-owned HTTP worker at
                    // creation time; otherwise socket writes are treated as
                    // untrusted SUT activity and writeFileDescriptor is denied.
                    Sandbox.addPrivilegedThread(worker);
                } catch (SecurityException | IllegalStateException e) {
                    logger.warn("Could not register LLM request worker as privileged: {}",
                            e.getMessage());
                }
            }
            return worker;
        });
    }

    private void sleepBackoff(int attempt, long deadlineNanos) {
        int exponent = Math.min(Math.max(0, attempt - 1), 20);
        long base = (long) configuration.getRetryBaseDelayMs() * (1L << exponent);
        double jitterFactor = 0.8 + (0.4 * jitterRandom.nextDouble());
        long delay = (long) (base * jitterFactor);
        delay = Math.min(delay, 60_000L);
        if (deadlineNanos != Long.MAX_VALUE) {
            delay = Math.min(delay,
                    TimeUnit.NANOSECONDS.toMillis(
                            Math.max(0L, remainingNanos(deadlineNanos))));
        }
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted during LLM retry backoff");
        }
    }

    private static void ensureBeforeDeadline(long deadlineNanos) {
        if (remainingNanos(deadlineNanos) <= 0) {
            throw new LlmCallFailedException("LLM phase deadline exhausted",
                    new TimeoutException("LLM phase deadline exhausted"), false);
        }
    }

    private static long remainingNanos(long deadlineNanos) {
        if (deadlineNanos == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return deadlineNanos - System.nanoTime();
    }

    private void safeRecordTrace(LlmFeature feature,
                                 List<LlmMessage> messages,
                                 String responseText,
                                 int inputTokens,
                                 int outputTokens,
                                 long latencyMs,
                                 String parseStatus,
                                 int repairAttempt,
                                 boolean expansionAttempted,
                                 List<String> expandedClasses,
                                 String errorType,
                                 QueryOptions options) {
        try {
            traceRecorder.recordCall(new LlmTraceRecorder.CallRecord.Builder()
                    .feature(feature)
                    .messages(messages)
                    .responseText(responseText)
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .latencyMs(latencyMs)
                    .parseStatus(parseStatus)
                    .repairAttempt(repairAttempt)
                    .expansionAttempted(expansionAttempted)
                    .expandedClasses(expandedClasses)
                    .errorType(errorType)
                    .sutContextMode(options.sutContextMode)
                    .contextUnavailable(options.contextUnavailable)
                    .contextTruncated(options.contextTruncated)
                    .contextCommentsStripped(options.contextCommentsStripped)
                    .contextSelectivelyTruncated(options.contextSelectivelyTruncated)
                    .clusterSummaryTruncated(options.clusterSummaryTruncated)
                    .clusterSummaryChars(options.clusterSummaryChars)
                    .dependencySummaryMetadata(options.dependencySummaryMetadata)
                    .build());
        } catch (Throwable traceFailure) {
            logger.warn("LLM trace recording failed; continuing without trace for this call: {}",
                    traceFailure.getClass().getSimpleName());
            logger.debug("LLM trace recording failure details", traceFailure);
        }
    }

    public void recordPostProcessingAssertionDiagnostic(
            LlmTraceRecorder.PostProcessingAssertionDiagnosticRecord record) {
        try {
            traceRecorder.recordPostProcessingAssertionDiagnostic(record);
        } catch (Throwable traceFailure) {
            logger.warn("LLM post-processing diagnostic trace recording failed; continuing without trace: {}",
                    traceFailure.getClass().getSimpleName());
            logger.debug("LLM post-processing diagnostic trace failure details", traceFailure);
        }
    }

    public void recordPostProcessingAssertionLifecycle(
            LlmTraceRecorder.PostProcessingAssertionLifecycleRecord record) {
        try {
            traceRecorder.recordPostProcessingAssertionLifecycle(record);
        } catch (Throwable traceFailure) {
            logger.warn("LLM post-processing lifecycle trace recording failed; continuing without trace: {}",
                    traceFailure.getClass().getSimpleName());
            logger.debug("LLM post-processing lifecycle trace failure details", traceFailure);
        }
    }

    @Override
    public void close() {
        executorService.shutdownNow();
        traceRecorder.close();
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof ExecutionException || current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static boolean isRetryable(Throwable error) {
        if (error == null) {
            return false;
        }

        boolean retryableSignal = false;
        for (Throwable cause : causalChain(error)) {
            if (isExplicitlyNonRetryable(cause)) {
                return false;
            }
            if (isExplicitlyRetryable(cause)) {
                retryableSignal = true;
            }
        }
        return retryableSignal;
    }

    private static boolean isExplicitlyRetryable(Throwable cause) {
        if (cause instanceof TimeoutException
                || cause instanceof SocketTimeoutException
                || cause instanceof ConnectException
                || cause instanceof SocketException
                || cause instanceof InterruptedIOException) {
            return true;
        }

        if (isLangChainNullTextFailure(cause)) {
            return true;
        }

        String text = combinedText(cause);
        return containsHttpCode(text, 429)
                || containsHttpCode(text, 500)
                || containsHttpCode(text, 502)
                || containsHttpCode(text, 503)
                || containsHttpCode(text, 504)
                || text.contains("rate limit")
                || text.contains("too many requests")
                || text.contains("service unavailable")
                || text.contains("gateway timeout")
                || text.contains("temporarily unavailable")
                || text.contains("timeout")
                || text.contains("timed out")
                || text.contains("transient");
    }

    private static boolean isExplicitlyNonRetryable(Throwable cause) {
        String text = combinedText(cause);
        return containsHttpCode(text, 400)
                || containsHttpCode(text, 401)
                || containsHttpCode(text, 403)
                || containsHttpCode(text, 404)
                || containsHttpCode(text, 422)
                || text.contains("invalid api key")
                || text.contains("unauthorized")
                || text.contains("forbidden")
                || text.contains("authentication")
                || text.contains("model not found")
                || text.contains("bad request")
                || text.contains("invalid request")
                || text.contains("malformed");
    }

    /**
     * Detects the LangChain4j {@code ValidationUtils.ensureNotNull(text, "text")}
     * failure that surfaces when a provider returns a response whose content text
     * is null. Pairing the message check with the exception type avoids false
     * positives from unrelated {@code IllegalArgumentException}s whose text
     * happens to include the phrase.
     */
    private static boolean isLangChainNullTextFailure(Throwable cause) {
        if (!(cause instanceof IllegalArgumentException) && !(cause instanceof NullPointerException)) {
            return false;
        }
        String message = cause.getMessage();
        return message != null && message.toLowerCase().contains("text cannot be null");
    }

    private static String combinedText(Throwable throwable) {
        String className = throwable.getClass().getName();
        String message = throwable.getMessage() == null ? "" : throwable.getMessage();
        return (className + " " + message).toLowerCase();
    }

    private static final java.util.Map<Integer, java.util.regex.Pattern> HTTP_CODE_PATTERNS;

    static {
        java.util.Map<Integer, java.util.regex.Pattern> map = new java.util.HashMap<>();
        for (int code : new int[]{400, 401, 403, 404, 422, 429, 500, 502, 503, 504}) {
            map.put(code, java.util.regex.Pattern.compile(".*\\b" + code + "\\b.*", java.util.regex.Pattern.DOTALL));
        }
        HTTP_CODE_PATTERNS = Collections.unmodifiableMap(map);
    }

    private static boolean containsHttpCode(String text, int code) {
        java.util.regex.Pattern pattern = HTTP_CODE_PATTERNS.get(code);
        if (pattern == null) {
            return text.matches(".*\\b" + code + "\\b.*");
        }
        return pattern.matcher(text).matches();
    }

    private static List<Throwable> causalChain(Throwable root) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> visited = new HashSet<>();
        Throwable current = root;
        while (current != null && visited.add(current)) {
            chain.add(current);
            current = current.getCause();
        }
        return chain;
    }

    /**
     * Extracts a human-readable message from an LLM error, stripping away raw
     * JSON and exception class names that are not useful in log output.
     */
    static String friendlyMessage(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String raw = error.getMessage();
        if (raw == null || raw.isEmpty()) {
            return error.getClass().getSimpleName();
        }

        // Try to extract the inner "message" field from JSON error bodies
        // e.g. {"error":{"message":"...","type":"...","code":"..."}}
        Matcher m = JSON_ERROR_MESSAGE.matcher(raw);
        if (m.find()) {
            String inner = m.group(1).trim();
            // The inner message may itself be a Python-style dict string;
            // try to pull the value of 'error' from it.
            Matcher nested = NESTED_ERROR_VALUE.matcher(inner);
            if (nested.find()) {
                return nested.group(1).trim();
            }
            return inner;
        }

        return raw;
    }

    private static final Pattern JSON_ERROR_MESSAGE =
            Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static final Pattern NESTED_ERROR_VALUE =
            Pattern.compile("'error'\\s*:\\s*'((?:[^'\\\\]|\\\\.)*)'");

    private static final Pattern INVALID_MODEL_PATTERN =
            Pattern.compile("(?i)invalid model|model.{0,20}not found|does not exist");

    /**
     * Returns true if the error message indicates the configured model name
     * was rejected by the server.
     */
    static boolean isInvalidModelError(String message) {
        return message != null && INVALID_MODEL_PATTERN.matcher(message).find();
    }

    /**
     * Queries the {@code /v1/models} endpoint and returns a sorted list of
     * available model IDs.  Returns an empty list on any failure (network
     * error, non-OpenAI provider, etc.) — this is a best-effort helper for
     * diagnostics only.
     */
    List<String> fetchAvailableModelIds() {
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

        try {
            logger.debug("Querying available models at {}", endpoint);
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(5_000);
            if (!isBlank(configuration.getApiKey())) {
                conn.setRequestProperty("Authorization", "Bearer " + configuration.getApiKey().trim());
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
        }
    }

    private static final Pattern MODEL_ID_PATTERN =
            Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    public interface ChatLanguageModel {
        LlmResponse generate(List<LlmMessage> messages, LlmFeature feature) throws Exception;
    }

    private static final class UnavailableChatLanguageModel implements ChatLanguageModel {
        @Override
        public LlmResponse generate(List<LlmMessage> messages, LlmFeature feature) {
            throw new IllegalStateException("No chat language model configured");
        }
    }

    private static final class LangChain4jChatLanguageModel implements ChatLanguageModel {

        private final dev.langchain4j.model.chat.ChatLanguageModel delegate;

        private LangChain4jChatLanguageModel(dev.langchain4j.model.chat.ChatLanguageModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public LlmResponse generate(List<LlmMessage> messages, LlmFeature feature) {
            Response<AiMessage> response = delegate.generate(toChatMessages(messages));
            String text = "";
            if (response != null && response.content() != null && response.content().text() != null) {
                text = response.content().text();
            }
            TokenUsage tokenUsage = response == null ? null : response.tokenUsage();
            int inputTokens = tokenUsage != null && tokenUsage.inputTokenCount() != null
                    ? tokenUsage.inputTokenCount()
                    : 0;
            int outputTokens = tokenUsage != null && tokenUsage.outputTokenCount() != null
                    ? tokenUsage.outputTokenCount()
                    : 0;
            return new LlmResponse(text, inputTokens, outputTokens);
        }

        private List<ChatMessage> toChatMessages(List<LlmMessage> messages) {
            List<ChatMessage> converted = new ArrayList<>();
            if (messages == null) {
                return converted;
            }
            for (LlmMessage message : messages) {
                if (message == null) {
                    continue;
                }
                switch (message.getRole()) {
                    case SYSTEM:
                        converted.add(SystemMessage.from(message.getContent()));
                        break;
                    case ASSISTANT:
                        converted.add(AiMessage.from(message.getContent()));
                        break;
                    case USER:
                    default:
                        converted.add(UserMessage.from(message.getContent()));
                        break;
                }
            }
            return converted;
        }
    }

    public static final class LlmResponse {
        private final String text;
        private final int inputTokens;
        private final int outputTokens;

        /**
         * Constructs an LLM response with text and token usage counts.
         */
        public LlmResponse(String text, int inputTokens, int outputTokens) {
            this.text = text;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }

        /**
         * Creates an {@link LlmResponse} with only the response text and zero token counts.
         */
        public static LlmResponse fromText(String text) {
            return new LlmResponse(text, 0, 0);
        }

        public String getText() {
            return text;
        }

        public int getInputTokens() {
            return inputTokens;
        }

        public int getOutputTokens() {
            return outputTokens;
        }
    }
}
