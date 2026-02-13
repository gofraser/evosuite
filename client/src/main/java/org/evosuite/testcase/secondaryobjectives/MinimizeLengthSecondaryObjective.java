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
package org.evosuite.testcase.secondaryobjectives;

import org.evosuite.ga.SecondaryObjective;
import org.evosuite.testcase.TestChromosome;

/**
 * Secondary objective to minimize the length (number of statements) of the test case.
 *
 * @author José Campos
 */
public class MinimizeLengthSecondaryObjective extends SecondaryObjective<TestChromosome> {

    private static final long serialVersionUID = 7211557650429998223L;

    /**
     * Compares two chromosomes based on their size (length).
     */
    @Override
    public int compareChromosomes(TestChromosome chromosome1, TestChromosome chromosome2) {
        return chromosome1.size() - chromosome2.size();
    }

    /**
     * Compares two generations based on the size (length) of the test cases.
     */
    @Override
    public int compareGenerations(TestChromosome parent1, TestChromosome parent2,
                                  TestChromosome child1, TestChromosome child2) {
        return Math.min(parent1.size(), parent2.size())
                - Math.min(child1.size(), child2.size());
    }

}
