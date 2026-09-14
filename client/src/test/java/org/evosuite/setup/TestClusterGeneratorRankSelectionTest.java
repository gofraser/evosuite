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
package org.evosuite.setup;

import com.examples.with.different.packagename.GeneratorRankSelectionFixture;
import com.examples.with.different.packagename.GeneratorRankSelectionFixture.Target;
import org.evosuite.Properties;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code TestCluster#selectWeightedGenerator} used to pick a generator by roulette over a raw,
 * multiplicative {@code scoreGenerator} score. Because that score's factors stack, generators
 * that were only slightly harder to satisfy than the easiest one could end up an order of
 * magnitude less likely to be picked, starving the search of any test that needed them. These
 * tests check the replacement rank-based selection instead: with {@link
 * Properties#GENERATOR_RANK_BIAS} at {@code 1.0} selection is uniform, and with the default bias
 * every one of several equally-scored generators still gets picked often enough not to be
 * effectively unreachable.
 */
public class TestClusterGeneratorRankSelectionTest {

    private static final int GENERATOR_COUNT = 10;
    private static final int DRAWS = 50_000;

    private double originalBias;

    @AfterEach
    public void tearDown() {
        Properties.GENERATOR_RANK_BIAS = originalBias;
        TestCluster.reset();
    }

    private TestCluster clusterWithEquallyScoredGenerators() throws Exception {
        originalBias = Properties.GENERATOR_RANK_BIAS;
        TestCluster.reset();
        TestCluster cluster = TestCluster.getInstance();
        GenericClass<?> targetType = GenericClassFactory.get(Target.class);

        for (int i = 0; i < GENERATOR_COUNT; i++) {
            Method m = GeneratorRankSelectionFixture.class.getMethod("create" + i);
            cluster.addGenerator(targetType,
                    new GenericMethod(m, GeneratorRankSelectionFixture.class));
        }

        Assertions.assertTrue(cluster.hasGenerator(targetType), "fixture not registered");
        return cluster;
    }

    @Test
    public void biasOneIsUniform() throws Exception {
        TestCluster cluster = clusterWithEquallyScoredGenerators();
        Properties.GENERATOR_RANK_BIAS = 1.0;
        Randomness.setSeed(1234L);

        Map<String, Integer> counts = draw(cluster, DRAWS);

        Assertions.assertEquals(GENERATOR_COUNT, counts.size(),
                "every generator should be reachable");
        double expected = DRAWS / (double) GENERATOR_COUNT;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            Assertions.assertTrue(Math.abs(e.getValue() - expected) < expected * 0.15,
                    "generator " + e.getKey() + " count " + e.getValue()
                            + " should be close to the uniform expectation " + expected);
        }
    }

    @Test
    public void defaultBiasStillReachesEveryGenerator() throws Exception {
        TestCluster cluster = clusterWithEquallyScoredGenerators();
        Properties.GENERATOR_RANK_BIAS = 1.7;
        Randomness.setSeed(1234L);

        Map<String, Integer> counts = draw(cluster, DRAWS);

        // No candidate is starved: all ten equally-scored generators still get selected ...
        Assertions.assertEquals(GENERATOR_COUNT, counts.size(),
                "every generator should still be reachable under the default bias, got " + counts);

        // ... each at a rate no worse than 1 / (bias * N) of the draws, i.e. well above what a
        // uniform choice over a much larger effective pool would give, and nowhere near the
        // near-zero probabilities the old multiplicative-score roulette could produce for a
        // generator with a few extra object parameters.
        double floor = DRAWS / (Properties.GENERATOR_RANK_BIAS * GENERATOR_COUNT) * 0.5;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            Assertions.assertTrue(e.getValue() >= floor,
                    "generator " + e.getKey() + " selected only " + e.getValue()
                            + " times out of " + DRAWS + ", below floor " + floor);
        }
    }

    private Map<String, Integer> draw(TestCluster cluster, int n) throws Exception {
        GenericClass<?> targetType = GenericClassFactory.get(Target.class);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < n; i++) {
            GenericAccessibleObject<?> generator = cluster.getRandomGenerator(
                    targetType, Collections.emptySet(), null, 0, null, 0);
            Assertions.assertNotNull(generator);
            counts.merge(generator.toString(), 1, Integer::sum);
        }
        return counts;
    }
}
