package org.evosuite.llm;

/**
 * Raised when an LLM request fails (after retries for retryable failures).
 */
public class LlmCallFailedException extends RuntimeException {

    private final boolean retryable;

    public LlmCallFailedException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
