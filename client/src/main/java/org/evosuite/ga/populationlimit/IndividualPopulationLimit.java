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
package org.evosuite.ga.populationlimit;

import org.evosuite.Properties;
import org.evosuite.ga.Chromosome;

import java.util.List;


/**
 * <p>IndividualPopulationLimit class.</p>
 *
 * <p>Limits the population size based on the number of individuals.</p>
 *
 * @author Gordon Fraser
 */
public class IndividualPopulationLimit<T extends Chromosome<T>> implements PopulationLimit<T> {

    private static final long serialVersionUID = -3985726226793280031L;

    private final int limit;

    /**
     * Constructor using the default population limit from Properties.
     */
    public IndividualPopulationLimit() {
        this(Properties.POPULATION);
    }

    /**
     * Constructor using a specific population limit.
     *
     * @param limit the maximum number of individuals in the population.
     */
    public IndividualPopulationLimit(int limit) {
        this.limit = limit;
    }

    /**
     * Copy Constructor.
     *
     * <p>This constructor is used by {@link org.evosuite.ga.metaheuristics.TestSuiteAdapter} to adapt the generic type
     * parameter.</p>
     *
     * <p>This constructor shall preserve the current state of the IndividualPopulationLimit (if existing).</p>
     *
     * @param other the other limit to copy
     */
    public IndividualPopulationLimit(IndividualPopulationLimit<?> other) {
        this.limit = other.limit;
    }

    /* (non-Javadoc)
     * @see org.evosuite.ga.PopulationLimit#isPopulationFull(java.util.List)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isPopulationFull(List<T> population) {
        return population.size() >= limit;
    }

    /**
     * Returns the configured limit.
     *
     * @return the limit
     */
    public int getLimit() {
        return limit;
    }
}
