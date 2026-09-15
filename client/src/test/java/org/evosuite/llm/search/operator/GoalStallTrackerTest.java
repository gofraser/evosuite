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
import java.util.HashMap;
import java.util.Map;

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

    // --- stallCountsForGeneration: batches repeated within-generation calls ------------------------------------------------------------

    @Test
    void repeatedCallsWithinOneGenerationCountAsOneObservation() {
        GoalStallTracker tracker = new GoalStallTracker();
        TestFitnessFunction g = goal("g");
        Map<TestFitnessFunction, Double> kept = Collections.singletonMap(g, 0.5);

        tracker.stallCountsForGeneration(1, kept);
        tracker.stallCountsForGeneration(1, kept);
        Map<TestFitnessFunction, Integer> thirdCallSameGeneration = tracker.stallCountsForGeneration(1, kept);

        assertEquals(0, thirdCallSameGeneration.get(g),
                "3 calls for the same generation must not count as 3 consecutive observations");
    }

    @Test
    void distinctGenerationsEachAdvanceTheCount() {
        GoalStallTracker tracker = new GoalStallTracker();
        TestFitnessFunction g = goal("g");
        Map<TestFitnessFunction, Double> kept = Collections.singletonMap(g, 0.5);

        assertEquals(0, tracker.stallCountsForGeneration(1, kept).get(g).intValue());
        assertEquals(1, tracker.stallCountsForGeneration(2, kept).get(g).intValue());
        assertEquals(2, tracker.stallCountsForGeneration(3, kept).get(g).intValue());
    }

    @Test
    void generationLowerThanTheLastOneSeenIsTreatedAsNew() {
        GoalStallTracker tracker = new GoalStallTracker();
        TestFitnessFunction g = goal("g");
        Map<TestFitnessFunction, Double> kept = Collections.singletonMap(g, 0.5);

        tracker.stallCountsForGeneration(5, kept);
        Map<TestFitnessFunction, Integer> afterLowerGeneration = tracker.stallCountsForGeneration(2, kept);

        assertEquals(1, afterLowerGeneration.get(g).intValue(),
                "a generation value below the last one seen is still a new generation, so it advances the count");
    }

    @Test
    void repeatCallAtTheSameGenerationDoesNotChangeWhatALaterGenerationSees() {
        GoalStallTracker tracker = new GoalStallTracker();
        TestFitnessFunction g = goal("g");
        Map<TestFitnessFunction, Double> kept = Collections.singletonMap(g, 0.5);

        tracker.stallCountsForGeneration(1, kept);
        tracker.stallCountsForGeneration(2, kept);
        Map<TestFitnessFunction, Integer> afterThree = tracker.stallCountsForGeneration(3, kept);
        Map<TestFitnessFunction, Integer> repeatedThree = tracker.stallCountsForGeneration(3, kept);
        assertEquals(afterThree.get(g), repeatedThree.get(g),
                "a repeat call at the same generation must reuse, not advance, the recorded count");
        Map<TestFitnessFunction, Integer> afterFour = tracker.stallCountsForGeneration(4, kept);

        GoalStallTracker withoutRepeat = new GoalStallTracker();
        withoutRepeat.stallCountsForGeneration(1, kept);
        withoutRepeat.stallCountsForGeneration(2, kept);
        withoutRepeat.stallCountsForGeneration(3, kept);
        Map<TestFitnessFunction, Integer> referenceAfterFour = withoutRepeat.stallCountsForGeneration(4, kept);

        assertEquals(referenceAfterFour.get(g), afterFour.get(g),
                "the redundant repeat call at generation 3 must not change what generation 4 later observes");
    }

    @Test
    void goalAbsentFromTheFirstCallsKeptSetIsNotStalledOnReuse() {
        GoalStallTracker tracker = new GoalStallTracker();
        TestFitnessFunction g = goal("g");
        TestFitnessFunction h = goal("h");
        tracker.stallCountsForGeneration(1, Collections.singletonMap(g, 0.5));

        Map<TestFitnessFunction, Double> keptBoth = new HashMap<>();
        keptBoth.put(g, 0.5);
        keptBoth.put(h, 0.3);
        Map<TestFitnessFunction, Integer> reused = tracker.stallCountsForGeneration(1, keptBoth);

        assertEquals(0, reused.getOrDefault(h, 0),
                "a goal kept this call but absent from the first call's kept set must count as not stalled");
    }
}
