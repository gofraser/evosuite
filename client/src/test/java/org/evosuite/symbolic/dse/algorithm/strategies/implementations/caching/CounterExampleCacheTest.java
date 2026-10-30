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

import org.evosuite.symbolic.dse.DSEStatistics;
import org.evosuite.symbolic.expr.Comparator;
import org.evosuite.symbolic.expr.Constraint;
import org.evosuite.symbolic.expr.bv.IntegerConstant;
import org.evosuite.symbolic.expr.constraint.IntegerConstraint;
import org.evosuite.symbolic.solver.SolverResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins which queries the counter-example cache answers from the cache and which it sends to the solver.
 */
public class CounterExampleCacheTest {

    private final Constraint<?> a = constraint(1, Comparator.EQ, 2);
    private final Constraint<?> b = constraint(3, Comparator.LT, 4);
    private final Constraint<?> c = constraint(5, Comparator.GE, 6);
    private final Constraint<?> d = constraint(7, Comparator.NE, 8);

    private static Constraint<?> constraint(long left, Comparator comparator, long right) {
        return new IntegerConstraint(new IntegerConstant(left), comparator, new IntegerConstant(right));
    }

    private Set<Constraint<?>> set(Constraint<?>... constraints) {
        return new LinkedHashSet<>(Arrays.asList(constraints));
    }

    private static Map<String, Object> model(String name, Object value) {
        Map<String, Object> model = new HashMap<>();
        model.put(name, value);
        return model;
    }

    private static long counter(String field) throws Exception {
        Field f = DSEStatistics.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.getLong(DSEStatistics.getInstance());
    }

    private static void assertMiss(CacheQueryResult result) {
        assertTrue(result.missed());
        assertTrue(!result.hitSat() && !result.hitUnSat());
    }

    @Test
    public void exactSatQueryReturnsTheCachedModel() {
        Map<Set<Constraint<?>>, SolverResult> cache = new LinkedHashMap<>();
        cache.put(set(a), SolverResult.newSAT(model("x", 1)));
        CacheQueryResult result = new CounterExampleCache().checkCache(set(a), cache);
        assertTrue(result.hitSat());
        assertEquals(model("x", 1), result.getSmtSolution());
    }

    @Test
    public void exactUnsatQueryIsAHit() {
        Map<Set<Constraint<?>>, SolverResult> cache = new LinkedHashMap<>();
        cache.put(set(a, b), SolverResult.newUNSAT());
        assertTrue(new CounterExampleCache().checkCache(set(b, a), cache).hitUnSat());
    }

    @Test
    public void supersetOfAnUnsatQueryIsAnUnsatHit() {
        Map<Set<Constraint<?>>, SolverResult> cache = new LinkedHashMap<>();
        cache.put(set(a, b), SolverResult.newUNSAT());
        assertTrue(new CounterExampleCache().checkCache(set(a, b, d), cache).hitUnSat());
    }

    @Test
    public void supersetOfASatQueryIsAMiss() {
        Map<Set<Constraint<?>>, SolverResult> cache = new LinkedHashMap<>();
        cache.put(set(a), SolverResult.newSAT(model("x", 1)));
        assertMiss(new CounterExampleCache().checkCache(set(a, b), cache));
    }

    @Test
    public void firstContainedEntryDecidesWhenSeveralAreSubsetsOfTheQuery() {
        Map<Set<Constraint<?>>, SolverResult> cache = new LinkedHashMap<>();
        cache.put(set(a), SolverResult.newSAT(model("x", 1)));
        cache.put(set(a, b), SolverResult.newUNSAT());
        assertMiss(new CounterExampleCache().checkCache(set(a, b, d), cache));

        Map<Set<Constraint<?>>, SolverResult> reversed = new LinkedHashMap<>();
        reversed.put(set(a, b), SolverResult.newUNSAT());
        reversed.put(set(a), SolverResult.newSAT(model("x", 1)));
        assertTrue(new CounterExampleCache().checkCache(set(a, b, d), reversed).hitUnSat());
    }

    @Test
    public void subsetOfACachedQueryIsAMissWhateverItsResult() {
        Map<Set<Constraint<?>>, SolverResult> sat = new LinkedHashMap<>();
        sat.put(set(a, b, d), SolverResult.newSAT(model("x", 1)));
        assertMiss(new CounterExampleCache().checkCache(set(a), sat));

        Map<Set<Constraint<?>>, SolverResult> unsat = new LinkedHashMap<>();
        unsat.put(set(a, b, d), SolverResult.newUNSAT());
        assertMiss(new CounterExampleCache().checkCache(set(a), unsat));
    }

    @Test
    public void unrelatedAndUnknownQueriesAreMisses() {
        Map<Set<Constraint<?>>, SolverResult> cache = new LinkedHashMap<>();
        cache.put(set(a, b), SolverResult.newSAT(model("x", 1)));
        cache.put(set(d), SolverResult.newUnknown());
        CounterExampleCache strategy = new CounterExampleCache();
        assertMiss(strategy.checkCache(set(c), cache));
        assertMiss(strategy.checkCache(set(d), cache));
        assertMiss(strategy.checkCache(set(), new LinkedHashMap<>()));
    }

    @Test
    public void callAndHitCountersFollowTheHits() throws Exception {
        Map<Set<Constraint<?>>, SolverResult> cache = new LinkedHashMap<>();
        cache.put(set(a), SolverResult.newSAT(model("x", 1)));
        cache.put(set(b, c), SolverResult.newUNSAT());
        CounterExampleCache strategy = new CounterExampleCache();
        long calls = counter("queryCacheCalls");
        long hits = counter("queryCacheHits");

        strategy.checkCache(set(a), cache);          // exact SAT hit
        strategy.checkCache(set(b, c), cache);       // exact UNSAT hit
        strategy.checkCache(set(b, c, d), cache);    // superset of UNSAT: hit
        strategy.checkCache(set(a, d), cache);       // superset of SAT: miss
        strategy.checkCache(set(c), cache);          // subset of cached: miss
        strategy.checkCache(set(d), cache);          // unrelated: miss

        assertEquals(calls + 6, counter("queryCacheCalls"));
        assertEquals(hits + 3, counter("queryCacheHits"));
    }
}
