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
