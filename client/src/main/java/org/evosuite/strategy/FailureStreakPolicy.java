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
package org.evosuite.strategy;

import org.evosuite.llm.PhaseDeadline;
import org.evosuite.utils.LoggingUtils;

/**
 * Tracks consecutive ITERATIVE_BUDGETED rounds without usable tests, whether the response did not
 * parse or every parsed test failed at execution. It owns the failure hint fed into the next
 * prompt, the parse-failure count, and the decision to restart the conversation or give up once
 * {@code LLM_STRATEGY_PARSE_FAIL_LIMIT} such rounds follow each other.
 */
final class FailureStreakPolicy {

    /** What the loop does after a follow-up round. */
    enum Verdict {
        /** Carry on with the no-progress check. */
        PROCEED,
        /** Skip the no-progress check and start the next round. */
        NEXT_ROUND,
        /** Leave the loop because parsing kept failing. */
        STOP_PARSE_FAIL_STREAK,
        /** Leave the loop because every parsed test kept failing at execution. */
        STOP_EXECUTION_FAIL_STREAK
    }

    private final int parseFailLimit;
    private int parseFailureCount;
    private int failedRoundStreak;
    private long failedStreakStartedNanos;
    private int consecutiveRestarts;
    private String previousFailureHint;

    FailureStreakPolicy(int parseFailLimit, long strategyStartedNanos) {
        this.parseFailLimit = parseFailLimit;
        this.failedStreakStartedNanos = strategyStartedNanos;
    }

    /** The failure summary of the previous round, or {@code null} when there is nothing to feed back. */
    String previousFailureHint() {
        return previousFailureHint;
    }

    int parseFailureCount() {
        return parseFailureCount;
    }

    /** Accounts for the initial round, which never ends the run by itself. */
    void onInitialRound(LlmStrategy.QueryOutcome initial) {
        if (initial.failureSummary == null) {
            return;
        }
        LoggingUtils.getEvoLogger().info(
                "* Initial broad-coverage query produced no usable tests: {}",
                initial.failureSummary);
        if (!initial.callFailed) {
            previousFailureHint = initial.failureSummary;
            if (!initial.droppedAtExecution) {
                parseFailureCount++;
            }
            failedRoundStreak++;
        }
    }

    /**
     * Accounts for a follow-up round and decides how the loop proceeds.
     *
     * @param added             tests the round added to the suite
     * @param roundStartedNanos start of the round, kept as the start of a new failure streak
     * @param deadlineNanos     end of the search phase
     */
    Verdict onFollowUpRound(LlmStrategy.QueryOutcome outcome, int added,
                            long roundStartedNanos, long deadlineNanos) {
        boolean failedRound = outcome.failureSummary != null && added == 0
                && !outcome.callFailed;
        if (failedRound) {
            if (!outcome.droppedAtExecution) {
                parseFailureCount++;
            }
            if (failedRoundStreak == 0) {
                failedStreakStartedNanos = roundStartedNanos;
            }
            failedRoundStreak++;
            previousFailureHint = outcome.failureSummary;
            if (parseFailLimit > 0 && failedRoundStreak >= parseFailLimit) {
                long streakMillis = LlmStrategy.elapsedMillis(failedStreakStartedNanos);
                if (LlmStrategy.canRestartAfterFailedRounds(
                        PhaseDeadline.remainingMillis(deadlineNanos),
                        streakMillis, consecutiveRestarts)) {
                    // Budget remains: drop the failure feedback, which tends to
                    // anchor the LLM on its broken approach, and re-prompt from
                    // scratch instead of ending the run.
                    consecutiveRestarts++;
                    LoggingUtils.getEvoLogger().info(
                            "* {} consecutive iterations produced no usable tests; "
                                    + "re-prompting without failure feedback (restart {} of {})",
                            failedRoundStreak, consecutiveRestarts,
                            LlmStrategy.MAX_CONSECUTIVE_FAILED_ROUND_RESTARTS);
                    failedRoundStreak = 0;
                    previousFailureHint = null;
                    return Verdict.NEXT_ROUND;
                }
                LoggingUtils.getEvoLogger().info(
                        "* {} consecutive iterations produced no usable tests, stopping",
                        failedRoundStreak);
                return outcome.droppedAtExecution
                        ? Verdict.STOP_EXECUTION_FAIL_STREAK
                        : Verdict.STOP_PARSE_FAIL_STREAK;
            }
        } else if (!outcome.callFailed) {
            failedRoundStreak = 0;
            previousFailureHint = null;
            if (added > 0) {
                consecutiveRestarts = 0;
            }
        }
        if (failedRound && parseFailLimit > 0) {
            // Bounded by the failed-round guard above; a round without usable
            // tests is not a round without new coverage.
            return Verdict.NEXT_ROUND;
        }
        return Verdict.PROCEED;
    }
}
