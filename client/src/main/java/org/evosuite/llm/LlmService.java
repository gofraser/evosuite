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
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.provider.RequestCancellation;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.tools.ToolProvider;

import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Entry-point for all LLM calls, including budget and retry enforcement.
 */
public class LlmService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(LlmService.class);
    private static final Object INSTANCE_LOCK = new Object();
    private static volatile LlmService instance;
    private static volatile Boolean compilerAvailableOverrideForTesting = null;

    private volatile ChatLanguageModel model;
    private volatile ChatLanguageModel operatorModel;
    private final LlmBudgetCoordinator budgetCoordinator;
    private final LlmConfiguration configuration;
    private final LlmStatistics statistics;
    private final LlmTraceRecorder traceRecorder;
    private final Random jitterRandom;
    private final TimeoutGuardedRunner timeoutRunner;
    private final boolean available;

    public LlmService(ChatLanguageModel model,
                      LlmBudgetCoordinator budgetCoordinator,
                      LlmConfiguration configuration,
                      LlmStatistics statistics,
                      LlmTraceRecorder traceRecorder) {
        this(model, null, budgetCoordinator, configuration, statistics, traceRecorder, new Random(), true);
    }

    public LlmService(ChatLanguageModel model,
                      LlmBudgetCoordinator budgetCoordinator,
                      LlmConfiguration configuration,
                      LlmStatistics statistics,
                      LlmTraceRecorder traceRecorder,
                      Random jitterRandom) {
        this(model, null, budgetCoordinator, configuration, statistics, traceRecorder, jitterRandom, true);
    }

    /**
     * Constructs an {@link LlmService} with a separate {@link ChatLanguageModel}
     * for the LLM operator features ({@link LlmFeature#SEMANTIC_MUTATION},
     * {@link LlmFeature#SEMANTIC_CROSSOVER}); pass {@code null} for {@code operatorModel}
     * when every feature should use {@code model}.
     */
    public LlmService(ChatLanguageModel model,
                      ChatLanguageModel operatorModel,
                      LlmBudgetCoordinator budgetCoordinator,
                      LlmConfiguration configuration,
                      LlmStatistics statistics,
                      LlmTraceRecorder traceRecorder) {
        this(model, operatorModel, budgetCoordinator, configuration, statistics, traceRecorder, new Random(), true);
    }

    LlmService(ChatLanguageModel model,
               ChatLanguageModel operatorModel,
               LlmBudgetCoordinator budgetCoordinator,
               LlmConfiguration configuration,
               LlmStatistics statistics,
               LlmTraceRecorder traceRecorder,
               Random jitterRandom,
               boolean available) {
        if (configuration.hasSeparateOperatorModel()) {
            if (operatorModel == null) {
                throw new IllegalArgumentException(
                        "configuration declares a separate operator model but operatorModel is null");
            }
        } else if (operatorModel != null) {
            throw new IllegalArgumentException(
                    "operatorModel is set but configuration does not declare a separate operator model");
        }
        this.model = model;
        this.operatorModel = operatorModel;
        this.budgetCoordinator = budgetCoordinator;
        this.configuration = configuration;
        this.statistics = statistics;
        this.statistics.setConfiguration(configuration);
        this.traceRecorder = traceRecorder;
        this.jitterRandom = jitterRandom;
        this.timeoutRunner = new TimeoutGuardedRunner(TimeoutGuardedRunner::newDefaultExecutor,
                this::rebuildModelsAfterStuckCall);
        this.available = available;
    }

    /**
     * Returns the {@link ChatLanguageModel} that {@code feature} resolves to:
     * {@link #operatorModel} when {@code feature} is an operator feature and
     * {@link #configuration} declares a separate operator model, otherwise the
     * primary {@link #model}. This mirrors {@link LlmConfiguration#modelFor(LlmFeature)}
     * exactly, so the model actually used for a call and the model name recorded
     * in traces/statistics can never disagree; the constructor enforces that
     * {@link #operatorModel} is non-null whenever the configuration says so.
     */
    private ChatLanguageModel modelFor(LlmFeature feature) {
        return feature.isOperator() && configuration.hasSeparateOperatorModel() ? operatorModel : model;
    }

    /** Package-private accessor for tests asserting per-feature model resolution. */
    ChatLanguageModel modelForTesting(LlmFeature feature) {
        return modelFor(feature);
    }

    long getExecutorGenerationForTesting() {
        return timeoutRunner.getGeneration();
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

    /** Returns whether the singleton has been created, without creating it. */
    public static boolean hasInstance() {
        return instance != null;
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

    /**
     * Closes the process-scoped LLM resources after a completed EvoSuite run.
     *
     * <p>Call this only after publishing the run statistics.  It deliberately
     * clears the singleton so a later in-process invocation snapshots its own
     * properties, budget, trace file, and provider client instead of inheriting
     * those from the completed run.
     */
    public static void closeAndResetForRunCompletion() {
        try {
            synchronized (INSTANCE_LOCK) {
                if (instance != null) {
                    try {
                        instance.close();
                    } finally {
                        instance = null;
                    }
                }
            }
        } finally {
            LlmStatistics.resetRunCounters();
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
            return unavailableInstance(config, budget, stats, recorder);
        }

        if (!isJdkCompilerAvailable()) {
            String message = "LLM requires jdk.compiler but no system Java compiler is available";
            if (Properties.LLM_REQUIRE_JDK_COMPILER) {
                throw new IllegalStateException(message + " (set -Dllm_require_jdk_compiler=false for soft fallback)");
            }
            logger.warn("{}; disabling LLM features for this run", message);
            return unavailableInstance(config, budget, stats, recorder);
        }

        try {
            ProviderModelFactory.Models models = ProviderModelFactory.createModels(config);
            logActiveLlmConfiguration(config);
            return new LlmService(models.primary, models.operator, budget, config, stats, recorder,
                    new Random(), true);
        } catch (IllegalArgumentException e) {
            logger.warn("LLM provider '{}' misconfigured: {}", config.getProvider(), e.getMessage());
        } catch (RuntimeException e) {
            logger.warn("Failed to initialize LLM provider '{}': {}", config.getProvider(), e.getMessage());
        }
        return unavailableInstance(config, budget, stats, recorder);
    }

    /**
     * Builds an unavailable-provider instance (NONE provider, missing compiler, or a
     * misconfigured provider). Even here the model pair must satisfy the invariant the
     * full constructor enforces: a second placeholder model whenever {@code config}
     * declares a separate operator model (e.g. someone set both model properties while
     * the provider is NONE), {@code null} otherwise.
     */
    private static LlmService unavailableInstance(LlmConfiguration config, LlmBudgetCoordinator budget,
                                                   LlmStatistics stats, LlmTraceRecorder recorder) {
        ChatLanguageModel operatorModel = config.hasSeparateOperatorModel()
                ? new UnavailableChatLanguageModel() : null;
        return new LlmService(new UnavailableChatLanguageModel(), operatorModel, budget, config, stats, recorder,
                new Random(), false);
    }

    private static void logActiveLlmConfiguration(LlmConfiguration config) {
        String baseUrl = config.getBaseUrl();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            baseUrl = "<provider default>";
        }
        if (config.hasSeparateOperatorModel()) {
            LoggingUtils.getEvoLogger().info("* LLM: provider={}, model={}, operatorModel={}, baseUrl={}",
                    config.getProvider(), config.getModel(), config.getOperatorModel(), baseUrl);
        } else {
            LoggingUtils.getEvoLogger().info("* LLM: provider={}, model={}, baseUrl={}",
                    config.getProvider(), config.getModel(), baseUrl);
        }
    }

    private static void warnIfTimeoutTooLarge(int timeoutSeconds) {
        // llm_timeout_seconds bounds the HttpURLConnection read timeout for the
        // whole call. A long value here means any LLM call can stall a worker —
        // and any sandbox-lock holder — for that long. 300s is already very
        // generous for a single LLM call.
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

    /** Returns true if this service has a configured and available LLM provider. */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Returns true if the service has remaining LLM call budget for the given
     * feature. When the budget is partitioned (see {@link LlmBudgetCoordinator})
     * this reports the feature-specific quota; otherwise the global budget.
     */
    public boolean hasBudget(LlmFeature feature) {
        if (!available) {
            return false;
        }
        long remaining = budgetCoordinator.getRemaining(feature);
        return remaining < 0 || remaining > 0;
    }

    /**
     * Whether a call for {@code feature} can start now: the provider is
     * available and the feature has budget left. A call that passes this
     * check can still run out of budget or time before it completes.
     */
    public boolean canCall(LlmFeature feature) {
        return isAvailable() && hasBudget(feature);
    }

    public LlmStatistics getStatistics() {
        return statistics;
    }

    /**
     * Sends {@code request} to the provider, retrying retryable failures within
     * the configured attempts, the call budget and the request's deadline.
     *
     * @return the text of the successful call with its token usage and latency
     * @throws LlmBudgetExceededException when no budget is left for an attempt
     * @throws LlmCallFailedException     when the call fails, times out, runs past
     *                                    the deadline or is interrupted
     */
    public QueryResult query(LlmRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        return queryInternal(request);
    }

    private QueryResult queryInternal(LlmRequest request) {
        List<LlmMessage> messages = request.getMessages();
        LlmFeature feature = request.getFeature();
        long deadlineNanos = request.getDeadlineNanos();
        if (!available) {
            throw new LlmCallFailedException("LLM service is unavailable",
                    new IllegalStateException("LLM provider is not configured"), false);
        }
        int maxTries = Math.max(1, configuration.getRetryMaxAttempts() + 1);
        Throwable lastError = null;

        for (int attempt = 1; attempt <= maxTries; attempt++) {
            if (lastError != null && PhaseDeadline.remainingNanos(deadlineNanos) <= 0) {
                recordAbandonedRetries(request, lastError);
            }
            ensureBeforeDeadline(deadlineNanos);
            if (!budgetCoordinator.tryAcquire(feature)) {
                if (lastError != null) {
                    recordAbandonedRetries(request, lastError);
                    throw new LlmBudgetExceededException("LLM call budget exhausted on attempt " + attempt
                            + " after: " + LlmErrorClassifier.friendlyMessage(lastError), lastError);
                }
                throw new LlmBudgetExceededException("LLM call budget exhausted on attempt " + attempt);
            }
            long start = System.currentTimeMillis();
            try {
                LlmResponse response = invokeWithTimeout(
                        messages, feature, deadlineNanos);
                long latency = System.currentTimeMillis() - start;
                boolean truncated = response.getOutputTokens() > 0
                        && response.getOutputTokens() >= configuration.getMaxTokens() - 1;
                if (truncated) {
                    logger.warn("LLM response likely truncated: outputTokens={} >= maxTokens={}. "
                                    + "Consider increasing -Dllm_max_tokens.",
                            response.getOutputTokens(), configuration.getMaxTokens());
                }
                String status = truncated ? "TRUNCATED" : "SUCCESS";
                statistics.recordCall(feature, response.getInputTokens(), response.getCachedInputTokens(),
                        response.getOutputTokens(), latency);
                safeRecordTrace(request, response.getText(), response.getInputTokens(),
                        response.getCachedInputTokens(), response.getOutputTokens(), latency, status, "");
                return new QueryResult(response.getText(), response.getInputTokens(),
                        response.getCachedInputTokens(), response.getOutputTokens(), latency);
            } catch (Exception e) {
                lastError = LlmErrorClassifier.unwrap(e);
                long latency = System.currentTimeMillis() - start;

                if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                    // Future.get() clears the interrupted flag before throwing.
                    // Do not turn a shutdown request into an ordinary provider
                    // failure or continue with a retry.
                    statistics.recordInterruptedAttempt(feature, latency);
                    safeRecordTrace(request, "", 0, 0, 0, latency,
                            "INTERRUPTED", lastError.getClass().getSimpleName());
                    Thread.currentThread().interrupt();
                    throw new LlmCallFailedException("LLM query interrupted", lastError, false);
                }
                boolean retryable = LlmErrorClassifier.isRetryable(lastError);
                if (!retryable || attempt == maxTries || PhaseDeadline.remainingNanos(deadlineNanos) <= 0L) {
                    String friendly = LlmErrorClassifier.friendlyMessage(lastError);
                    if (!retryable) {
                        logger.warn("LLM call failed (attempt {}/{}): {}", attempt, maxTries, friendly);
                    } else {
                        logger.warn("LLM call failed after {} attempts: {}", attempt, friendly);
                    }
                    LlmModelListDiagnostics.logIfModelRejected(configuration, feature, friendly,
                            deadlineNanos);
                    logger.debug("Full LLM error detail", lastError);
                    if (lastError instanceof TimeoutException) {
                        statistics.recordTimeout(feature, latency);
                    } else {
                        statistics.recordFailure(feature, latency);
                    }
                    safeRecordTrace(request, "", 0, 0, 0,
                            latency, "FAILED", lastError.getClass().getSimpleName());
                    throw new LlmCallFailedException(
                            "LLM query failed after " + attempt + " attempt(s): " + friendly, lastError, retryable);
                }
                // LLM_Calls measures provider attempts, while the failed and
                // timed-out counters retain their documented final-outcome
                // semantics.  Record retries separately so costs and traces
                // do not hide transient provider work.
                statistics.recordRetryAttempt(feature, latency);
                safeRecordTrace(request, "", 0, 0, 0, latency,
                        "RETRYING", lastError.getClass().getSimpleName());
                logger.debug("LLM call failed (attempt {}/{}): {}; retrying...",
                        attempt, maxTries, LlmErrorClassifier.friendlyMessage(lastError));
                if (!sleepBackoff(attempt, deadlineNanos)) {
                    // The attempt is already counted; only the trace needs a final
                    // row, as for an interrupted attempt.
                    safeRecordTrace(request, "", 0, 0, 0, 0L,
                            "INTERRUPTED", lastError.getClass().getSimpleName());
                    Thread.currentThread().interrupt();
                    throw new LlmCallFailedException("LLM retry interrupted", lastError, false);
                }
            }
        }

        throw new LlmCallFailedException("LLM query failed", lastError, false);
    }

    /**
     * Gives a call whose last attempt was recorded as RETRYING a final outcome
     * when no further attempt can start, so the counters and the trace do not
     * end on a retry that never completed.
     */
    private void recordAbandonedRetries(LlmRequest request, Throwable lastError) {
        statistics.recordRetriesAbandoned(request.getFeature(), lastError instanceof TimeoutException);
        safeRecordTrace(request, "", 0, 0, 0, 0L, "FAILED", lastError.getClass().getSimpleName());
    }

    private LlmResponse invokeWithTimeout(List<LlmMessage> messages, LlmFeature feature,
                                          long deadlineNanos) throws Exception {
        ChatLanguageModel snapshot = modelFor(feature);
        long configuredNanos = TimeUnit.SECONDS.toNanos(
                Math.max(1, configuration.getTimeoutSeconds()));
        return timeoutRunner.run(cancellation -> snapshot.generate(messages, feature, cancellation),
                configuredNanos, deadlineNanos);
    }

    /**
     * Rebuilds the {@link ChatLanguageModel}s after a stuck worker forced the
     * {@link TimeoutGuardedRunner} to replace its executor, so that the next
     * call opens a fresh {@code HttpURLConnection} rather than risk any
     * connection reuse tied to the leaked worker.
     */
    private void rebuildModelsAfterStuckCall() {
        if (available && configuration.getProvider() != Properties.LlmProvider.NONE) {
            try {
                ProviderModelFactory.Models rebuilt = ProviderModelFactory.createModels(configuration);
                // Publish both together, only once both constructions succeeded, so a
                // failure part-way through (e.g. the operator model throwing) can never
                // leave model/operatorModel as a pair inconsistent with
                // configuration.hasSeparateOperatorModel() — the invariant the
                // constructor enforces.
                this.model = rebuilt.primary;
                this.operatorModel = rebuilt.operator;
                if (rebuilt.operator != null) {
                    LoggingUtils.getEvoLogger().info(
                            "* LLM: rebuilt {} client after stuck call (model={}, operatorModel={})",
                            configuration.getProvider(), configuration.getModel(), configuration.getOperatorModel());
                } else {
                    LoggingUtils.getEvoLogger().info(
                            "* LLM: rebuilt {} client after stuck call (model={})",
                            configuration.getProvider(), configuration.getModel());
                }
            } catch (Throwable t) {
                logger.warn("Failed to rebuild LLM model after stuck call; keeping existing instance: {}",
                        t.getMessage());
            }
        }
    }

    private boolean sleepBackoff(int attempt, long deadlineNanos) {
        int exponent = Math.min(Math.max(0, attempt - 1), 20);
        long base = (long) configuration.getRetryBaseDelayMs() * (1L << exponent);
        double jitterFactor = 0.8 + (0.4 * jitterRandom.nextDouble());
        long delay = (long) (base * jitterFactor);
        delay = Math.min(delay, 60_000L);
        if (deadlineNanos != PhaseDeadline.NONE) {
            // Rounded up: sleeping the whole milliseconds below the remaining time wakes
            // up with a fraction of a millisecond left, and the loop then starts an attempt
            // that cannot finish and still spends a call of the budget.
            long remainingNanos = Math.max(0L, PhaseDeadline.remainingNanos(deadlineNanos));
            delay = Math.min(delay, (remainingNanos + 999_999L) / 1_000_000L);
        }
        if (delay <= 0) {
            return true;
        }
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException interruptedException) {
            logger.debug("Interrupted during LLM retry backoff");
            return false;
        }
    }

    private static void ensureBeforeDeadline(long deadlineNanos) {
        if (PhaseDeadline.remainingNanos(deadlineNanos) <= 0) {
            throw new LlmCallFailedException("LLM phase deadline exhausted",
                    new TimeoutException("LLM phase deadline exhausted"), false);
        }
    }

    private void safeRecordTrace(LlmRequest request,
                                 String responseText,
                                 int inputTokens,
                                 int cachedInputTokens,
                                 int outputTokens,
                                 long latencyMs,
                                 String parseStatus,
                                 String errorType) {
        try {
            LlmTraceRecorder.CallRecord.Builder record = new LlmTraceRecorder.CallRecord.Builder()
                    .feature(request.getFeature())
                    .messages(request.getMessages())
                    .responseText(responseText)
                    .inputTokens(inputTokens)
                    .cachedInputTokens(cachedInputTokens)
                    .outputTokens(outputTokens)
                    .latencyMs(latencyMs)
                    .parseStatus(parseStatus)
                    .repairAttempt(request.getRepairAttempt())
                    .expansionAttempted(request.isExpansionAttempted())
                    .expandedClasses(request.getExpandedClasses())
                    .errorType(errorType);
            PromptResult context = request.getContext();
            if (context != null) {
                record.sutContextMode(context.getSutContextMode())
                        .contextUnavailable(context.isContextUnavailable())
                        .contextTruncated(context.isContextTruncated())
                        .contextCommentsStripped(context.isContextCommentsStripped())
                        .contextSelectivelyTruncated(context.isContextSelectivelyTruncated())
                        .clusterSummaryTruncated(context.isClusterSummaryTruncated())
                        .clusterSummaryChars(context.getClusterSummaryChars())
                        .dependencySummaryMetadata(context.getDependencySummaryMetadata());
            }
            traceRecorder.recordCall(record.build());
        } catch (Throwable traceFailure) {
            logTraceFailure("call", traceFailure);
        }
    }

    /** A trace is best effort: log a failure to record one and carry on. */
    private static void logTraceFailure(String what, Throwable traceFailure) {
        logger.warn("LLM {} trace recording failed; continuing without trace: {}",
                what, traceFailure.getClass().getSimpleName());
        logger.debug("LLM {} trace recording failure details", what, traceFailure);
    }

    /**
     * Whether trace records are written, so that callers can skip building them otherwise.
     *
     * @return true when tracing is enabled
     */
    public boolean isTraceEnabled() {
        return traceRecorder.isTraceEnabled();
    }

    /**
     * Records an assertion diagnostic of post-processing in the trace; a failure to write the trace is logged and
     * does not propagate.
     *
     * @param record the diagnostic to record
     */
    public void recordPostProcessingAssertionDiagnostic(
            LlmTraceRecorder.PostProcessingAssertionDiagnosticRecord record) {
        try {
            traceRecorder.recordPostProcessingAssertionDiagnostic(record);
        } catch (Throwable traceFailure) {
            logTraceFailure("post-processing diagnostic", traceFailure);
        }
    }

    /**
     * Records an assertion lifecycle event of post-processing in the trace; a failure to write the trace is logged
     * and does not propagate.
     *
     * @param record the lifecycle event to record
     */
    public void recordPostProcessingAssertionLifecycle(
            LlmTraceRecorder.PostProcessingAssertionLifecycleRecord record) {
        try {
            traceRecorder.recordPostProcessingAssertionLifecycle(record);
        } catch (Throwable traceFailure) {
            logTraceFailure("post-processing lifecycle", traceFailure);
        }
    }

    @Override
    public void close() {
        timeoutRunner.close();
    }

    public interface ChatLanguageModel {
        LlmResponse generate(List<LlmMessage> messages, LlmFeature feature) throws Exception;

        /**
         * Same as {@link #generate(List, LlmFeature)}, but with a {@link
         * RequestCancellation} handle scoped to this one call. Providers
         * backed by {@link org.evosuite.llm.provider.JsonHttpClient} attach
         * the handle to their connection so that if {@code Future.cancel(true)}
         * fails to stop a worker blocked in a non-interruptible read,
         * {@code invokeWithTimeout} can force just this request's connection
         * closed — without disturbing any other request the model may have
         * in flight concurrently. The default implementation ignores the
         * handle and delegates to the 2-arg overload, which is enough for
         * models with nothing to abort (e.g. test doubles).
         */
        default LlmResponse generate(List<LlmMessage> messages, LlmFeature feature,
                                     RequestCancellation cancellation) throws Exception {
            return generate(messages, feature);
        }
    }

    private static final class UnavailableChatLanguageModel implements ChatLanguageModel {
        @Override
        public LlmResponse generate(List<LlmMessage> messages, LlmFeature feature) {
            throw new IllegalStateException("No chat language model configured");
        }
    }
}
