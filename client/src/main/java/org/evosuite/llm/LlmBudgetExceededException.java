package org.evosuite.llm;

/**
 * Raised when a caller asks for an LLM response after budget exhaustion.
 */
public class LlmBudgetExceededException extends RuntimeException {

    public LlmBudgetExceededException(String message) {
        super(message);
    }
}
