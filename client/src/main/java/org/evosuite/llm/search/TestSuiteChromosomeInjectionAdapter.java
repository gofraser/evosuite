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

import org.evosuite.Properties;
import org.evosuite.Properties.LlmSuiteInjectionPolicy;
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
 * {@code TestSuiteChromosome} population. This adapter supports two policies:
 * <ul>
 *   <li>{@link LlmSuiteInjectionPolicy#NEW_SUITE} (default):
 *       Creates a new {@link TestSuiteChromosome} from the LLM-generated tests
 *       and adds it to the population to compete via normal replacement.</li>
 *   <li>{@link LlmSuiteInjectionPolicy#MERGE_INTO_EXISTING}:
 *       Merges LLM-generated tests into the worst-fitness existing suite,
 *       then marks it for re-evaluation.</li>
 * </ul>
 */
public class TestSuiteChromosomeInjectionAdapter
        implements LlmInjectionAdapter<TestSuiteChromosome> {

    private static final Logger logger =
            LoggerFactory.getLogger(TestSuiteChromosomeInjectionAdapter.class);

    @Override
    public void inject(List<TestChromosome> tests,
                       List<TestSuiteChromosome> population,
                       List<FitnessFunction<TestSuiteChromosome>> fitnessFunctions,
                       int populationLimit) {
        if (tests == null || tests.isEmpty()) {
            return;
        }
        if (population.isEmpty()) {
            logger.debug("WholeSuite population is empty; creating new suite from {} LLM test(s)", tests.size());
            injectAsNewSuite(tests, population, fitnessFunctions);
            return;
        }

        boolean maximization = !fitnessFunctions.isEmpty()
                && fitnessFunctions.get(0).isMaximizationFunction();

        LlmSuiteInjectionPolicy policy = Properties.LLM_SUITE_INJECTION_POLICY;
        switch (policy) {
            case NEW_SUITE:
                injectAsNewSuite(tests, population, fitnessFunctions);
                break;
            case MERGE_INTO_EXISTING:
                mergeIntoExisting(tests, population, maximization);
                break;
            default:
                logger.warn("Unknown LLM suite injection policy: {}; falling back to NEW_SUITE", policy);
                injectAsNewSuite(tests, population, fitnessFunctions);
                break;
        }

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
    }

    private void injectAsNewSuite(List<TestChromosome> tests,
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
    }

    private void mergeIntoExisting(List<TestChromosome> tests,
                                   List<TestSuiteChromosome> population,
                                   boolean maximization) {
        // Select worst-fitness suite according to objective direction
        TestSuiteChromosome target;
        if (maximization) {
            // Maximization: worst = lowest fitness value
            target = population.stream()
                    .min(Comparator.comparingDouble(c -> c.getFitness()))
                    .orElse(null);
        } else {
            // Minimization: worst = highest fitness value
            target = population.stream()
                    .max(Comparator.comparingDouble(c -> c.getFitness()))
                    .orElse(null);
        }
        if (target == null) {
            logger.warn("No target suite found for merge; skipping injection");
            return;
        }
        for (TestChromosome test : tests) {
            target.addTestChromosome(test);
        }
        target.setChanged(true);
        logger.debug("Merged {} LLM test(s) into existing suite (now {} tests)",
                tests.size(), target.size());
    }
}
