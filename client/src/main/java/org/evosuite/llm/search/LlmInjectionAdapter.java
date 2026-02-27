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
package org.evosuite.llm.search;

import org.evosuite.ga.Chromosome;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.testcase.TestChromosome;

import java.util.List;

/**
 * Chromosome-type-aware injection adapter for LLM-generated tests.
 *
 * <p>Different GA families evolve different chromosome types:
 * <ul>
 *   <li>MOSA/DynaMOSA evolve {@link TestChromosome} — direct injection is valid.</li>
 *   <li>WholeSuite (StandardGA/MonotonicGA) evolve {@code TestSuiteChromosome} —
 *       raw {@link TestChromosome} injection is invalid and must be adapted to
 *       suite-level operations.</li>
 * </ul>
 *
 * @param <T> the chromosome type of the target population
 */
public interface LlmInjectionAdapter<T extends Chromosome<T>> {

    /**
     * Inject LLM-generated {@link TestChromosome}s into the given population.
     *
     * <p>Implementations are responsible for adapting the raw test chromosomes
     * to the target population's chromosome type and maintaining population
     * size invariants.
     *
     * @param tests             LLM-generated test chromosomes (may be empty or null)
     * @param population        the current GA population (mutable)
     * @param fitnessFunctions  fitness functions to assign to injected individuals
     * @param populationLimit   maximum population size (0 = no limit)
     */
    void inject(List<TestChromosome> tests,
                List<T> population,
                List<FitnessFunction<T>> fitnessFunctions,
                int populationLimit);
}
