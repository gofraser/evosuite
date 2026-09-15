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
package org.evosuite.llm.search;

import org.evosuite.llm.search.operator.OperatorTarget;

/**
 * Result of an LLM operator attempt (mutation or crossover).
 * Tracks whether the semantic path was attempted, applied, or fell back,
 * along with the reason for skipping if applicable.
 */
public class OperatorAttemptResult {

    /** Why the semantic operator was not attempted. */
    public enum SkipReason {
        NONE,           // semantic was attempted
        DISABLED,       // llm_operator_mode is not SYNC
        PROBABILITY,    // probability gate rejected
        NOT_CONFIGURED, // operator wrapper is null
        NO_TARGET       // probability gate passed, but the selector gave this parent no target
    }

    private final boolean attemptedSemantic;
    private final boolean appliedSemantic;
    private final boolean fallbackUsed;
    private final SkipReason skipReason;
    private final OperatorTarget target;
    private final String attemptId;

    private OperatorAttemptResult(boolean attemptedSemantic, boolean appliedSemantic,
                                  boolean fallbackUsed, SkipReason skipReason,
                                  OperatorTarget target, String attemptId) {
        this.attemptedSemantic = attemptedSemantic;
        this.appliedSemantic = appliedSemantic;
        this.fallbackUsed = fallbackUsed;
        this.skipReason = skipReason;
        this.target = target;
        this.attemptId = attemptId;
    }

    /** Semantic path was attempted and successfully applied, with no target/attempt tracking. */
    public static OperatorAttemptResult semanticApplied() {
        return new OperatorAttemptResult(true, true, false, SkipReason.NONE, null, null);
    }

    /** Semantic path was attempted and successfully applied for {@code target}, under {@code attemptId} (nullable). */
    public static OperatorAttemptResult semanticApplied(OperatorTarget target, String attemptId) {
        return new OperatorAttemptResult(true, true, false, SkipReason.NONE, target, attemptId);
    }

    /** Semantic path was attempted but failed; standard fallback was used. */
    public static OperatorAttemptResult semanticFallback() {
        return new OperatorAttemptResult(true, false, true, SkipReason.NONE, null, null);
    }

    /** Semantic path was attempted for {@code target} but failed; standard fallback was used. */
    public static OperatorAttemptResult semanticFallback(OperatorTarget target) {
        return new OperatorAttemptResult(true, false, true, SkipReason.NONE, target, null);
    }

    /** Semantic path was not attempted; standard operator used directly. */
    public static OperatorAttemptResult standardOnly(SkipReason reason) {
        return new OperatorAttemptResult(false, false, false, reason, null, null);
    }

    public boolean isAttemptedSemantic() {
        return attemptedSemantic;
    }

    public boolean isAppliedSemantic() {
        return appliedSemantic;
    }

    public boolean isFallbackUsed() {
        return fallbackUsed;
    }

    public SkipReason getSkipReason() {
        return skipReason;
    }

    /** The selector target this attempt used, or null when none was involved. */
    public OperatorTarget getTarget() {
        return target;
    }

    /** The repeated-injection attempt id this outcome should be recorded under, or null. */
    public String getAttemptId() {
        return attemptId;
    }

    /**
     * Derive disruption event source from this result.
     * SEMANTIC if attempted (regardless of outcome); STANDARD otherwise.
     */
    public DisruptionEvent.OperatorSource toOperatorSource() {
        return attemptedSemantic
                ? DisruptionEvent.OperatorSource.SEMANTIC
                : DisruptionEvent.OperatorSource.STANDARD;
    }

    /**
     * Derive disruption event outcome from this result.
     */
    public DisruptionEvent.OperatorOutcome toOperatorOutcome() {
        if (appliedSemantic) {
            return DisruptionEvent.OperatorOutcome.APPLIED;
        }
        if (fallbackUsed) {
            return DisruptionEvent.OperatorOutcome.FALLBACK;
        }
        return DisruptionEvent.OperatorOutcome.APPLIED; // standard applied
    }
}
