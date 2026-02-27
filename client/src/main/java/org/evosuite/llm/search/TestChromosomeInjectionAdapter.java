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
package org.evosuite.llm.search;

import org.evosuite.ga.FitnessFunction;
import org.evosuite.testcase.TestChromosome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Injection adapter for MOSA-family algorithms that evolve {@link TestChromosome}.
 *
 * <p>Directly adds LLM-generated test chromosomes to the population with fitness
 * function assignment. Population size limiting is left to the caller's
 * ranking/distillation logic (MOSA's non-dominated sorting handles this).
 */
public class TestChromosomeInjectionAdapter implements LlmInjectionAdapter<TestChromosome> {

    private static final Logger logger = LoggerFactory.getLogger(TestChromosomeInjectionAdapter.class);

    @Override
    public void inject(List<TestChromosome> tests,
                       List<TestChromosome> population,
                       List<FitnessFunction<TestChromosome>> fitnessFunctions,
                       int populationLimit) {
        if (tests == null || tests.isEmpty()) {
            return;
        }
        for (TestChromosome test : tests) {
            fitnessFunctions.forEach(test::addFitness);
            population.add(test);
        }
        logger.debug("Injected {} LLM-generated TestChromosome(s) into population (size={})",
                tests.size(), population.size());
    }
}
