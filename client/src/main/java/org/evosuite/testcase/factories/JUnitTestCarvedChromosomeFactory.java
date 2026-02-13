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
package org.evosuite.testcase.factories;

import org.evosuite.Properties;
import org.evosuite.coverage.FitnessFunctions;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientNodeLocal;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcarver.extraction.CarvingManager;
import org.evosuite.testcarver.testcase.CarvedTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.evosuite.utils.LoggingUtils;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class JUnitTestCarvedChromosomeFactory implements
        ChromosomeFactory<TestChromosome> {

    private static final long serialVersionUID = -569338946355072318L;

    private static final Logger logger = LoggerFactory.getLogger(JUnitTestCarvedChromosomeFactory.class);

    private final List<TestCase> junitTests = new ArrayList<>();

    private final ChromosomeFactory<TestChromosome> defaultFactory;

    // These two variables will go once the new statistics frontend is finally finished
    private int totalNumberOfTestsCarved = 0;

    private double carvedCoverage = 0.0;

    /**
     * The carved test cases are used only with a certain probability P. So,
     * with probability 1-P the 'default' factory is rather used.
     *
     * @param defaultFactory the default factory.
     * @throws IllegalStateException if Properties are not properly set
     */
    public JUnitTestCarvedChromosomeFactory(
            ChromosomeFactory<TestChromosome> defaultFactory)
            throws IllegalStateException {
        this.defaultFactory = defaultFactory;
        readTestCases();
    }


    private void readTestCases() throws IllegalStateException {
        CarvingManager manager = CarvingManager.getInstance();
        final Class<?> targetClass = Properties.getTargetClassAndDontInitialise();
        List<TestCase> tests = manager.getTestsForClass(targetClass);
        junitTests.addAll(tests);

        junitTests.sort((t1, t2) -> {
            if (t1 instanceof CarvedTestCase && t2 instanceof CarvedTestCase) {
                return ((CarvedTestCase) t1).getName().compareTo(((CarvedTestCase) t2).getName());
            } else if (t1 instanceof CarvedTestCase) {
                return -1;
            } else if (t2 instanceof CarvedTestCase) {
                return 1;
            }
            return Integer.compare(t1.hashCode(), t2.hashCode());
        });

        if (!junitTests.isEmpty()) {
            totalNumberOfTestsCarved = junitTests.size();

            LoggingUtils.getEvoLogger().info("* Using {} carved tests from existing JUnit tests for seeding",
                    junitTests.size());
            if (logger.isDebugEnabled()) {
                for (TestCase test : junitTests) {
                    logger.debug("Carved Test: {}", test.toCode());
                }
            }

            TestSuiteChromosome suite = new TestSuiteChromosome();
            for (TestCase test : junitTests) {
                suite.addTest(test);
            }

            for (Properties.Criterion pc : Properties.CRITERION) {
                TestSuiteFitnessFunction f = FitnessFunctions.getFitnessFunction(pc);
                f.getFitness(suite);
            }
            carvedCoverage = suite.getCoverage();
        }

        ClientNodeLocal client = ClientServices.getInstance().getClientNode();
        if (client != null) {
            client.trackOutputVariable(RuntimeVariable.CarvedTests, totalNumberOfTestsCarved);
            client.trackOutputVariable(RuntimeVariable.CarvedCoverage, carvedCoverage);
        }
    }

    /**
     * Checks if there are any carved test cases available.
     *
     * @return true if there are carved test cases, false otherwise.
     */
    public boolean hasCarvedTestCases() {
        return !junitTests.isEmpty();
    }

    /**
     * Returns the number of carved test cases.
     *
     * @return the number of carved test cases.
     */
    public int getNumCarvedTestCases() {
        return junitTests.size();
    }

    /**
     * Returns the list of carved test cases.
     *
     * @return a list of {@link TestCase}.
     */
    public List<TestCase> getCarvedTestCases() {
        return junitTests;
    }

    /**
     * Returns a test suite containing all carved test cases.
     *
     * @return a {@link TestSuiteChromosome} containing the carved tests.
     */
    public TestSuiteChromosome getCarvedTestSuite() {
        TestSuiteChromosome testSuite = new TestSuiteChromosome();
        for (TestCase t : junitTests) {
            testSuite.addTest(t);
        }
        return testSuite;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns a chromosome containing a test case, potentially cloned from a carved test.
     */
    @Override
    public TestChromosome getChromosome() {
        final int N_mutations = Properties.SEED_MUTATIONS;
        final double P_clone = Properties.SEED_CLONE;

        double r = Randomness.nextDouble();

        if (r >= P_clone || junitTests.isEmpty()) {
            logger.debug("Using random test");
            return defaultFactory.getChromosome();
        }

        // Cloning
        logger.info("Cloning user test");
        TestCase test = Randomness.choice(junitTests);
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(test.clone());
        if (N_mutations > 0) {
            int numMutations = Randomness.nextInt(N_mutations);
            logger.debug("Mutations: " + numMutations);

            // Delta
            for (int i = 0; i < numMutations; i++) {
                chromosome.mutate();
            }
        }

        return chromosome;
    }

}
