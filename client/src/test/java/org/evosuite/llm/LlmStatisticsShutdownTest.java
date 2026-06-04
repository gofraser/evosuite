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

import org.evosuite.llm.search.ProblemCardType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the contract that {@link LlmStatistics#initializeRuntimeVariables()}
 * only publishes zero defaults and does <em>not</em> clear the in-process
 * accumulators. Pre-search enrichment populates those accumulators before the
 * GA's shutdown hook runs; if {@code initializeRuntimeVariables} reset them,
 * {@code LLM_Constants_Added_SUT} and {@code LLM_Object_Pool_Sequences_Added}
 * would always be reported as 0 for runs that use only pre-evolution enrichment
 * strategies (no stagnation assistance).
 */
class LlmStatisticsShutdownTest {

    @BeforeEach
    void resetCounters() {
        LlmStatistics.resetSeedingCounters();
        LlmStatistics.resetDiagnosticCardCounters();
    }

    @AfterEach
    void tearDown() {
        LlmStatistics.resetSeedingCounters();
        LlmStatistics.resetDiagnosticCardCounters();
    }

    @Test
    void initializeRuntimeVariablesPreservesSeedingCounters() {
        LlmStatistics.recordSutConstantsAdded(5);
        LlmStatistics.recordNonSutConstantsAdded(2);
        LlmStatistics.recordObjectPoolSequencesAdded(3);
        LlmStatistics.recordCastClassSuggestions(7);
        LlmStatistics.recordCastClassesAccepted(4);

        // Simulates GeneticAlgorithm#shutdownLlmAssistance taking the
        // "no stagnation, but LLM provider may have been enabled" path.
        LlmStatistics.initializeRuntimeVariables();

        assertEquals(5L, LlmStatistics.getSutConstantsAdded(),
                "SUT constants populated by pre-search enrichment must survive shutdown defaults");
        assertEquals(2L, LlmStatistics.getNonSutConstantsAdded());
        assertEquals(3L, LlmStatistics.getObjectPoolSequencesAdded(),
                "object pool sequences populated by pre-search enrichment must survive shutdown defaults");
        assertEquals(7L, LlmStatistics.getCastClassSuggestions());
        assertEquals(4L, LlmStatistics.getCastClassesAccepted());
    }

    @Test
    void initializeRuntimeVariablesPreservesDiagnosticCounters() {
        LlmStatistics.recordDiagnosticCoverageGain(ProblemCardType.UNREACHED_METHOD, 3);
        LlmStatistics.recordDiagnosticCoverageGain(ProblemCardType.BRANCH_POLARITY_GAP, 2);

        LlmStatistics.initializeRuntimeVariables();

        assertEquals(5L, LlmStatistics.getDiagnosticCoverageGains());
        assertEquals(3L, LlmStatistics.getDiagnosticCoverageGains(ProblemCardType.UNREACHED_METHOD));
        assertEquals(2L, LlmStatistics.getDiagnosticCoverageGains(ProblemCardType.BRANCH_POLARITY_GAP));
    }

    @Test
    void initializeRuntimeVariablesIsIdempotentOnFreshState() {
        LlmStatistics.initializeRuntimeVariables();
        LlmStatistics.initializeRuntimeVariables();

        assertEquals(0L, LlmStatistics.getSutConstantsAdded());
        assertEquals(0L, LlmStatistics.getObjectPoolSequencesAdded());
        assertEquals(0L, LlmStatistics.getCastClassSuggestions());
        assertEquals(0L, LlmStatistics.getDiagnosticCoverageGains());
    }
}
