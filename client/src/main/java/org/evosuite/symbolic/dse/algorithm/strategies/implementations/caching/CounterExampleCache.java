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
package org.evosuite.symbolic.dse.algorithm.strategies.implementations.caching;

import org.evosuite.symbolic.PathConditionUtils;
import org.evosuite.symbolic.dse.DSEStatistics;
import org.evosuite.symbolic.expr.Constraint;
import org.evosuite.symbolic.solver.SolverResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

/**
 * Counter-example cache strategy: answers a query from the cache when exactly the same constraint
 * set was solved before, or when the query contains a constraint set that is cached as
 * unsatisfiable (the query x&gt;10 ∧ x&lt;5 ∧ y=0 has no solution because x&gt;10 ∧ x&lt;5 has none).
 * Every other query is a miss and goes to the solver.
 *
 * @author Ignacio Lebrero
 */
public class CounterExampleCache {

    private static final Logger logger = LoggerFactory.getLogger(CounterExampleCache.class);

    /**
     * Looks up the constraint set {@code query} in {@code queryCache} and reports the outcome in a
     * {@link CacheQueryResult}. Records the query in the DSE statistics.
     */
    public CacheQueryResult checkCache(Set<Constraint<?>> query, Map<Set<Constraint<?>>, SolverResult> queryCache) {
        // The statistics instance is replaced on every context reset, so it is read per query.
        final DSEStatistics statisticsLogger = DSEStatistics.getInstance();
        statisticsLogger.reportNewQueryCacheCall();

        // Cache hit of an exact set solution
        if (queryCache.containsKey(query)) {
            SolverResult cachedResult = queryCache.get(query);

            if (cachedResult.isSAT()) {
                statisticsLogger.reportNewQueryCacheHit();
                logger.debug("skipping solving of current query since it is in the query cache");
                return new CacheQueryResult(cachedResult.getModel(), CacheQueryStatus.HIT_SAT);

            } else if (cachedResult.isUNSAT()) {
                statisticsLogger.reportNewQueryCacheHit();
                logger.debug("skipping current query since it is in the query cache and it unsatisfiable");
                return new CacheQueryResult(CacheQueryStatus.HIT_UNSAT);
            }
        }

        // A query containing a cached unsatisfiable set is itself unsatisfiable. Only the first
        // contained entry is consulted.
        if (PathConditionUtils.isConstraintSetSupraSetOf(query, queryCache.keySet())) {
            Set<Constraint<?>> subSetSolution =
                    PathConditionUtils.getConstraintSetSupraSetOf(query, queryCache.keySet());

            if (queryCache.get(subSetSolution).isUNSAT()) {
                statisticsLogger.reportNewQueryCacheHit();
                logger.debug("skipping current query since it is in the query cache and it unsatisfiable");
                return new CacheQueryResult(CacheQueryStatus.HIT_UNSAT);
            }
        }

        return new CacheQueryResult(CacheQueryStatus.MISS);
    }
}
