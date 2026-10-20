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

import org.evosuite.strategy.FailureStreakPolicy.Verdict;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class FailureStreakPolicyTest {

    private static final long NOW = System.nanoTime();
    // A streak that began ten seconds ago, against a deadline that has passed.
    private static final long LONG_AGO = NOW - 10_000_000_000L;

    private static LlmStrategy.QueryOutcome ok() {
        return LlmStrategy.QueryOutcome.success(Collections.emptyList(), 0L);
    }

    @Test
    public void failedRoundsKeepTheHintUntilTheLimitStopsTheRun() {
        FailureStreakPolicy policy = new FailureStreakPolicy(2, NOW);
        // No time left, so the streak cannot restart.
        long deadline = NOW;

        LlmStrategy.QueryOutcome bad = LlmStrategy.QueryOutcome.failure("no code block");
        assertEquals(Verdict.NEXT_ROUND, policy.onFollowUpRound(bad, 0, LONG_AGO, deadline));
        assertEquals("no code block", policy.previousFailureHint());
        assertEquals(Verdict.STOP_PARSE_FAIL_STREAK, policy.onFollowUpRound(bad, 0, LONG_AGO, deadline));
        assertEquals(2, policy.parseFailureCount());
    }

    @Test
    public void executionFailuresAreNotParseFailures() {
        FailureStreakPolicy policy = new FailureStreakPolicy(1, NOW);
        LlmStrategy.QueryOutcome dropped =
                LlmStrategy.QueryOutcome.droppedAtExecution("all failed", 0L);
        assertEquals(Verdict.STOP_EXECUTION_FAIL_STREAK,
                policy.onFollowUpRound(dropped, 0, LONG_AGO, NOW));
        assertEquals(0, policy.parseFailureCount());
    }

    @Test
    public void budgetLeftRestartsWithoutFeedback() {
        FailureStreakPolicy policy = new FailureStreakPolicy(1, NOW);
        LlmStrategy.QueryOutcome bad = LlmStrategy.QueryOutcome.failure("broken");
        assertEquals(Verdict.NEXT_ROUND,
                policy.onFollowUpRound(bad, 0, NOW, Long.MAX_VALUE));
        assertNull(policy.previousFailureHint());
    }

    @Test
    public void providerFailuresAndSuccessesDoNotCountAsFailedRounds() {
        FailureStreakPolicy policy = new FailureStreakPolicy(1, NOW);
        LlmStrategy.QueryOutcome outage = LlmStrategy.QueryOutcome.callFailure("down", 0L);
        assertEquals(Verdict.PROCEED, policy.onFollowUpRound(outage, 0, NOW, NOW));
        assertEquals(Verdict.PROCEED, policy.onFollowUpRound(ok(), 1, NOW, NOW));
        assertEquals(0, policy.parseFailureCount());
        assertNull(policy.previousFailureHint());
    }
}
