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
package org.evosuite.llm.prompt;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterGrowthDeltaTest {

    @Test
    void deltaBudgetIsAQuarterOfTheSummaryBudgetWithinFloorAndCeiling() {
        assertEquals(4000, ClusterGrowthDelta.resolveDeltaBudget(0));
        assertEquals(4000, ClusterGrowthDelta.resolveDeltaBudget(-1));
        assertEquals(25000, ClusterGrowthDelta.resolveDeltaBudget(100000));
        assertEquals(1000, ClusterGrowthDelta.resolveDeltaBudget(2000));
        // The floor must not push the delta past a deliberately small budget.
        assertEquals(800, ClusterGrowthDelta.resolveDeltaBudget(800));
    }

    @Test
    void namesThatFitAreJoinedWithoutATruncationMarker() {
        String joined = ClusterGrowthDelta.joinNamesWithinBudget(
                Arrays.asList("a.A", "b.B", "c.C"), 100);
        assertEquals("a.A, b.B, c.C", joined);
    }

    @Test
    void namesBeyondTheBudgetAreDroppedAndCounted() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            names.add(String.format(Locale.ROOT, "com.example.pkg%02d.SomeDependency%02d", i, i));
        }
        int budget = 200;
        String joined = ClusterGrowthDelta.joinNamesWithinBudget(names, budget);
        assertTrue(joined.length() <= budget, "fallback must respect the budget: " + joined.length());
        assertTrue(joined.startsWith(names.get(0)));
        int shown = joined.split(", ").length;
        assertTrue(shown > 1 && shown < names.size());
        assertTrue(joined.endsWith(String.format(Locale.ROOT, ClusterGrowthDelta.TRUNCATION_FORMAT,
                names.size() - shown)), joined);
    }

    @Test
    void atLeastOneNameSurvivesAnUnreasonablySmallBudget() {
        String joined = ClusterGrowthDelta.joinNamesWithinBudget(
                Arrays.asList("com.example.First", "com.example.Second"), 5);
        assertTrue(joined.startsWith("com.example.First"));
        assertFalse(joined.contains("Second"));
        assertTrue(joined.endsWith(String.format(Locale.ROOT, ClusterGrowthDelta.TRUNCATION_FORMAT, 1)));
    }
}
