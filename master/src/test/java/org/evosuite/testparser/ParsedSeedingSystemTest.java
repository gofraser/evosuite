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
package org.evosuite.testparser;

import com.examples.with.different.packagename.testcarver.DifficultClassWithoutCarving;
import com.examples.with.different.packagename.testcarver.DifficultClassWithoutCarvingTest;
import org.evosuite.EvoSuite;
import org.evosuite.Properties;
import org.evosuite.Properties.TestFactory;
import org.evosuite.SystemTestBase;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end system test for PARSED_JUNIT seeding.
 *
 * Uses {@link DifficultClassWithoutCarving} as the target class. Its
 * {@code testMe(dep)} method returns true only when {@code dep.isTen()}
 * is true, which requires exactly 10 calls to {@code dep.inc()} — a
 * sequence that random search is very unlikely to discover in a short
 * budget. The seed test source in {@link DifficultClassWithoutCarvingTest}
 * provides exactly this sequence.
 */
public class ParsedSeedingSystemTest extends SystemTestBase {

    private static final String defaultSelectedJUnit = Properties.SELECTED_JUNIT;
    private static final TestFactory defaultTestFactory = Properties.TEST_FACTORY;
    private static final int defaultSeedMutations = Properties.SEED_MUTATIONS;
    private static final double defaultSeedClone = Properties.SEED_CLONE;
    private static final String defaultSeedTestSourceDir = Properties.SEED_TEST_SOURCE_DIR;

    @AfterEach
    public void reset() {
        Properties.SELECTED_JUNIT = defaultSelectedJUnit;
        Properties.TEST_FACTORY = defaultTestFactory;
        Properties.SEED_MUTATIONS = defaultSeedMutations;
        Properties.SEED_CLONE = defaultSeedClone;
        Properties.SEED_TEST_SOURCE_DIR = defaultSeedTestSourceDir;
    }

    @Test
    public void testParsedSeedingWithDifficultClass() {
        EvoSuite evosuite = new EvoSuite();

        String targetClass = DifficultClassWithoutCarving.class.getCanonicalName();
        Properties.TARGET_CLASS = targetClass;
        Properties.TEST_FACTORY = TestFactory.PARSED_JUNIT;
        Properties.SELECTED_JUNIT = DifficultClassWithoutCarvingTest.class.getCanonicalName();
        Properties.SEED_TEST_SOURCE_DIR = "src/test/java";
        Properties.SEED_CLONE = 1.0;
        Properties.SEED_MUTATIONS = 1;

        String[] command = new String[]{"-generateSuite", "-class", targetClass};

        Object result = evosuite.parseCommandLine(command);
        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        System.out.println("EvolvedTestSuite:\n" + best);

        assertEquals(1d, best.getCoverage(), 0.001, "Expected optimal coverage");
    }
}
