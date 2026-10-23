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
package org.evosuite.llm.search;

import org.evosuite.ga.FitnessFunction;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;

/**
 * Injection adapter for WholeSuite algorithms (StandardGA/MonotonicGA) that
 * evolve {@link TestSuiteChromosome}.
 *
 * <p>LLM-generated {@link TestChromosome}s cannot be inserted directly into a
 * {@code TestSuiteChromosome} population. This adapter creates a new
 * {@link TestSuiteChromosome} from the LLM-generated tests and adds it to the
 * population to compete via normal replacement.
 *
 * <p>Into a non-empty population the new suite is evaluated straight away: the
 * population is trimmed by fitness and selected from before the GA evaluates it again, and an
 * unevaluated suite carries the worst possible fitness (or its fitness from before the merge).
 * An empty population is filled and evaluated by the GA, so its seed suite is left to it.
 */
public class TestSuiteChromosomeInjectionAdapter
        implements LlmInjectionAdapter<TestSuiteChromosome> {

    private static final Logger logger =
            LoggerFactory.getLogger(TestSuiteChromosomeInjectionAdapter.class);

    @Override
    public int inject(List<TestChromosome> tests,
                      List<TestSuiteChromosome> population,
                      List<FitnessFunction<TestSuiteChromosome>> fitnessFunctions,
                      int populationLimit) {
        if (tests == null || tests.isEmpty()) {
            return 0;
        }
        if (population.isEmpty()) {
            logger.debug("WholeSuite population is empty; creating new suite from {} LLM test(s)", tests.size());
            injectAsNewSuite(tests, population, fitnessFunctions);
            return tests.size();
        }

        boolean maximization = !fitnessFunctions.isEmpty()
                && fitnessFunctions.get(0).isMaximizationFunction();
        evaluate(injectAsNewSuite(tests, population, fitnessFunctions), fitnessFunctions);

        // Trim population to limit if exceeded — remove worst-fitness individuals
        if (populationLimit > 0 && population.size() > populationLimit) {
            if (maximization) {
                // Maximization: higher fitness is better; sort descending, drop tail (lowest)
                population.sort(Comparator.comparingDouble(
                        (TestSuiteChromosome c) -> c.getFitness()).reversed());
            } else {
                // Minimization: lower fitness is better; sort ascending, drop tail (highest)
                population.sort(Comparator.comparingDouble(c -> c.getFitness()));
            }
            while (population.size() > populationLimit) {
                population.remove(population.size() - 1);
            }
        }
        return tests.size();
    }

    private static void evaluate(TestSuiteChromosome suite,
                                 List<FitnessFunction<TestSuiteChromosome>> fitnessFunctions) {
        if (suite == null) {
            return;
        }
        for (FitnessFunction<TestSuiteChromosome> ff : fitnessFunctions) {
            ff.getFitness(suite);
        }
    }

    private TestSuiteChromosome injectAsNewSuite(List<TestChromosome> tests,
                                                 List<TestSuiteChromosome> population,
                                                 List<FitnessFunction<TestSuiteChromosome>> fitnessFunctions) {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        for (TestChromosome test : tests) {
            suite.addTestChromosome(test);
        }
        for (FitnessFunction<TestSuiteChromosome> ff : fitnessFunctions) {
            suite.addFitness(ff);
        }
        population.add(suite);
        logger.debug("Injected new TestSuiteChromosome with {} LLM test(s) into population (size={})",
                tests.size(), population.size());
        return suite;
    }
}
