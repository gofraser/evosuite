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
 *
 * <p>By default every feature draws from one counter. When the LLM operators and the stagnation
 * assistance can both spend under a finite cap (see {@link #fromProperties()}), a share of the
 * total is reserved for the operator calls ({@link LlmFeature#SEMANTIC_MUTATION},
 * {@link LlmFeature#SEMANTIC_CROSSOVER}) so that they cannot be crowded out by (or crowd out)
 * every other feature, chiefly {@link LlmFeature#STAGNATION}. The two quotas sum to the total,
 * so each is a hard cap on its own bucket and the total needs no counter of its own.
 */
public final class LlmBudgetCoordinator {

    /** Counter for every feature, or for the non-operator features when a share is reserved. */
    private final AtomicInteger otherRemaining;

    /** Operator quota, or {@code null} when the budget is not partitioned. */
    private final AtomicInteger operatorRemaining;

    private LlmBudgetCoordinator(AtomicInteger otherRemaining, AtomicInteger operatorRemaining) {
        this.otherRemaining = otherRemaining;
        this.operatorRemaining = operatorRemaining;
    }

    /**
     * Creates an unpartitioned budget.
     *
     * @param maxCalls the call cap; {@code maxCalls <= 0} means unlimited (the
     *                 {@link Properties#LLM_MAX_CALLS} convention)
     * @return the budget
     */
    public static LlmBudgetCoordinator of(int maxCalls) {
        return new LlmBudgetCoordinator(new AtomicInteger(maxCalls <= 0 ? -1 : maxCalls), null);
    }

    /**
     * Creates a budget that reserves a fraction of {@code maxCalls} for the operator features.
     * A quota can legitimately round down to zero calls, and then stays zero.
     *
     * @param maxCalls the overall call cap
     * @param share    the fraction of {@code maxCalls} reserved for operator calls
     * @return the budget
     */
    public static LlmBudgetCoordinator withOperatorShare(int maxCalls, double share) {
        int operatorCalls = (int) Math.round(share * maxCalls);
        return new LlmBudgetCoordinator(
                new AtomicInteger(Math.max(0, maxCalls - operatorCalls)),
                new AtomicInteger(Math.max(0, operatorCalls)));
    }

    /**
     * Builds the coordinator configured by the current {@link Properties}.
     * Partitions the budget when the LLM operators and the stagnation assistance are both
     * enabled under a finite call cap; otherwise returns a plain counter.
     *
     * @return the configured budget coordinator
     */
    static LlmBudgetCoordinator fromProperties() {
        if (Properties.LLM_MAX_CALLS > 0 && Properties.LLM_ON_STAGNATION && operatorsCanSpend()) {
            return withOperatorShare(Properties.LLM_MAX_CALLS, Properties.LLM_OPERATOR_CALL_SHARE);
        }
        return of(Properties.LLM_MAX_CALLS);
    }

    /**
     * Whether the current {@link Properties#LLM_OPERATOR_MODE} configuration could actually spend
     * an operator call this run: {@code ASYNC} needs a positive {@link
     * Properties#LLM_OPERATOR_CALLS_PER_GENERATION}, {@code SYNC} needs a positive {@link
     * Properties#LLM_MUTATION_PROBABILITY}, or a positive {@link Properties#LLM_CROSSOVER_PROBABILITY}
     * with {@link Properties#LLM_OPERATOR_CROSSOVER} also on (a crossover probability alone spends
     * nothing when crossover targets aren't offered), and {@code OFF} never can. Reserving a share
     * of the budget for operators that cannot spend it (see {@link #fromProperties()}) would just
     * strand it, unreachable by every other feature.
     *
     * <p>Package-private for tests.
     */
    static boolean operatorsCanSpend() {
        switch (Properties.LLM_OPERATOR_MODE) {
            case ASYNC:
                return Properties.LLM_OPERATOR_CALLS_PER_GENERATION > 0;
            case SYNC:
                return Properties.LLM_MUTATION_PROBABILITY > 0
                        || (Properties.LLM_CROSSOVER_PROBABILITY > 0 && Properties.LLM_OPERATOR_CROSSOVER);
            case OFF:
            default:
                return false;
        }
    }

    /**
     * Reserves one call budget unit for the given feature, if available.
     *
     * @param feature the feature attempting to spend budget
     * @return true when reservation succeeded
     */
    public boolean tryAcquire(LlmFeature feature) {
        AtomicInteger counter = counterFor(feature);
        while (true) {
            int current = counter.get();
            if (current < 0) {
                return true;
            }
            if (current == 0) {
                return false;
            }
            if (counter.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }

    /**
     * Returns the remaining budget for the given feature, or -1 when unlimited.
     *
     * @param feature the feature asking about remaining budget
     * @return remaining budget for that feature, or -1 when unlimited
     */
    public long getRemaining(LlmFeature feature) {
        return counterFor(feature).get();
    }

    private AtomicInteger counterFor(LlmFeature feature) {
        return operatorRemaining != null && feature.isOperator() ? operatorRemaining : otherRemaining;
    }
}
