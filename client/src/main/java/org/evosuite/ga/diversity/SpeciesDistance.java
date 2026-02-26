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
package org.evosuite.ga.diversity;

import org.evosuite.testcase.TestChromosome;

/**
 * Computes a distance between two test chromosomes for speciation purposes.
 * Distances are in [0, 1] where 0 means identical and 1 means maximally different.
 */
public interface SpeciesDistance {

    /**
     * Compute the distance between two individuals.
     *
     * @param a first individual
     * @param b second individual
     * @return distance in [0, 1]
     */
    double distance(TestChromosome a, TestChromosome b);
}
