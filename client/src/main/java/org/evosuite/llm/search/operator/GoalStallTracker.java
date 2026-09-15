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
 * search run). A goal that disappears from the current-goals collection
 * (covered, or the run moved past it) is forgotten so a later, unrelated goal
 * cannot inherit a stale count.
 */
final class GoalStallTracker {

    private final Map<TestFitnessFunction, Double> lastDistance = new HashMap<>();
    private final Map<TestFitnessFunction, Integer> stallCount = new HashMap<>();

    /** Drops tracked state for every goal not present in {@code currentGoals}. */
    void forgetGoalsNotIn(Collection<TestFitnessFunction> currentGoals) {
        Set<TestFitnessFunction> keep = currentGoals instanceof Set
                ? (Set<TestFitnessFunction>) currentGoals
                : new HashSet<>(currentGoals);
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
