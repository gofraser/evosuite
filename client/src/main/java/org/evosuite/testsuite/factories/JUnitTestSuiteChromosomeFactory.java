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

package org.evosuite.testsuite.factories;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.utils.Randomness;


/**
 * <p>JUnitTestSuiteChromosomeFactory class.</p>
 *
 * @author fraser
 */
public class JUnitTestSuiteChromosomeFactory implements
        ChromosomeFactory<TestSuiteChromosome> {

    private static final long serialVersionUID = 1L;

    private final ChromosomeFactory<TestChromosome> defaultFactory;

    /**
     * <p>Constructor for JUnitTestSuiteChromosomeFactory.</p>
     *
     * @param defaultFactory a {@link org.evosuite.ga.ChromosomeFactory} object.
     */
    public JUnitTestSuiteChromosomeFactory(
            ChromosomeFactory<TestChromosome> defaultFactory) {
        this.defaultFactory = defaultFactory;
    }

    /* (non-Javadoc)
     * @see org.evosuite.ga.ChromosomeFactory#getChromosome()
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public TestSuiteChromosome getChromosome() {
        TestSuiteChromosome chromosome = new TestSuiteChromosome(defaultFactory);
        chromosome.clearTests();

        int min = Properties.MIN_INITIAL_TESTS;
        int max = Properties.MAX_INITIAL_TESTS;

        if (max < min) {
            max = min;
        }

        int numTests = Randomness.nextInt(min, max + 1);

        for (int i = 0; i < numTests; i++) {
            TestChromosome test = defaultFactory.getChromosome();
            chromosome.addTest(test);
        }

        return chromosome;
    }

}
