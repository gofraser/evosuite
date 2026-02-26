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

import org.evosuite.Properties;
import org.evosuite.testcase.TestChromosome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Default leader-based species assigner. Assigns each individual to the first
 * species whose leader is within the distance threshold, or creates a new species.
 * Species assignment is deterministic given a fixed population order.
 */
public class DefaultSpeciesAssigner implements SpeciesAssigner {

    private static final Logger logger = LoggerFactory.getLogger(DefaultSpeciesAssigner.class);

    private final SpeciesDistance distanceMetric;
    private final double threshold;

    public DefaultSpeciesAssigner() {
        this(new JaccardSpeciesDistance(), Properties.SPECIATION_THRESHOLD);
    }

    public DefaultSpeciesAssigner(SpeciesDistance distanceMetric, double threshold) {
        this.distanceMetric = distanceMetric;
        this.threshold = threshold;
    }

    @Override
    public Map<Integer, List<TestChromosome>> groupBySpecies(List<TestChromosome> population) {
        Map<Integer, List<TestChromosome>> speciesMap = new LinkedHashMap<>();
        List<TestChromosome> leaders = new ArrayList<>();

        for (TestChromosome individual : population) {
            int assignedSpecies = -1;
            for (int s = 0; s < leaders.size(); s++) {
                double dist = distanceMetric.distance(individual, leaders.get(s));
                if (dist <= threshold) {
                    assignedSpecies = s;
                    break;
                }
            }
            if (assignedSpecies < 0) {
                // Create new species with this individual as leader
                assignedSpecies = leaders.size();
                leaders.add(individual);
                speciesMap.put(assignedSpecies, new ArrayList<>());
            }
            speciesMap.get(assignedSpecies).add(individual);
        }

        logger.debug("Speciation: {} species from {} individuals", speciesMap.size(), population.size());
        return speciesMap;
    }
}
