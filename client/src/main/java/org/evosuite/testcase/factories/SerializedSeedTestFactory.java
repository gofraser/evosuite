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
package org.evosuite.testcase.factories;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testsuite.TestSuiteSerialization;
import org.evosuite.utils.LoggingUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts the search from tests serialized by an earlier run, eg the tests continuous test generation
 * kept for this class, and then creates tests with another factory.
 *
 * <p>Each seeded test is handed out once, without assertions, as the initial population is built; after that
 * the search keeps whatever is worth keeping. Without seeds this factory behaves exactly like the one it wraps.
 * </p>
 */
public class SerializedSeedTestFactory implements ChromosomeFactory<TestChromosome> {

    private static final long serialVersionUID = 3071565322914651137L;

    private final ChromosomeFactory<TestChromosome> delegate;

    private final List<TestChromosome> seeds;

    /**
     * Create a factory that hands out the given seeds before delegating.
     *
     * @param delegate the factory for tests once the seeds are used up
     * @param seeds    the seeded tests
     */
    public SerializedSeedTestFactory(ChromosomeFactory<TestChromosome> delegate, List<TestChromosome> seeds) {
        this.delegate = delegate;
        this.seeds = new ArrayList<>(seeds);
    }

    /**
     * Wrap a factory so that it hands out the tests in {@link Properties#CTG_SEEDS_FILE_IN} first, if
     * that file is set and has tests.
     *
     * @param factory the factory to wrap
     * @return the wrapping factory, or the given one if there are no seeds
     */
    public static ChromosomeFactory<TestChromosome> withCtgSeeds(ChromosomeFactory<TestChromosome> factory) {
        List<TestChromosome> seeds = loadCtgSeeds();
        return seeds.isEmpty() ? factory : new SerializedSeedTestFactory(factory, seeds);
    }

    /**
     * Load the tests in {@link Properties#CTG_SEEDS_FILE_IN}.
     *
     * @return the tests; empty if the property or the file is missing
     */
    public static List<TestChromosome> loadCtgSeeds() {
        if (Properties.CTG_SEEDS_FILE_IN == null || !new File(Properties.CTG_SEEDS_FILE_IN).isFile()) {
            return new ArrayList<>();
        }
        List<TestChromosome> seeds = TestSuiteSerialization.loadTests(Properties.CTG_SEEDS_FILE_IN);
        LoggingUtils.getEvoLogger().info("* Loaded {} tests from {}", seeds.size(), Properties.CTG_SEEDS_FILE_IN);
        return seeds;
    }

    /**
     * Hand out one seed, without its assertions, which are not used during the search.
     *
     * @param seeds the seeds left, from which it is removed
     * @return the seed
     */
    public static TestChromosome takeSeed(List<TestChromosome> seeds) {
        TestChromosome test = seeds.remove(seeds.size() - 1); //pull out one element, 'last' just for efficiency
        test.getTestCase().removeAssertions();
        return test;
    }

    /**
     * The number of seeds not handed out yet.
     *
     * @return the number of seeds left
     */
    public int remainingSeeds() {
        return seeds.size();
    }

    @Override
    public TestChromosome getChromosome() {
        if (!seeds.isEmpty()) {
            return takeSeed(seeds);
        }
        return delegate.getChromosome();
    }
}
