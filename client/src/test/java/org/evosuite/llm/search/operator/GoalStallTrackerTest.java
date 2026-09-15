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
package org.evosuite.llm.search.operator;

import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalStallTrackerTest {

    private static TestFitnessFunction goal(String name) {
        return new OperatorTargetTest.StubGoal(name);
    }

    /**
     * Simulates how {@link OperatorTargetSelector} drives the tracker: each
     * generation, {@code forgetGoalsNotIn} is called with exactly the set of
     * goals kept this generation, then {@code record} is called for each of
     * them.
     */
    @Test
    void stallCountResetsWhenAGoalLeavesTheKeptSetEvenBriefly() {
        GoalStallTracker tracker = new GoalStallTracker();
        TestFitnessFunction g = goal("g");
        int stallThreshold = 3;

        recordKept(tracker, g, 0.5);
        recordKept(tracker, g, 0.5);
        // best becomes 1.0 (covered or otherwise no longer kept): goal drops out of the kept set entirely
        tracker.forgetGoalsNotIn(Collections.emptySet());
        int afterDrop = recordKept(tracker, g, 0.5);
        int afterSecond = recordKept(tracker, g, 0.5);

        assertTrue(afterDrop < stallThreshold, "the count right after rejoining must not already be stalled");
        assertTrue(afterSecond < stallThreshold,
                "only 2 consecutive observations since rejoining have happened, not " + stallThreshold);
    }

    @Test
    void fourConsecutiveUnchangedObservationsStall() {
        GoalStallTracker tracker = new GoalStallTracker();
        TestFitnessFunction g = goal("g");
        int stallThreshold = 3;

        recordKept(tracker, g, 0.5);
        recordKept(tracker, g, 0.5);
        recordKept(tracker, g, 0.5);
        int fourth = recordKept(tracker, g, 0.5);

        assertTrue(fourth >= stallThreshold, "4 consecutive unchanged observations must be stalled at threshold 3");
    }

    @Test
    void firstObservationIsNeverStalled() {
        GoalStallTracker tracker = new GoalStallTracker();
        TestFitnessFunction g = goal("g");

        assertEquals(0, recordKept(tracker, g, 0.5));
    }

    private static int recordKept(GoalStallTracker tracker, TestFitnessFunction g, double distance) {
        tracker.forgetGoalsNotIn(Collections.singleton(g));
        return tracker.record(g, distance);
    }
}
