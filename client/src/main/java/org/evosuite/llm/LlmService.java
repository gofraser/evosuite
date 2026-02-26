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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.*;
import javax.tools.ToolProvider;

/**
 * Entry-point for all LLM calls, including budget and retry enforcement.
 */
public class LlmService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(LlmService.class);
    private static final Object INSTANCE_LOCK = new Object();
    private static volatile LlmService instance;
    private static volatile Boolean compilerAvailableOverrideForTesting = null;

    private final ChatLanguageModel model;
    private final LlmBudgetCoordinator budgetCoordinator;
    private final LlmConfiguration configuration;
    private final LlmStatistics statistics;
    private final LlmTraceRecorder traceRecorder;
    private final Random jitterRandom;
    private final ExecutorService executorService;
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
        this.executorService = Executors.newSingleThreadExecutor();
        this.available = available;
    }

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

    public static void setInstanceForTesting(LlmService service) {
        synchronized (INSTANCE_LOCK) {
            if (instance != null && instance != service) {
                instance.close();
            }
            instance = service;
        }
    }

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
        LlmBudgetCoordinator budget = LlmBudgetCoordinator.fromProperties();
        LlmStatistics stats = new LlmStatistics();
        LlmTraceRecorder recorder = new LlmTraceRecorder(config);

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
            return new LlmService(model, budget, config, stats, recorder, new Random(), true);
        } catch (IllegalArgumentException e) {
            logger.warn("LLM provider '{}' misconfigured: {}", config.getProvider(), e.getMessage());
        } catch (RuntimeException e) {
            logger.warn("Failed to initialize LLM provider '{}': {}", config.getProvider(), e.getMessage());
        }
        return new LlmService(new UnavailableChatLanguageModel(), budget, config, stats, recorder, new Random(), false);
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
                return createOpenAiModel(config);
            case OLLAMA:
                return createOllamaModel(config);
            case ANTHROPIC:
                return createAnthropicModel(config);
            default:
                throw new IllegalArgumentException("Unsupported LLM provider: " + config.getProvider());
        }
    }

    private static ChatLanguageModel createOpenAiModel(LlmConfiguration config) {
        String modelName = requireNonBlank(config.getModel(), "LLM model must be configured for OPENAI");
        String apiKey = requireNonBlank(config.getApiKey(), "LLM API key must be configured for OPENAI");
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
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

    public boolean isAvailable() {
        return available;
    }

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
        return queryInternal(messages, feature, null, false);
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
                promptResult.getSutContextMode(), promptResult.isContextUnavailable());
    }

    private String queryInternal(List<LlmMessage> messages, LlmFeature feature,
                                 Properties.LlmSutContextMode sutContextMode,
                                 boolean contextUnavailable) {
        if (!available) {
            throw new LlmCallFailedException("LLM service is unavailable",
                    new IllegalStateException("LLM provider is not configured"), false);
        }
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
                        Collections.<String>emptyList(), "",
                        sutContextMode, contextUnavailable);
                return response.getText();
            } catch (Exception e) {
                lastError = unwrap(e);
                boolean retryable = isRetryable(lastError);
                if (!retryable || attempt == maxTries) {
                    statistics.recordFailure(feature);
                    traceRecorder.recordCall(feature, messages, "", 0, 0,
                            System.currentTimeMillis() - start, "FAILED", attempt,
                            false, Collections.<String>emptyList(), lastError.getClass().getSimpleName(),
                            sutContextMode, contextUnavailable);
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
