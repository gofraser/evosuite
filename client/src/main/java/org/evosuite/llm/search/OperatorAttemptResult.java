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
        NO_BUDGET,      // the LLM service is unavailable or its call budget is spent
        NO_TARGET       // probability gate passed, but the selector gave this parent no target
    }

    /** Which path the offspring went through. */
    private enum Path { STANDARD_ONLY, SEMANTIC_APPLIED, SEMANTIC_FALLBACK }

    private final Path path;
    private final SkipReason skipReason;
    private final OperatorTarget target;
    private final String attemptId;
    private final int generation;

    private OperatorAttemptResult(Path path, SkipReason skipReason,
                                  OperatorTarget target, String attemptId, int generation) {
        this.path = path;
        this.skipReason = skipReason;
        this.target = target;
        this.attemptId = attemptId;
        this.generation = generation;
    }

    /** Semantic path was attempted and successfully applied, with no target/attempt tracking. */
    public static OperatorAttemptResult semanticApplied() {
        return new OperatorAttemptResult(Path.SEMANTIC_APPLIED, SkipReason.NONE, null, null, -1);
    }

    /** Semantic path was attempted and successfully applied for {@code target}, under {@code attemptId} (nullable). */
    public static OperatorAttemptResult semanticApplied(OperatorTarget target, String attemptId) {
        return semanticApplied(target, attemptId, -1);
    }

    /**
     * Semantic path was attempted and successfully applied for {@code target}, under {@code
     * attemptId} (nullable), from the {@code generation} the target's plan was built for.
     */
    public static OperatorAttemptResult semanticApplied(OperatorTarget target, String attemptId, int generation) {
        return new OperatorAttemptResult(Path.SEMANTIC_APPLIED, SkipReason.NONE, target, attemptId, generation);
    }

    /** Semantic path was attempted but failed; standard fallback was used. */
    public static OperatorAttemptResult semanticFallback() {
        return new OperatorAttemptResult(Path.SEMANTIC_FALLBACK, SkipReason.NONE, null, null, -1);
    }

    /** Semantic path was attempted for {@code target} but failed; standard fallback was used. */
    public static OperatorAttemptResult semanticFallback(OperatorTarget target) {
        return new OperatorAttemptResult(Path.SEMANTIC_FALLBACK, SkipReason.NONE, target, null, -1);
    }

    /** Semantic path was not attempted; standard operator used directly. */
    public static OperatorAttemptResult standardOnly(SkipReason reason) {
        return new OperatorAttemptResult(Path.STANDARD_ONLY, reason, null, null, -1);
    }

    public boolean isAttemptedSemantic() {
        return path != Path.STANDARD_ONLY;
    }

    public boolean isAppliedSemantic() {
        return path == Path.SEMANTIC_APPLIED;
    }

    public boolean isFallbackUsed() {
        return path == Path.SEMANTIC_FALLBACK;
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

    /** The generation the target's {@code SyncTargetPlan} was built for; {@code -1} when not tracked. */
    public int getGeneration() {
        return generation;
    }

    /**
     * Derive disruption event source from this result.
     * SEMANTIC if attempted (regardless of outcome); STANDARD otherwise.
     */
    public DisruptionEvent.OperatorSource toOperatorSource() {
        return isAttemptedSemantic()
                ? DisruptionEvent.OperatorSource.SEMANTIC
                : DisruptionEvent.OperatorSource.STANDARD;
    }

    /** Derive disruption event outcome from this result: FALLBACK for a failed semantic attempt, APPLIED otherwise. */
    public DisruptionEvent.OperatorOutcome toOperatorOutcome() {
        return path == Path.SEMANTIC_FALLBACK
                ? DisruptionEvent.OperatorOutcome.FALLBACK
                : DisruptionEvent.OperatorOutcome.APPLIED;
    }
}
