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
     * Applies species-level protection (age-ordered quotas, newborn protection and per-species
     * caps) to the ranked survivor list.
     *
     * @param rankedSurvivors              the survivor list already ordered by MOSA ranking/crowding
     * @param speciesMap                   species assignment from {@link SpeciesAssigner#groupBySpecies}
     * @param targetSize                   desired population size
     * @param survivalCap                  maximum fraction of survivors any single species may occupy
     * @param currentGeneration            the current generation
     * @param minSurvivorsPerSpecies       minimum survivors guaranteed to each species
     * @param newbornProtectionGenerations age below which a species gets extra protection
     * @param speciesBirthGeneration       birth generation per species id (may be null)
     * @param stats                        receives protection counters (may be null)
     * @return survivor list of size min(targetSize, available individuals)
     */
    List<TestChromosome> applyProtectedSurvival(
            List<TestChromosome> rankedSurvivors,
            Map<Integer, List<TestChromosome>> speciesMap,
            int targetSize,
            double survivalCap,
            int currentGeneration,
            int minSurvivorsPerSpecies,
            int newbornProtectionGenerations,
            Map<Integer, Integer> speciesBirthGeneration,
            SpeciesProtectionStats stats);

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

    /**
     * Adjust crowding-distance-like density scores inside a front to penalize
     * crowded species (fitness sharing). Implementations may leave the front
     * unchanged when sharing is disabled.
     *
     * @param stats receives sharing counters (may be null)
     */
    void applyFitnessSharing(
            List<TestChromosome> front,
            Map<Integer, List<TestChromosome>> speciesMap,
            SpeciesProtectionStats stats);
}
