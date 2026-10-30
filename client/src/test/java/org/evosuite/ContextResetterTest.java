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
package org.evosuite;

import org.evosuite.graphs.GraphPool;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.symbolic.dse.DSEStatistics;
import org.evosuite.symbolic.dse.algorithm.strategies.implementations.caching.CounterExampleCache;
import org.evosuite.symbolic.expr.Comparator;
import org.evosuite.symbolic.expr.Constraint;
import org.evosuite.symbolic.expr.bv.IntegerConstant;
import org.evosuite.symbolic.expr.constraint.IntegerConstraint;
import org.evosuite.symbolic.solver.SolverCache;
import org.evosuite.symbolic.solver.SolverResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ContextResetterTest {

    @Test
    public void resetInstallsAFreshLoaderAndEmptiesThePools() {
        TestGenerationContext context = TestGenerationContext.getInstance();
        InstrumentingClassLoader before = context.getClassLoaderForSUT();
        GraphPool oldPool = GraphPool.getInstance(before);

        ContextResetter.reset();

        InstrumentingClassLoader after = context.getClassLoaderForSUT();
        assertNotSame(before, after);
        assertSame(after, TestGenerationContext.getClassLoader());
        assertNotSame(oldPool, GraphPool.getInstance(after));
    }

    @Test
    public void clearLeavesTheTargetUnanalysedWhereResetAnalysesIt() {
        String oldTarget = Properties.TARGET_CLASS;
        try {
            Properties.TARGET_CLASS = ContextResetterTest.class.getName();
            ContextResetter.clear();
            assertNull(DependencyAnalysis.getCallGraph(Properties.TARGET_CLASS));

            ContextResetter.reset();
            assertNotNull(DependencyAnalysis.getCallGraph(Properties.TARGET_CLASS));
        } finally {
            Properties.TARGET_CLASS = oldTarget;
            ContextResetter.reset();
        }
    }

    private static Set<Constraint<?>> query() {
        Set<Constraint<?>> query = new LinkedHashSet<>();
        query.add(new IntegerConstraint(new IntegerConstant(1), Comparator.EQ, new IntegerConstant(2)));
        return query;
    }

    private static long queryCacheCalls(DSEStatistics statistics) throws Exception {
        Field field = DSEStatistics.class.getDeclaredField("queryCacheCalls");
        field.setAccessible(true);
        return field.getLong(statistics);
    }

    @Test
    public void resetEmptiesTheSolverCache() {
        SolverCache cache = SolverCache.getInstance();
        Set<Constraint<?>> constraints = query();
        cache.saveSolverResult(constraints, SolverResult.newUNSAT());
        assertTrue(cache.hasCachedResult(constraints));

        ContextResetter.reset();

        assertFalse(cache.hasCachedResult(constraints));
        assertEquals(0, cache.getNumberOfUNSATs());
        assertEquals(0, cache.getNumberOfSATs());
    }

    @Test
    public void counterExampleCacheReportsIntoTheStatisticsOfTheCurrentRun() throws Exception {
        CounterExampleCache cache = new CounterExampleCache();
        DSEStatistics before = DSEStatistics.getInstance();
        cache.checkCache(query(), Collections.emptyMap());
        assertEquals(1, queryCacheCalls(before));

        ContextResetter.reset();

        DSEStatistics after = DSEStatistics.getInstance();
        assertNotSame(before, after);
        cache.checkCache(query(), Collections.emptyMap());
        assertEquals(1, queryCacheCalls(before));
        assertEquals(1, queryCacheCalls(after));
    }
}
