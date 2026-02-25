package org.evosuite.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Entry-point for all LLM calls, including budget and retry enforcement.
 */
public class LlmService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(LlmService.class);

    private final ChatLanguageModel model;
    private final LlmBudgetCoordinator budgetCoordinator;
    private final LlmConfiguration configuration;
    private final LlmStatistics statistics;
    private final LlmTraceRecorder traceRecorder;
    private final Random jitterRandom;
    private final ExecutorService executorService;

    public LlmService(ChatLanguageModel model,
                      LlmBudgetCoordinator budgetCoordinator,
                      LlmConfiguration configuration,
                      LlmStatistics statistics,
                      LlmTraceRecorder traceRecorder) {
        this(model, budgetCoordinator, configuration, statistics, traceRecorder, new Random());
    }

    public LlmService(ChatLanguageModel model,
                      LlmBudgetCoordinator budgetCoordinator,
                      LlmConfiguration configuration,
                      LlmStatistics statistics,
                      LlmTraceRecorder traceRecorder,
                      Random jitterRandom) {
        this.model = model;
        this.budgetCoordinator = budgetCoordinator;
        this.configuration = configuration;
        this.statistics = statistics;
        this.traceRecorder = traceRecorder;
        this.jitterRandom = jitterRandom;
        this.executorService = Executors.newSingleThreadExecutor();
    }

    public String query(List<LlmMessage> messages, LlmFeature feature) {
        int maxTries = Math.max(1, configuration.getRetryMaxAttempts() + 1);
        Throwable lastError = null;

        for (int attempt = 1; attempt <= maxTries; attempt++) {
            if (!budgetCoordinator.tryAcquire()) {
                throw new LlmBudgetExceededException("LLM call budget exhausted on attempt " + attempt);
            }
            long start = System.currentTimeMillis();
            try {
                LlmResponse response = invokeWithTimeout(messages, feature);
                long latency = System.currentTimeMillis() - start;
                statistics.recordCall(feature, response.getInputTokens(), response.getOutputTokens(), latency);
                traceRecorder.recordCall(feature, messages, response.getText(), response.getInputTokens(),
                        response.getOutputTokens(), latency, "SUCCESS", attempt, false,
                        Collections.<String>emptyList(), "");
                return response.getText();
            } catch (Exception e) {
                lastError = unwrap(e);
                boolean retryable = isRetryable(lastError);
                if (!retryable || attempt == maxTries) {
                    statistics.recordFailure(feature);
                    traceRecorder.recordCall(feature, messages, "", 0, 0,
                            System.currentTimeMillis() - start, "FAILED", attempt,
                            false, Collections.<String>emptyList(), lastError.getClass().getSimpleName());
                    throw new LlmCallFailedException(
                            "LLM query failed after " + attempt + " attempt(s)", lastError, retryable);
                }
                sleepBackoff(attempt);
            }
        }

        throw new LlmCallFailedException("LLM query failed", lastError, false);
    }

    private LlmResponse invokeWithTimeout(List<LlmMessage> messages, LlmFeature feature) throws Exception {
        Future<LlmResponse> future = executorService.submit(new Callable<LlmResponse>() {
            @Override
            public LlmResponse call() throws Exception {
                return model.generate(messages, feature);
            }
        });
        try {
            return future.get(configuration.getTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    private void sleepBackoff(int attempt) {
        int exponent = Math.min(Math.max(0, attempt - 1), 20);
        long base = (long) configuration.getRetryBaseDelayMs() * (1L << exponent);
        double jitterFactor = 0.8 + (0.4 * jitterRandom.nextDouble());
        long delay = (long) (base * jitterFactor);
        delay = Math.min(delay, 60_000L);
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

    @Override
    public void close() {
        executorService.shutdownNow();
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

    private static String combinedText(Throwable throwable) {
        String className = throwable.getClass().getName();
        String message = throwable.getMessage() == null ? "" : throwable.getMessage();
        return (className + " " + message).toLowerCase();
    }

    private static boolean containsHttpCode(String text, int code) {
        String token = String.valueOf(code);
        return text.matches(".*\\b" + token + "\\b.*");
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

    public interface ChatLanguageModel {
        LlmResponse generate(List<LlmMessage> messages, LlmFeature feature) throws Exception;
    }

    public static final class LlmResponse {
        private final String text;
        private final int inputTokens;
        private final int outputTokens;

        public LlmResponse(String text, int inputTokens, int outputTokens) {
            this.text = text;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }

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
