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

import org.evosuite.llm.LlmStatistics.FeatureSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the combined {@code LLM_Operator_*} call/token/latency totals
 * sum exactly the {@code SEMANTIC_MUTATION} and {@code SEMANTIC_CROSSOVER}
 * feature snapshots, which is what {@link LlmStatistics#publishRuntimeVariables()}
 * publishes.
 */
class LlmStatisticsOperatorTotalsTest {

    @Test
    void sumsSemanticMutationAndCrossoverFeatureSnapshots() {
        LlmStatistics stats = new LlmStatistics();
        stats.recordCall(LlmFeature.SEMANTIC_MUTATION, 10, 2, 5, 100L);
        stats.recordCall(LlmFeature.SEMANTIC_MUTATION, 20, 3, 6, 150L);
        stats.recordCall(LlmFeature.SEMANTIC_CROSSOVER, 30, 4, 7, 200L);
        // Another feature must not be folded into the operator totals.
        stats.recordCall(LlmFeature.SEEDING, 1000, 1000, 1000, 1000L);

        Map<LlmFeature, FeatureSnapshot> snapshots = stats.getFeatureSnapshots();
        LlmStatistics.OperatorFeatureTotals totals = LlmStatistics.OperatorFeatureTotals.of(snapshots);

        assertEquals(3L, totals.calls);
        assertEquals(60L, totals.inputTokens);
        assertEquals(9L, totals.cachedInputTokens);
        assertEquals(18L, totals.outputTokens);
        assertEquals(450L, totals.latencyMs);
    }
}
