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

import java.util.function.LongSupplier;

/**
 * Single source of truth for orchestrator-level wall-clock waits on
 * repair-capable LLM producers (seeding, pool enrichment, stagnation
 * injection, single-prompt strategy).
 *
 * <p>{@link Properties#LLM_TIMEOUT_SECONDS} is the per-call HTTP timeout used
 * by {@code LlmService.query()}. A repair-capable producer may issue up to
 * {@code 1 + LLM_REPAIR_ATTEMPTS} LLM calls per parse attempt, so any
 * orchestrator waiting on its completion should budget
 * {@code LLM_TIMEOUT_SECONDS × (LLM_REPAIR_ATTEMPTS + 1)} seconds at worst.
 *
 * <p>The clamped variants additionally cap the wait at a caller-supplied
 * remaining-budget value (e.g. the search-phase remaining time), so a slow
 * LLM cannot consume more wall-clock than the search has left. A supplier
 * returning a negative value opts out of clamping.
 *
 * <p>This class is distinct from {@link LlmBudgetCoordinator}, which tracks
 * <em>call-count</em> budget. {@code LlmWaitBudget} is about wall-clock.
 */
public final class LlmWaitBudget {

    private LlmWaitBudget() {}

    /**
     * Worst-case wall-clock wait in seconds, unclamped. Suitable for callers
     * outside an active search phase (pre-search smoke, tests).
     */
    public static long repairAwareWaitSeconds() {
        long timeout = Math.max(1L, Properties.LLM_TIMEOUT_SECONDS);
        long attempts = Math.max(0L, Properties.LLM_REPAIR_ATTEMPTS);
        return Math.max(1L, timeout * (attempts + 1L));
    }

    /**
     * Worst-case wall-clock wait in milliseconds, unclamped.
     */
    public static long repairAwareWaitMillis() {
        return repairAwareWaitSeconds() * 1000L;
    }

    /**
     * Worst-case wall-clock wait in seconds, clamped to the remaining budget
     * returned by {@code remainingBudgetSecondsSupplier}. A supplier returning
     * a negative value (or null supplier) means "no clamp". The result is
     * floored at 1 second.
     */
    public static long repairAwareWaitSeconds(LongSupplier remainingBudgetSecondsSupplier) {
        long derived = repairAwareWaitSeconds();
        if (remainingBudgetSecondsSupplier == null) {
            return derived;
        }
        long remaining = remainingBudgetSecondsSupplier.getAsLong();
        if (remaining < 0) {
            return derived;
        }
        return Math.max(1L, Math.min(derived, remaining));
    }

    /**
     * Worst-case wall-clock wait in milliseconds, clamped to the remaining
     * budget returned by {@code remainingBudgetMillisSupplier}. A supplier
     * returning a negative value (or null supplier) means "no clamp". The
     * result is floored at 1 millisecond.
     */
    public static long repairAwareWaitMillis(LongSupplier remainingBudgetMillisSupplier) {
        long derived = repairAwareWaitMillis();
        if (remainingBudgetMillisSupplier == null) {
            return derived;
        }
        long remaining = remainingBudgetMillisSupplier.getAsLong();
        if (remaining < 0) {
            return derived;
        }
        return Math.max(1L, Math.min(derived, remaining));
    }
}
