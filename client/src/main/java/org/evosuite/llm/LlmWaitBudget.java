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
 * {@code 1 + LLM_REPAIR_ATTEMPTS} LLM calls per parse attempt, and each call
 * may be tried {@code 1 + LLM_RETRY_MAX_ATTEMPTS} times with a backoff before
 * every retry. An orchestrator waiting on its completion should therefore
 * budget {@code (LLM_REPAIR_ATTEMPTS + 1)} times the worst case of one call:
 * {@code LLM_TIMEOUT_SECONDS × (LLM_RETRY_MAX_ATTEMPTS + 1)} plus the backoffs.
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

    private LlmWaitBudget() {
    }

    /**
     * Worst-case wall-clock wait in seconds, unclamped. Suitable for callers
     * outside an active search phase (pre-search smoke, tests).
     */
    public static long repairAwareWaitSeconds() {
        long millis = repairAwareWaitMillis();
        if (millis == Long.MAX_VALUE) {
            return Long.MAX_VALUE / 1000L;
        }
        return Math.max(1L, (millis + 999L) / 1000L);
    }

    /**
     * Worst-case wall-clock wait in milliseconds, unclamped.
     */
    public static long repairAwareWaitMillis() {
        long attempts = Math.max(0L, Properties.LLM_REPAIR_ATTEMPTS);
        long perCall = saturatingMultiply(worstCaseCallMillis(), attempts + 1L);
        return Math.max(1000L, perCall);
    }

    /**
     * Worst case for one {@code LlmService} call: every try runs into the
     * timeout, and every retry is preceded by its (jittered, capped) backoff.
     */
    private static long worstCaseCallMillis() {
        long timeoutMillis = saturatingMultiply(Math.max(1L, Properties.LLM_TIMEOUT_SECONDS), 1000L);
        long retries = Math.max(0L, Properties.LLM_RETRY_MAX_ATTEMPTS);
        long total = saturatingAdd(timeoutMillis, saturatingMultiply(timeoutMillis, retries));
        long baseDelay = Math.max(1L, Properties.LLM_RETRY_BASE_DELAY_MS);
        for (long retry = 1; retry <= retries && total < Long.MAX_VALUE; retry++) {
            // Mirrors LlmService.sleepBackoff: base * 2^(retry-1), up to +20% jitter, 60 s cap.
            long exponent = Math.min(retry - 1L, 20L);
            long delay = Math.min(60_000L, baseDelay * (1L << exponent) * 6L / 5L);
            total = saturatingAdd(total, delay);
            if (delay == 60_000L) {
                total = saturatingAdd(total, saturatingMultiply(60_000L, retries - retry));
                break;
            }
        }
        return total;
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

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    /**
     * The absolute {@link System#nanoTime()} deadline at which the remaining
     * budget returned by {@code remainingBudgetSecondsSupplier} runs out, or
     * {@link Long#MAX_VALUE} when there is none: a null supplier, or one
     * returning a negative value.
     */
    public static long deadlineNanos(LongSupplier remainingBudgetSecondsSupplier) {
        if (remainingBudgetSecondsSupplier == null) {
            return Long.MAX_VALUE;
        }
        long remaining = remainingBudgetSecondsSupplier.getAsLong();
        if (remaining < 0) {
            return Long.MAX_VALUE;
        }
        long now = System.nanoTime();
        long remainingNanos = saturatingMultiply(remaining, 1_000_000_000L);
        return now > Long.MAX_VALUE - remainingNanos ? Long.MAX_VALUE : now + remainingNanos;
    }

    private static long saturatingMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }
}
