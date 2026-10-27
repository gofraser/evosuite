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
package org.evosuite.llm.search;

import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoveredGoalsTest {

    @Test
    void removesOnlyTheCoveredGoalsAndKeepsTheOrder() {
        TestChromosome candidate = new TestChromosome();
        TestFitnessFunction first = goal(candidate, true);
        TestFitnessFunction second = goal(candidate, false);
        TestFitnessFunction third = goal(candidate, true);
        Set<TestFitnessFunction> remaining = new LinkedHashSet<>(Arrays.asList(first, second, third));

        List<TestFitnessFunction> covered = CoveredGoals.removeCovered(remaining, candidate);

        assertEquals(Arrays.asList(first, third), covered);
        assertEquals(1, remaining.size());
        assertTrue(remaining.contains(second));
    }

    @Test
    void aGoalThatCannotBeCheckedStaysUncoveredAndTheRestAreStillCounted() {
        TestChromosome candidate = new TestChromosome();
        TestFitnessFunction failing = mock(TestFitnessFunction.class);
        when(failing.isCovered(candidate)).thenThrow(new IllegalStateException("no result"));
        TestFitnessFunction covered = goal(candidate, true);
        Set<TestFitnessFunction> remaining = new LinkedHashSet<>(Arrays.asList(failing, covered));

        List<TestFitnessFunction> removed = CoveredGoals.removeCovered(remaining, candidate);

        assertEquals(Arrays.asList(covered), removed);
        assertTrue(remaining.contains(failing));
    }

    private static TestFitnessFunction goal(TestChromosome candidate, boolean covers) {
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.isCovered(candidate)).thenReturn(covers);
        return goal;
    }
}
