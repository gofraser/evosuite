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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmWaitBudgetTest {

    private int savedTimeout;
    private int savedAttempts;
    private int savedRetries;
    private int savedBaseDelay;

    @BeforeEach
    void snapshotProperties() {
        savedTimeout = Properties.LLM_TIMEOUT_SECONDS;
        savedAttempts = Properties.LLM_REPAIR_ATTEMPTS;
        savedRetries = Properties.LLM_RETRY_MAX_ATTEMPTS;
        savedBaseDelay = Properties.LLM_RETRY_BASE_DELAY_MS;
        // The tests below isolate the repair multiplier; retries have their own tests.
        Properties.LLM_RETRY_MAX_ATTEMPTS = 0;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_TIMEOUT_SECONDS = savedTimeout;
        Properties.LLM_REPAIR_ATTEMPTS = savedAttempts;
        Properties.LLM_RETRY_MAX_ATTEMPTS = savedRetries;
        Properties.LLM_RETRY_BASE_DELAY_MS = savedBaseDelay;
    }

    @Test
    void retriesAndBackoffExtendTheWorstCaseOfOneCall() {
        Properties.LLM_TIMEOUT_SECONDS = 60;
        Properties.LLM_REPAIR_ATTEMPTS = 0;
        Properties.LLM_RETRY_MAX_ATTEMPTS = 2;
        Properties.LLM_RETRY_BASE_DELAY_MS = 250;
        // Three attempts of 60 s plus at most 300 ms and 600 ms of backoff (20% jitter),
        // rounded up to whole seconds.
        assertEquals(181L, LlmWaitBudget.repairAwareWaitSeconds());
        assertEquals(180_900L, LlmWaitBudget.repairAwareWaitMillis());
    }

    @Test
    void retryBudgetIsPaidOncePerRepairCall() {
        Properties.LLM_TIMEOUT_SECONDS = 10;
        Properties.LLM_REPAIR_ATTEMPTS = 1;
        Properties.LLM_RETRY_MAX_ATTEMPTS = 1;
        Properties.LLM_RETRY_BASE_DELAY_MS = 1000;
        // Per call: 2 x 10 s + 1.2 s backoff; two calls.
        assertEquals(2 * 21_200L, LlmWaitBudget.repairAwareWaitMillis());
    }

    @Test
    void backoffOfASingleRetryIsCappedAtOneMinute() {
        Properties.LLM_TIMEOUT_SECONDS = 1;
        Properties.LLM_REPAIR_ATTEMPTS = 0;
        Properties.LLM_RETRY_MAX_ATTEMPTS = 1;
        Properties.LLM_RETRY_BASE_DELAY_MS = 1_000_000;
        assertEquals(2_000L + 60_000L, LlmWaitBudget.repairAwareWaitMillis());
    }

    @Test
    void defaultsProduceTimeoutTimesAttemptsPlusOne() {
        Properties.LLM_TIMEOUT_SECONDS = 60;
        Properties.LLM_REPAIR_ATTEMPTS = 4;
        assertEquals(300L, LlmWaitBudget.repairAwareWaitSeconds());
        assertEquals(300_000L, LlmWaitBudget.repairAwareWaitMillis());
    }

    @Test
    void phase3ConfigurationProducesNinetySeconds() {
        Properties.LLM_TIMEOUT_SECONDS = 30;
        Properties.LLM_REPAIR_ATTEMPTS = 2;
        assertEquals(90L, LlmWaitBudget.repairAwareWaitSeconds());
    }

    @Test
    void zeroAttemptsCollapsesToTimeout() {
        Properties.LLM_TIMEOUT_SECONDS = 180;
        Properties.LLM_REPAIR_ATTEMPTS = 0;
        assertEquals(180L, LlmWaitBudget.repairAwareWaitSeconds());
    }

    @Test
    void negativeSupplierMeansNoClamp() {
        Properties.LLM_TIMEOUT_SECONDS = 180;
        Properties.LLM_REPAIR_ATTEMPTS = 4;
        assertEquals(900_000L, LlmWaitBudget.repairAwareWaitMillis(() -> -1L));
    }

    @Test
    void nullSupplierMeansNoClamp() {
        Properties.LLM_TIMEOUT_SECONDS = 60;
        Properties.LLM_REPAIR_ATTEMPTS = 4;
        assertEquals(300_000L, LlmWaitBudget.repairAwareWaitMillis(null));
    }

    @Test
    void zeroRemainingIsFlooredAtOne() {
        Properties.LLM_TIMEOUT_SECONDS = 60;
        Properties.LLM_REPAIR_ATTEMPTS = 4;
        assertEquals(1L, LlmWaitBudget.repairAwareWaitMillis(() -> 0L));
    }

    @Test
    void nonPositivePropertyValuesAreFlooredAtOne() {
        Properties.LLM_TIMEOUT_SECONDS = 0;
        Properties.LLM_REPAIR_ATTEMPTS = -3;
        assertEquals(1L, LlmWaitBudget.repairAwareWaitSeconds());
    }

    @Test
    void millisMatchesSecondsTimesThousand() {
        Properties.LLM_TIMEOUT_SECONDS = 45;
        Properties.LLM_REPAIR_ATTEMPTS = 3;
        long seconds = LlmWaitBudget.repairAwareWaitSeconds();
        assertEquals(seconds * 1000L, LlmWaitBudget.repairAwareWaitMillis());
    }

    @Test
    void millisSaturatesInsteadOfOverflowingForValidLargeProperties() {
        Properties.LLM_TIMEOUT_SECONDS = Integer.MAX_VALUE;
        Properties.LLM_REPAIR_ATTEMPTS = Integer.MAX_VALUE;

        assertEquals(Long.MAX_VALUE, LlmWaitBudget.repairAwareWaitMillis());
    }
}
