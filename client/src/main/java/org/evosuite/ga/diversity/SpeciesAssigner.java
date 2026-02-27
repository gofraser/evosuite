/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.ga.diversity;

import org.evosuite.testcase.TestChromosome;

import java.util.List;
import java.util.Map;

/**
 * Assigns individuals to species based on a distance metric and threshold.
 * Species are identified by integer IDs starting from 0.
 */
public interface SpeciesAssigner {

    /**
     * Group the population into species. Each individual is assigned to exactly
     * one species. Species IDs are deterministic and stable within a generation.
     *
     * @param population the current population
     * @return mapping from species ID to list of members
     */
    Map<Integer, List<TestChromosome>> groupBySpecies(List<TestChromosome> population);
}
