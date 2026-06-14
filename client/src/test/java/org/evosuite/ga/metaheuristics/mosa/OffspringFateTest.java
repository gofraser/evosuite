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
package org.evosuite.ga.metaheuristics.mosa;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the shared-goals + new-coverage fate classification of
 * {@link OffspringFate}.
 */
class OffspringFateTest {

    private static Map<String, Double> map(Object... kv) {
        Map<String, Double> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Double) kv[i + 1]);
        }
        return m;
    }

    @Test
    void newCoverageIsBetterEvenWhenSharedSumIsEqual() {
        // Offspring covers g2 (parent had 0.5 there); shared sums otherwise equal.
        Map<String, Double> offspring = map("g1", 0.4, "g2", 0.0);
        Map<String, Double> parent = map("g1", 0.4, "g2", 0.5);
        assertEquals(OffspringFate.BETTER, OffspringFate.classify(offspring, parent));
    }

    @Test
    void newCoverageOnGoalAbsentInParentIsBetter() {
        // Parent was never evaluated on g2 (e.g., goal activated later).
        Map<String, Double> offspring = map("g1", 0.4, "g2", 0.0);
        Map<String, Double> parent = map("g1", 0.4);
        assertEquals(OffspringFate.BETTER, OffspringFate.classify(offspring, parent));
    }

    @Test
    void sharedSumImprovementIsBetter() {
        Map<String, Double> offspring = map("g1", 0.2, "g2", 0.3);
        Map<String, Double> parent = map("g1", 0.4, "g2", 0.3);
        assertEquals(OffspringFate.BETTER, OffspringFate.classify(offspring, parent));
    }

    @Test
    void sharedSumDegradationIsWorse() {
        Map<String, Double> offspring = map("g1", 0.9, "g2", 0.3);
        Map<String, Double> parent = map("g1", 0.4, "g2", 0.3);
        assertEquals(OffspringFate.WORSE, OffspringFate.classify(offspring, parent));
    }

    @Test
    void equalSharedSumIsNeutral() {
        Map<String, Double> offspring = map("g1", 0.4, "g2", 0.3);
        Map<String, Double> parent = map("g1", 0.4, "g2", 0.3);
        assertEquals(OffspringFate.NEUTRAL, OffspringFate.classify(offspring, parent));
    }

    @Test
    void changesWithinEpsAreNeutral() {
        Map<String, Double> offspring = map("g1", 0.4 + OffspringFate.EPS / 2);
        Map<String, Double> parent = map("g1", 0.4);
        assertEquals(OffspringFate.NEUTRAL, OffspringFate.classify(offspring, parent));

        Map<String, Double> offspring2 = map("g1", 0.4 - OffspringFate.EPS / 2);
        assertEquals(OffspringFate.NEUTRAL, OffspringFate.classify(offspring2, parent));
    }

    @Test
    void parentOnlyArchivedEntriesAreIgnored() {
        // Parent carries a stale 0.0 entry for an archived goal the offspring
        // was never evaluated on; this must not bias the comparison.
        Map<String, Double> offspring = map("g1", 0.4);
        Map<String, Double> parent = map("g1", 0.4, "gArchived", 0.0);
        assertEquals(OffspringFate.NEUTRAL, OffspringFate.classify(offspring, parent));
    }

    @Test
    void bothCoveringSharedGoalIsNotNewCoverage() {
        // Offspring keeping the parent's covered goal at 0.0 is neutral, not better.
        Map<String, Double> offspring = map("g1", 0.0, "g2", 0.5);
        Map<String, Double> parent = map("g1", 0.0, "g2", 0.5);
        assertEquals(OffspringFate.NEUTRAL, OffspringFate.classify(offspring, parent));
    }
}
