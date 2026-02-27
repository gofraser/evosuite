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
package org.evosuite.ga.diversity;

import org.evosuite.Properties;
import org.evosuite.Properties.SpeciationMetric;
import org.evosuite.statistics.RuntimeVariable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase 5 property defaults and enum values.
 */
class Phase5PropertyDefaultsTest {

    @Test
    void llmOperatorDefaultsAreCorrect() {
        assertFalse(Properties.LLM_OPERATOR_ENABLED);
        assertEquals(0.1, Properties.LLM_MUTATION_PROBABILITY, 1e-9);
        assertEquals(0.1, Properties.LLM_CROSSOVER_PROBABILITY, 1e-9);
        assertEquals(2, Properties.LLM_OPERATOR_MAX_ATTEMPTS);
    }

    @Test
    void speciationDefaultsAreCorrect() {
        assertFalse(Properties.SPECIATION_ENABLED);
        assertEquals(SpeciationMetric.TRACE_BRANCH_JACCARD, Properties.SPECIATION_METRIC);
        assertEquals(0.3, Properties.SPECIATION_THRESHOLD, 1e-9);
        assertEquals(0.5, Properties.SPECIES_SURVIVAL_CAP, 1e-9);
        assertFalse(Properties.SPECIES_BALANCE_PARENT_SELECTION);
        assertFalse(Properties.SPECIES_RESTRICT_MATING);
        assertTrue(Properties.SPECIES_TIMELINE_ENABLED);
        assertFalse(Properties.SPECIES_LARGEST_SHARE_TIMELINE_ENABLED);
        assertEquals(0.7, Properties.SPECIATION_HYBRID_PHENOTYPIC_WEIGHT, 1e-9);
    }

    @Test
    void speciationMetricEnumHasAllValues() {
        SpeciationMetric[] values = SpeciationMetric.values();
        assertEquals(5, values.length);
        assertNotNull(SpeciationMetric.valueOf("TRACE_BRANCH_JACCARD"));
        assertNotNull(SpeciationMetric.valueOf("TRACE_LINE_JACCARD"));
        assertNotNull(SpeciationMetric.valueOf("GOAL_JACCARD"));
        assertNotNull(SpeciationMetric.valueOf("METHOD_CALL_JACCARD"));
        assertNotNull(SpeciationMetric.valueOf("HYBRID"));
    }

    @Test
    void runtimeVariablesExist() {
        assertNotNull(RuntimeVariable.valueOf("LLM_Semantic_Mutations"));
        assertNotNull(RuntimeVariable.valueOf("LLM_Semantic_Mutation_Fallbacks"));
        assertNotNull(RuntimeVariable.valueOf("LLM_Semantic_Crossovers"));
        assertNotNull(RuntimeVariable.valueOf("LLM_Semantic_Crossover_Fallbacks"));
        assertNotNull(RuntimeVariable.valueOf("Species_Count_Timeline"));
        assertNotNull(RuntimeVariable.valueOf("Species_Largest_Share_Timeline"));
    }
}
