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

    @BeforeEach
    void snapshotProperties() {
        savedTimeout = Properties.LLM_TIMEOUT_SECONDS;
        savedAttempts = Properties.LLM_REPAIR_ATTEMPTS;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_TIMEOUT_SECONDS = savedTimeout;
        Properties.LLM_REPAIR_ATTEMPTS = savedAttempts;
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
    void clampLimitsWaitToRemainingBudget() {
        Properties.LLM_TIMEOUT_SECONDS = 180;
        Properties.LLM_REPAIR_ATTEMPTS = 4;
        assertEquals(300L, LlmWaitBudget.repairAwareWaitSeconds(() -> 300L));
    }

    @Test
    void clampDoesNotInflateWhenRemainingExceedsDerived() {
        Properties.LLM_TIMEOUT_SECONDS = 30;
        Properties.LLM_REPAIR_ATTEMPTS = 2;
        assertEquals(90L, LlmWaitBudget.repairAwareWaitSeconds(() -> 600L));
    }

    @Test
    void negativeSupplierMeansNoClamp() {
        Properties.LLM_TIMEOUT_SECONDS = 180;
        Properties.LLM_REPAIR_ATTEMPTS = 4;
        assertEquals(900L, LlmWaitBudget.repairAwareWaitSeconds(() -> -1L));
        assertEquals(900_000L, LlmWaitBudget.repairAwareWaitMillis(() -> -1L));
    }

    @Test
    void nullSupplierMeansNoClamp() {
        Properties.LLM_TIMEOUT_SECONDS = 60;
        Properties.LLM_REPAIR_ATTEMPTS = 4;
        assertEquals(300L, LlmWaitBudget.repairAwareWaitSeconds(null));
        assertEquals(300_000L, LlmWaitBudget.repairAwareWaitMillis(null));
    }

    @Test
    void zeroRemainingIsFlooredAtOne() {
        Properties.LLM_TIMEOUT_SECONDS = 60;
        Properties.LLM_REPAIR_ATTEMPTS = 4;
        assertEquals(1L, LlmWaitBudget.repairAwareWaitSeconds(() -> 0L));
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
}
