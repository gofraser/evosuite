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
package org.evosuite.symbolic.dse.algorithm;

import org.evosuite.symbolic.PathCondition;
import org.evosuite.symbolic.dse.DSEStatistics;
import org.evosuite.symbolic.dse.DSETestCase;
import org.evosuite.symbolic.expr.Constraint;
import org.evosuite.symbolic.solver.SolverResult;
import org.evosuite.testcase.TestCase;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks how the exploration algorithm interprets the three outcomes of a solver query.
 */
public class QuerySolutionTest {

    private static class Algorithm extends ExplorationAlgorithm {

        Algorithm() {
            super(false, DSEStatistics.getInstance(),
                    testCase -> new PathCondition(Collections.emptyList()), null);
        }

        @Override
        protected double getTestScore(TestCase newTestCase, boolean hasPathConditionDiverged) {
            return 0;
        }

        @Override
        protected Queue<DSETestCase> createWorkList() {
            return new LinkedList<>();
        }

        boolean cached(Set<Constraint<?>> query) {
            return queryCache.containsKey(query);
        }
    }

    private final Set<Constraint<?>> query = Collections.emptySet();

    @Test
    public void unknownResultYieldsNoSolutionAndIsNotCached() {
        Algorithm algorithm = new Algorithm();

        assertNull(algorithm.getQuerySolution(query, SolverResult.newUnknown()));
        assertFalse(algorithm.cached(query));
    }

    @Test
    public void unsatResultYieldsNoSolutionAndIsCached() {
        Algorithm algorithm = new Algorithm();

        assertNull(algorithm.getQuerySolution(query, SolverResult.newUNSAT()));
        assertTrue(algorithm.cached(query));
    }

    @Test
    public void satResultYieldsItsModelAndIsCached() {
        Algorithm algorithm = new Algorithm();
        Map<String, Object> model = new HashMap<>();
        model.put("x", 3L);

        assertEquals(model, algorithm.getQuerySolution(query, SolverResult.newSAT(model)));
        assertTrue(algorithm.cached(query));
    }
}
