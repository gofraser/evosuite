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
 * Applies species-aware policies during survival selection.
 * This is additive to existing MOSA ranking/crowding semantics.
 */
public interface SpeciesPolicy {

    /**
     * Apply per-species survival caps to the ranked survivor list.
     * Individuals beyond the species cap are replaced by the next-best
     * individuals from underrepresented species.
     *
     * @param rankedSurvivors the survivor list already ordered by MOSA ranking/crowding
     * @param speciesMap      species assignment from {@link SpeciesAssigner#groupBySpecies}
     * @param targetSize      desired population size
     * @param survivalCap     maximum fraction of survivors any single species may occupy
     * @return capped survivor list of size min(targetSize, available individuals)
     */
    List<TestChromosome> applySurvivalCaps(
            List<TestChromosome> rankedSurvivors,
            Map<Integer, List<TestChromosome>> speciesMap,
            int targetSize,
            double survivalCap);

    /**
     * Optionally balance parent selection pool across species.
     *
     * @param population  the current population
     * @param speciesMap  species assignment
     * @return a balanced selection pool (may be the same as input if balancing is disabled)
     */
    List<TestChromosome> balanceParentPool(
            List<TestChromosome> population,
            Map<Integer, List<TestChromosome>> speciesMap);
}
