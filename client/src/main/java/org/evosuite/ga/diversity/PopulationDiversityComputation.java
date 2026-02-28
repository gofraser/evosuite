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

import org.evosuite.Properties;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Computes population diversity for a {@code List<TestChromosome>} using the
 * configured speciation metric (Jaccard-based by default, STF if enabled).
 *
 * <p>Diversity is the mean pairwise distance across all (or a sampled subset of)
 * pairs, bounded to [0, 1] where 0 = all individuals identical and 1 = maximally diverse.
 *
 * <p>This utility is designed for use in MOSA/DynaMOSA where the population consists
 * of individual test chromosomes, as opposed to test-suite chromosomes in WTS.
 */
public final class PopulationDiversityComputation {

    private static final Logger logger = LoggerFactory.getLogger(PopulationDiversityComputation.class);

    private PopulationDiversityComputation() {
        // utility class
    }

    /**
     * Compute population diversity using the configured distance metric
     * and optional pair sampling via {@link Properties#DIVERSITY_SAMPLE_SIZE}.
     *
     * <p>When {@link Properties#STF_ENABLED} is {@code true}, uses STFDistance
     * (pure or hybrid depending on {@link Properties#STF_JACCARD_WEIGHT}).
     * Otherwise uses JaccardSpeciesDistance (default).
     *
     * @param population the test chromosome population
     * @return diversity in [0, 1]
     */
    public static double computeDiversity(List<TestChromosome> population) {
        SpeciesDistance distance;
        if (Properties.STF_ENABLED) {
            if (Properties.STF_JACCARD_WEIGHT <= 0.0) {
                distance = new STFDistance();
            } else {
                distance = new STFDistance(Properties.STF_JACCARD_WEIGHT,
                        new JaccardSpeciesDistance());
            }
        } else {
            distance = new JaccardSpeciesDistance();
        }
        return computeDiversity(population, distance, Properties.DIVERSITY_SAMPLE_SIZE);
    }

    /**
     * Compute population diversity with an explicit distance function and sample size.
     *
     * @param population the test chromosome population
     * @param distance   the distance metric to use
     * @param sampleSize maximum number of pairs to sample (0 = all pairs)
     * @return diversity in [0, 1]
     */
    public static double computeDiversity(List<TestChromosome> population,
                                          SpeciesDistance distance,
                                          int sampleSize) {
        int n = population.size();
        if (n < 2) {
            return 0.0;
        }

        long totalPairs = (long) n * (n - 1) / 2;
        boolean useSampling = sampleSize > 0 && sampleSize < totalPairs;

        double totalDistance;
        int numPairs;

        if (useSampling) {
            totalDistance = 0.0;
            numPairs = sampleSize;
            for (int s = 0; s < sampleSize; s++) {
                int i = Randomness.nextInt(n);
                int j = Randomness.nextInt(n - 1);
                if (j >= i) {
                    j++;
                }
                double d = distance.distance(population.get(i), population.get(j));
                totalDistance += d;
            }
        } else {
            totalDistance = 0.0;
            numPairs = 0;
            for (int i = 0; i < n - 1; i++) {
                for (int j = i + 1; j < n; j++) {
                    totalDistance += distance.distance(population.get(i), population.get(j));
                    numPairs++;
                }
            }
        }

        if (numPairs == 0) {
            return 0.0;
        }

        double diversity = totalDistance / numPairs;
        // Clamp to [0, 1]
        diversity = Math.max(0.0, Math.min(1.0, diversity));

        logger.debug("Population diversity ({} pairs): {}", numPairs, diversity);
        return diversity;
    }
}
