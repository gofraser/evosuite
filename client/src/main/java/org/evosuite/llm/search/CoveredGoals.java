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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Counting the goals an injected candidate newly covers. */
public final class CoveredGoals {

    private static final Logger logger = LoggerFactory.getLogger(CoveredGoals.class);

    private CoveredGoals() {
    }

    /**
     * Removes from {@code remaining} the goals {@code candidate} covers and returns them. A goal
     * that cannot be checked is left in {@code remaining}.
     *
     * @param remaining the goals not yet covered; modified
     * @param candidate an evaluated candidate
     * @return the goals that were removed, in iteration order
     */
    public static List<TestFitnessFunction> removeCovered(Set<TestFitnessFunction> remaining,
                                                          TestChromosome candidate) {
        if (candidate == null || remaining == null || remaining.isEmpty()) {
            return Collections.emptyList();
        }
        List<TestFitnessFunction> covered = new ArrayList<>();
        Iterator<TestFitnessFunction> it = remaining.iterator();
        while (it.hasNext()) {
            TestFitnessFunction goal = it.next();
            try {
                if (goal != null && goal.isCovered(candidate)) {
                    covered.add(goal);
                    it.remove();
                }
            } catch (RuntimeException e) {
                logger.debug("Could not check goal {} against an injected test: {}", goal, e.getMessage());
            }
        }
        return covered;
    }
}
