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

import org.evosuite.llm.search.ExtractorCandidateMetric;
import org.evosuite.llm.search.ExtractorRejectReason;
import org.evosuite.statistics.RuntimeVariable;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ColumnCountersTest {

    private enum Unknown {
        NO_SUCH_COLUMN
    }

    @Test
    void resolvesEveryConstantToItsColumnByName() {
        Map<ExtractorRejectReason, RuntimeVariable> rejects =
                ColumnCounters.columns(ExtractorRejectReason.class, "LLM_Diagnostic_Extractor_Rejects_", "");
        assertEquals(RuntimeVariable.LLM_Diagnostic_Extractor_Rejects_BlockedTypeMappingFailure,
                rejects.get(ExtractorRejectReason.BLOCKED_TYPE_MAPPING_FAILURE));

        Map<ExtractorCandidateMetric, RuntimeVariable> candidates = ColumnCounters.columns(
                ExtractorCandidateMetric.class, "LLM_Diagnostic_Extractor_Candidates_", "");
        assertEquals(ExtractorCandidateMetric.values().length, candidates.size());
        assertEquals(RuntimeVariable.LLM_Diagnostic_Extractor_Candidates_ExceptionBarrierSuppressedLowFailureRate,
                candidates.get(ExtractorCandidateMetric.EXCEPTION_BARRIER_SUPPRESSED_LOW_FAILURE_RATE));

        Map<LlmFeature, RuntimeVariable> cached =
                ColumnCounters.columns(LlmFeature.class, "LLM_", "_Cached_Input_Tokens");
        assertEquals(RuntimeVariable.LLM_ConstantPoolEnrichment_Cached_Input_Tokens,
                cached.get(LlmFeature.CONSTANT_POOL_ENRICHMENT));
    }

    @Test
    void aConstantWithoutAColumnFailsInsteadOfSharingANeighbour() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ColumnCounters.columns(Unknown.class, "LLM_Diagnostic_Extractor_Rejects_", ""));
        assertEquals("No runtime variable LLM_Diagnostic_Extractor_Rejects_NoSuchColumn for NO_SUCH_COLUMN",
                failure.getMessage());
    }
}
