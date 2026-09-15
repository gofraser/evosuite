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

    /**
     * Reserve one call budget unit for the given feature, if available.
     * Implementations that partition the budget by feature (see
     * {@link FeatureShare}) override this; the default forwards to the
     * global, feature-agnostic {@link #tryAcquire()}.
     *
     * @param feature the feature attempting to spend budget
     * @return true when reservation succeeded
     */
    default boolean tryAcquire(LlmFeature feature) {
        return tryAcquire();
    }

    /**
     * Returns the remaining budget for the given feature, or -1 when
     * unlimited. Implementations that partition the budget by feature (see
     * {@link FeatureShare}) override this; the default forwards to the
     * global, feature-agnostic {@link #getRemaining()}.
     *
     * @param feature the feature asking about remaining budget
     * @return remaining budget for that feature, or -1 when unlimited
     */
    default long getRemaining(LlmFeature feature) {
        return getRemaining();
    }

    /**
     * Builds the coordinator configured by the current {@link Properties}.
     * Returns a {@link FeatureShare} when the LLM operators and the
     * stagnation assistance are both enabled under a finite call cap, so
     * that operator calls cannot starve out (or be starved by) the
     * stagnation prompt; otherwise returns a plain {@link Local} counter.
     *
     * @return the configured budget coordinator
     */
    static LlmBudgetCoordinator fromProperties() {
        Local total = new Local(Properties.LLM_MAX_CALLS);
        if (Properties.LLM_MAX_CALLS > 0
                && Properties.LLM_OPERATOR_MODE != Properties.LlmOperatorMode.OFF
                && Properties.LLM_ON_STAGNATION) {
            return new FeatureShare(total, Properties.LLM_MAX_CALLS, Properties.LLM_OPERATOR_CALL_SHARE);
        }
        return total;
    }

    /**
     * Process-local atomic budget implementation used in non-island mode.
     */
    final class Local implements LlmBudgetCoordinator {

        private final AtomicInteger remaining;

        public Local(int maxCalls) {
            this(maxCalls, true);
        }

        /**
         * @param maxCalls               the call cap
         * @param nonPositiveIsUnlimited when true, {@code maxCalls <= 0} means unlimited
         *                               (the {@link Properties#LLM_MAX_CALLS} convention);
         *                               when false, a computed quota of exactly 0 stays 0.
         *                               Used internally by {@link FeatureShare}, whose quotas
         *                               can legitimately round down to zero calls.
         */
        private Local(int maxCalls, boolean nonPositiveIsUnlimited) {
            this.remaining = new AtomicInteger(
                    nonPositiveIsUnlimited ? (maxCalls <= 0 ? -1 : maxCalls) : Math.max(0, maxCalls));
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

    /**
     * Reserves a share of the total budget for the LLM operator calls
     * ({@link LlmFeature#SEMANTIC_MUTATION}, {@link LlmFeature#SEMANTIC_CROSSOVER})
     * so that they cannot be crowded out by (or crowd out) every other LLM
     * feature, chiefly {@link LlmFeature#STAGNATION}. Each feature bucket
     * draws from its own {@link Local} quota first, then from the shared
     * delegate that tracks the actual total; the delegate is the same total
     * as the two quotas combined, so it can only refuse once both quotas are
     * already exhausted.
     */
    final class FeatureShare implements LlmBudgetCoordinator {

        private final LlmBudgetCoordinator delegate;
        private final Local operatorQuota;
        private final Local otherQuota;

        /**
         * @param delegate  the coordinator tracking the overall total
         * @param maxCalls  the overall call cap the delegate was built from
         * @param share     the fraction of {@code maxCalls} reserved for operator calls
         */
        public FeatureShare(LlmBudgetCoordinator delegate, int maxCalls, double share) {
            this.delegate = delegate;
            int operatorCalls = (int) Math.round(share * maxCalls);
            this.operatorQuota = new Local(operatorCalls, false);
            this.otherQuota = new Local(maxCalls - operatorCalls, false);
        }

        private Local quotaFor(LlmFeature feature) {
            return isOperatorFeature(feature) ? operatorQuota : otherQuota;
        }

        private static boolean isOperatorFeature(LlmFeature feature) {
            return feature == LlmFeature.SEMANTIC_MUTATION || feature == LlmFeature.SEMANTIC_CROSSOVER;
        }

        @Override
        public boolean tryAcquire() {
            return delegate.tryAcquire();
        }

        @Override
        public long getRemaining() {
            return delegate.getRemaining();
        }

        @Override
        public boolean tryAcquire(LlmFeature feature) {
            if (!quotaFor(feature).tryAcquire()) {
                return false;
            }
            return delegate.tryAcquire();
        }

        @Override
        public long getRemaining(LlmFeature feature) {
            return Math.min(quotaFor(feature).getRemaining(), delegate.getRemaining());
        }
    }
}
