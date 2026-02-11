/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.symbolic.solver;

import org.evosuite.symbolic.expr.Constraint;

import java.util.Collection;
import java.util.HashMap;

/**
 * A cache for solver results.
 *
 * @author ilebrero
 */
public final class SolverCache {

    private static final SolverCache instance = new SolverCache();
    private static final String CONTRAINT_NOT_CACHED_EXCEPTION_MESSAGE = "The constraint is not cached!";
    private static final String SOLVER_RESULT_CANNOT_BE_NULL_EXCEPTION_MESSAGE =
            "Unable to save solver result as its null.";

    private int numberOfHits = 0;
    private int numberOfAccesses = 0;
    private int cachedSatResultCount = 0;
    private int cachedUnsatResultCount = 0;
    private boolean validCachedSolution = false;

    private final HashMap<Collection<Constraint<?>>, SolverResult> cachedSolverResults = new HashMap<>();
    private SolverResult cachedSolution = null;

    /**
     * Returns the number of cached UNSAT results.
     *
     * @return the number of UNSAT results
     */
    public int getNumberOfUNSATs() {
        return cachedUnsatResultCount;
    }

    /**
     * Returns the number of cached SAT results.
     *
     * @return the number of SAT results
     */
    public int getNumberOfSATs() {
        return cachedSatResultCount;
    }

    private SolverCache() {
        /* empty constructor */
    }

    /**
     * Returns the singleton instance of the solver cache.
     *
     * @return the solver cache instance
     */
    public static SolverCache getInstance() {
        return instance;
    }

    private void addUNSAT(Collection<Constraint<?>> unsatConstraints, SolverResult unsatResult) {
        cachedSolverResults.put(unsatConstraints, unsatResult);
        cachedUnsatResultCount++;
    }

    private void addSAT(Collection<Constraint<?>> satConstraints, SolverResult satResult) {
        cachedSolverResults.put(satConstraints, satResult);
        cachedSatResultCount++;
    }

    /**
     * Checks if a result for the given constraints is cached.
     *
     * @param constraints the collection of constraints
     * @return true if a result is cached, false otherwise
     */
    public boolean hasCachedResult(Collection<Constraint<?>> constraints) {
        numberOfAccesses++;

        if (this.cachedSolverResults.containsKey(constraints)) {
            validCachedSolution = true;
            cachedSolution = this.cachedSolverResults.get(constraints);
            numberOfHits++;
            return true;
        } else {
            validCachedSolution = false;
            return false;
        }
    }

    /**
     * Returns the hit rate of the cache.
     *
     * @return the hit rate
     */
    public double getHitRate() {
        return (double) this.numberOfHits / (double) this.numberOfAccesses;
    }

    /**
     * Returns the cached result.
     * If not in cache, throws an {@link IllegalArgumentException}.
     *
     * @return the cached solver result
     */
    public SolverResult getCachedResult() {

        if (validCachedSolution == false) {
            throw new IllegalArgumentException(CONTRAINT_NOT_CACHED_EXCEPTION_MESSAGE);
        }

        validCachedSolution = false;
        return this.cachedSolution;
    }

    /**
     * Saves a solver result to the cache.
     *
     * @param constraints  the collection of constraints
     * @param solverResult the solver result to save
     */
    public void saveSolverResult(Collection<Constraint<?>> constraints, SolverResult solverResult) {
        if (solverResult == null) {
            throw new IllegalArgumentException(SOLVER_RESULT_CANNOT_BE_NULL_EXCEPTION_MESSAGE);
        }

        if (solverResult.isUNSAT()) {
            addUNSAT(constraints, solverResult);
        } else {
            addSAT(constraints, solverResult);
        }
    }

}
