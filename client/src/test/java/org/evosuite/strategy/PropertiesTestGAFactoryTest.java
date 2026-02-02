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
package org.evosuite.strategy;

import org.evosuite.Properties;
import org.evosuite.ga.populationlimit.IndividualPopulationLimit;
import org.evosuite.ga.populationlimit.PopulationLimit;
import org.evosuite.ga.populationlimit.SizePopulationLimit;
import org.evosuite.testcase.TestChromosome;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class PropertiesTestGAFactoryTest {

    private static class TestFactory extends PropertiesTestGAFactory {
        @Override
        public PopulationLimit<TestChromosome> getPopulationLimit() {
            return super.getPopulationLimit();
        }
    }

    @Test
    public void testGetPopulationLimit() {
        TestFactory factory = new TestFactory();

        Properties.POPULATION_LIMIT = Properties.PopulationLimit.INDIVIDUALS;
        assertTrue(factory.getPopulationLimit() instanceof IndividualPopulationLimit);

        Properties.POPULATION_LIMIT = Properties.PopulationLimit.TESTS;
        assertTrue(factory.getPopulationLimit() instanceof IndividualPopulationLimit);

        Properties.POPULATION_LIMIT = Properties.PopulationLimit.STATEMENTS;
        assertTrue(factory.getPopulationLimit() instanceof SizePopulationLimit);
    }
}
