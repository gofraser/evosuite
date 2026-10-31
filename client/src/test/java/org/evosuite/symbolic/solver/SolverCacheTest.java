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
package org.evosuite.symbolic.solver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SolverCacheTest {

    @BeforeEach
    @AfterEach
    public void clearCache() {
        SolverCache.getInstance().clear();
    }

    @Test
    public void testHitRateOfUnusedCacheIsZero() {
        assertEquals(0.0, SolverCache.getInstance().getHitRate());
    }

    @Test
    public void testHitRateOfCacheWithOnlyMissesIsZero() {
        SolverCache cache = SolverCache.getInstance();
        cache.hasCachedResult(Collections.emptyList());

        assertEquals(0.0, cache.getHitRate());
    }

    @Test
    public void testHitRateCountsHitsPerAccess() {
        SolverCache cache = SolverCache.getInstance();
        cache.saveSolverResult(Collections.emptyList(), SolverResult.newUNSAT());
        cache.hasCachedResult(Collections.emptyList());
        cache.hasCachedResult(Collections.singletonList(null));

        assertEquals(0.5, cache.getHitRate());
    }
}
