package org.evosuite.llm;

import org.evosuite.Properties;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinates LLM call budget consumption.
 */
public interface LlmBudgetCoordinator {

    /**
     * Reserve one call budget unit if available.
     *
     * @return true when reservation succeeded
     */
    boolean tryAcquire();

    /**
     * Returns the remaining budget, or -1 when unlimited.
     *
     * @return remaining budget or -1 when unlimited
     */
    long getRemaining();

    static LlmBudgetCoordinator fromProperties() {
        return new Local(Properties.LLM_MAX_CALLS);
    }

    /**
     * Process-local atomic budget implementation used in non-island mode.
     */
    final class Local implements LlmBudgetCoordinator {

        private final AtomicInteger remaining;

        public Local(int maxCalls) {
            this.remaining = new AtomicInteger(maxCalls <= 0 ? -1 : maxCalls);
        }

        @Override
        public boolean tryAcquire() {
            while (true) {
                int current = remaining.get();
                if (current < 0) {
                    return true;
                }
                if (current == 0) {
                    return false;
                }
                if (remaining.compareAndSet(current, current - 1)) {
                    return true;
                }
            }
        }

        @Override
        public long getRemaining() {
            return remaining.get();
        }
    }
}
