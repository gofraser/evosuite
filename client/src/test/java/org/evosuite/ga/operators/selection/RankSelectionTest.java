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
package org.evosuite.ga.operators.selection;

import org.evosuite.Properties;
import org.evosuite.utils.Randomness;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RankSelection#getIndex(int, double)}, the rank-selection index helper shared
 * between GA individual selection ({@link RankSelection#getIdx}) and generator selection in
 * {@code TestCluster#selectWeightedGenerator}.
 */
public class RankSelectionTest {

    private static final int SIZE = 10;
    private static final int DRAWS = 400_000;

    @Test
    public void biasOneIsUniform() {
        Randomness.setSeed(42L);

        int[] counts = draw(SIZE, 1.0, DRAWS);

        double expected = DRAWS / (double) SIZE;
        for (int i = 0; i < SIZE; i++) {
            assertTrue(Math.abs(counts[i] - expected) < expected * 0.1,
                    "rank " + i + " count " + counts[i] + " should be close to the uniform "
                            + "expectation " + expected);
        }
    }

    @Test
    public void higherRanksArePreferredButNoneAreStarved() {
        double bias = 1.7;
        Randomness.setSeed(42L);

        int[] counts = draw(SIZE, bias, DRAWS);

        // The classic rank-selection property that the "bias" parameter actually controls:
        // the best rank (index 0) is selected about `bias` times as often as the population
        // average (P(best) = bias / size, P(worst) = (2 - bias) / size in the textbook linear
        // ranking formulation this GENITOR-style approximation targets).
        double average = DRAWS / (double) SIZE;
        double expectedBest = average * bias;
        assertTrue(Math.abs(counts[0] - expectedBest) < expectedBest * 0.15,
                "rank 0 count " + counts[0] + " should be close to " + expectedBest);

        // Selection frequency decreases monotonically from best to worst rank ...
        for (int i = 0; i < SIZE - 1; i++) {
            assertTrue(counts[i] >= counts[i + 1],
                    "rank " + i + " (count " + counts[i] + ") should not be rarer than rank "
                            + (i + 1) + " (count " + counts[i + 1] + ")");
        }
        // ... but no rank is starved: the worst of 10 ranks is still reached often, and the
        // best-to-worst ratio stays bounded -- nowhere near the one or two orders of magnitude
        // that the raw multiplicative generator score could produce.
        assertTrue(counts[SIZE - 1] > 0, "the worst rank must still be reachable");
        double ratio = counts[0] / (double) counts[SIZE - 1];
        assertTrue(ratio < 8.0, "best-to-worst ratio " + ratio + " should stay bounded");
    }

    @Test
    public void singleCandidateAlwaysWins() {
        assertEquals(0, RankSelection.getIndex(1, 1.7));
        assertEquals(0, RankSelection.getIndex(1, 1.0));
        assertEquals(0, RankSelection.getIndex(0, 1.7));
    }

    @Test
    public void getIdxDelegatesToConfiguredBias() {
        double original = Properties.RANK_BIAS;
        try {
            Properties.RANK_BIAS = 1.0;
            Randomness.setSeed(7L);
            List<Object> ten = Collections.nCopies(SIZE, new Object());

            for (int i = 0; i < 1000; i++) {
                int idx = RankSelection.getIdx(ten);
                assertTrue(idx >= 0 && idx < SIZE, "index " + idx + " out of bounds");
            }
        } finally {
            Properties.RANK_BIAS = original;
        }
    }

    private static int[] draw(int size, double bias, int draws) {
        int[] counts = new int[size];
        for (int i = 0; i < draws; i++) {
            counts[RankSelection.getIndex(size, bias)]++;
        }
        return counts;
    }
}
