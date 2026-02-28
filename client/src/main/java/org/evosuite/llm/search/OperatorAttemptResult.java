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
package org.evosuite.llm.search;

/**
 * Result of an LLM operator attempt (mutation or crossover).
 * Tracks whether the semantic path was attempted, applied, or fell back,
 * along with the reason for skipping if applicable.
 */
public class OperatorAttemptResult {

    /** Why the semantic operator was not attempted. */
    public enum SkipReason {
        NONE,           // semantic was attempted
        DISABLED,       // LLM_OPERATOR_ENABLED=false
        PROBABILITY,    // probability gate rejected
        NOT_CONFIGURED  // operator wrapper is null
    }

    private final boolean attemptedSemantic;
    private final boolean appliedSemantic;
    private final boolean fallbackUsed;
    private final SkipReason skipReason;

    private OperatorAttemptResult(boolean attemptedSemantic, boolean appliedSemantic,
                                  boolean fallbackUsed, SkipReason skipReason) {
        this.attemptedSemantic = attemptedSemantic;
        this.appliedSemantic = appliedSemantic;
        this.fallbackUsed = fallbackUsed;
        this.skipReason = skipReason;
    }

    /** Semantic path was attempted and successfully applied. */
    public static OperatorAttemptResult semanticApplied() {
        return new OperatorAttemptResult(true, true, false, SkipReason.NONE);
    }

    /** Semantic path was attempted but failed; standard fallback was used. */
    public static OperatorAttemptResult semanticFallback() {
        return new OperatorAttemptResult(true, false, true, SkipReason.NONE);
    }

    /** Semantic path was not attempted; standard operator used directly. */
    public static OperatorAttemptResult standardOnly(SkipReason reason) {
        return new OperatorAttemptResult(false, false, false, reason);
    }

    public boolean isAttemptedSemantic() { return attemptedSemantic; }
    public boolean isAppliedSemantic() { return appliedSemantic; }
    public boolean isFallbackUsed() { return fallbackUsed; }
    public SkipReason getSkipReason() { return skipReason; }

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
        if (appliedSemantic) return DisruptionEvent.OperatorOutcome.APPLIED;
        if (fallbackUsed) return DisruptionEvent.OperatorOutcome.FALLBACK;
        return DisruptionEvent.OperatorOutcome.APPLIED; // standard applied
    }
}
