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

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Counts, per goal, how many consecutive {@link OperatorTargetSelector#select}
 * calls have observed the same best distance for that goal.
 *
 * <p>State is scoped to one {@link OperatorTargetSelector} instance (one
 * search run). A goal is forgotten as soon as it is not one of the goals kept
 * for this generation (covered, unreached, or the run moved past it) so that
 * later, unrelated generation in which it reappears cannot inherit a stale
 * count: the count reflects only strictly consecutive observations of the
 * same distance, never ones separated by a generation where the goal was not
 * tracked.
 */
final class GoalStallTracker {

    private final Map<TestFitnessFunction, Double> lastDistance = new HashMap<>();
    private final Map<TestFitnessFunction, Integer> stallCount = new HashMap<>();

    /**
     * Drops tracked state for every goal not present in {@code keptGoals}.
     * Must be called with the exact set of goals that will (or may) be passed
     * to {@link #record} this generation, so that a goal dropped from that set
     * for even one generation starts its stall count over from zero.
     */
    void forgetGoalsNotIn(Collection<TestFitnessFunction> keptGoals) {
        Set<TestFitnessFunction> keep = keptGoals instanceof Set
                ? (Set<TestFitnessFunction>) keptGoals
                : new HashSet<>(keptGoals);
        lastDistance.keySet().retainAll(keep);
        stallCount.keySet().retainAll(keep);
    }

    /**
     * Records this generation's best distance for {@code goal} and returns the
     * updated stall count: 0 if this is the first observation or the distance
     * changed since the last observation, otherwise the previous count plus one.
     */
    int record(TestFitnessFunction goal, double distance) {
        Double previous = lastDistance.put(goal, distance);
        int updated = (previous != null && previous.doubleValue() == distance)
                ? stallCount.getOrDefault(goal, 0) + 1
                : 0;
        stallCount.put(goal, updated);
        return updated;
    }
}
