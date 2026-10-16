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
package org.evosuite.ga.archive;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.factories.RandomLengthTestFactory;
import org.evosuite.testcase.factories.SerializedSeedTestFactory;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ArchiveTestChromosomeFactory implements ChromosomeFactory<TestChromosome> {

    private static final long serialVersionUID = -8499807341782893732L;

    private static final Logger logger = LoggerFactory.getLogger(ArchiveTestChromosomeFactory.class);

    private final ChromosomeFactory<TestChromosome> defaultFactory = new RandomLengthTestFactory();

    /**
     * Serialized tests read from disk, eg from previous runs in CTG.
     */
    private List<TestChromosome> seededTests;

    /**
     * Constructor.
     */
    public ArchiveTestChromosomeFactory() {
        //This does happen in CTG
        seededTests = SerializedSeedTestFactory.loadCtgSeeds();
    }

    @Override
    public TestChromosome getChromosome() {

        if (!seededTests.isEmpty()) {
            /*
             Ideally, we should populate the archive directly when EvoSuite starts.
             But might be bit tricky based on current archive implementation (which needs executed tests).
             So, easiest approach is to just return tests here, with no mutation on those.
             However, this is done just once per test, as anyway those will end up
             in archive.
             */
            return SerializedSeedTestFactory.takeSeed(seededTests);
        }

        TestChromosome test = null;
        if (!Archive.getArchiveInstance().isArchiveEmpty()
                && Randomness.nextDouble() < Properties.SEED_CLONE) {
            logger.debug("Creating test based on archive");
            test = new TestChromosome();
            test.setTestCase(Archive.getArchiveInstance().getRandomSolution().getTestCase());
            int mutations = Randomness.nextInt(Properties.SEED_MUTATIONS);
            for (int i = 0; i < mutations; i++) {
                test.mutate();
            }
        } else {
            logger.debug("Creating random test");
            test = defaultFactory.getChromosome();
        }

        return test;
    }

}
