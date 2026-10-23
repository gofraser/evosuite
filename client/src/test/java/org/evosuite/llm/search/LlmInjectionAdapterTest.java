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

import org.evosuite.Properties;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmInjectionAdapterTest {


    @BeforeEach
    void setUp() {
    }

    @AfterEach
    void tearDown() {
    }

    // --- TestChromosomeInjectionAdapter tests ---

    @Test
    void testChromosomeAdapterInjectsDirectly() {
        TestChromosomeInjectionAdapter adapter = new TestChromosomeInjectionAdapter();
        List<TestChromosome> population = new ArrayList<>();
        population.add(new TestChromosome());

        List<TestChromosome> tests = Arrays.asList(new TestChromosome(), new TestChromosome());
        List<FitnessFunction<TestChromosome>> ff = Collections.emptyList();

        int injected = adapter.inject(tests, population, ff, 0);

        assertEquals(3, population.size(), "should add both tests directly");
        assertEquals(2, injected);
    }

    @Test
    void testChromosomeAdapterIgnoresNull() {
        TestChromosomeInjectionAdapter adapter = new TestChromosomeInjectionAdapter();
        List<TestChromosome> population = new ArrayList<>();
        population.add(new TestChromosome());

        adapter.inject(null, population, Collections.emptyList(), 0);
        assertEquals(1, population.size());
    }

    @Test
    void testChromosomeAdapterIgnoresEmpty() {
        TestChromosomeInjectionAdapter adapter = new TestChromosomeInjectionAdapter();
        List<TestChromosome> population = new ArrayList<>();
        population.add(new TestChromosome());

        adapter.inject(Collections.emptyList(), population, Collections.emptyList(), 0);
        assertEquals(1, population.size());
    }

    @Test
    void testChromosomeAdapterReportsPopulationLimitedInsertions() {
        TestChromosomeInjectionAdapter adapter = new TestChromosomeInjectionAdapter();
        List<TestChromosome> population = new ArrayList<>();
        population.add(new TestChromosome());

        int injected = adapter.inject(
                Arrays.asList(new TestChromosome(), new TestChromosome()),
                population,
                Collections.emptyList(),
                2);

        assertEquals(1, injected);
        assertEquals(2, population.size());
    }

    // --- TestSuiteChromosomeInjectionAdapter tests ---

    @Test
    void suiteAdapterNewSuitePolicyCreatesNewSuiteChromosome() {
        TestSuiteChromosomeInjectionAdapter adapter = new TestSuiteChromosomeInjectionAdapter();

        List<TestSuiteChromosome> population = new ArrayList<>();
        TestSuiteChromosome existing = new TestSuiteChromosome();
        existing.addTest(new DefaultTestCase());
        population.add(existing);

        TestChromosome tc1 = new TestChromosome();
        tc1.setTestCase(new DefaultTestCase());
        TestChromosome tc2 = new TestChromosome();
        tc2.setTestCase(new DefaultTestCase());
        List<TestChromosome> tests = Arrays.asList(tc1, tc2);

        int injectedCount = adapter.inject(tests, population, Collections.emptyList(), 0);

        assertEquals(2, population.size(), "should add a new suite to the population");
        TestSuiteChromosome injected = population.get(1);
        assertEquals(2, injected.size(), "new suite should contain the 2 LLM tests");
        assertEquals(2, injectedCount, "return value counts source tests, not suite chromosomes");
    }

    @Test
    void suiteAdapterRespectsPopulationLimit() {
        TestSuiteChromosomeInjectionAdapter adapter = new TestSuiteChromosomeInjectionAdapter();

        List<TestSuiteChromosome> population = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            TestSuiteChromosome s = new TestSuiteChromosome();
            s.addTest(new DefaultTestCase());
            s.setFitness(null, (double) i);
            population.add(s);
        }

        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());

        adapter.inject(Collections.singletonList(tc), population, Collections.emptyList(), 3);

        assertEquals(3, population.size(),
                "population should be trimmed to limit after injection");
    }

    @Test
    void suiteAdapterHandlesEmptyPopulation() {
        TestSuiteChromosomeInjectionAdapter adapter = new TestSuiteChromosomeInjectionAdapter();

        List<TestSuiteChromosome> population = new ArrayList<>();
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());

        adapter.inject(Collections.singletonList(tc), population, Collections.emptyList(), 10);

        assertEquals(1, population.size(), "should create suite even for empty population");
        assertEquals(1, population.get(0).size());
    }

    @Test
    void suiteAdapterIgnoresNullTests() {
        TestSuiteChromosomeInjectionAdapter adapter = new TestSuiteChromosomeInjectionAdapter();
        List<TestSuiteChromosome> population = new ArrayList<>();
        population.add(new TestSuiteChromosome());

        adapter.inject(null, population, Collections.emptyList(), 0);
        assertEquals(1, population.size());
    }

    @Test
    void suiteAdapterNeverInjectsTestChromosomeDirectly() {
        // Verify the core invariant: no TestChromosome in a TestSuiteChromosome population
        TestSuiteChromosomeInjectionAdapter adapter = new TestSuiteChromosomeInjectionAdapter();

        List<TestSuiteChromosome> population = new ArrayList<>();
        population.add(new TestSuiteChromosome());

        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        adapter.inject(Collections.singletonList(tc), population, Collections.emptyList(), 0);

        for (Object item : population) {
            assertTrue(item instanceof TestSuiteChromosome,
                    "Every population member must be TestSuiteChromosome, got: " + item.getClass().getName());
        }
    }

    // --- Maximization-aware tests ---

    @Test
    void suiteAdapterTrimmingKeepsBestForMaximization() {
        TestSuiteChromosomeInjectionAdapter adapter = new TestSuiteChromosomeInjectionAdapter();

        List<TestSuiteChromosome> population = new ArrayList<>();
        TestSuiteChromosome best = new TestSuiteChromosome();
        best.addTest(new DefaultTestCase());
        best.setFitness(null, 10.0); // highest = best for maximization
        TestSuiteChromosome mid = new TestSuiteChromosome();
        mid.addTest(new DefaultTestCase());
        mid.setFitness(null, 5.0);
        TestSuiteChromosome worst = new TestSuiteChromosome();
        worst.addTest(new DefaultTestCase());
        worst.setFitness(null, 1.0); // lowest = worst for maximization
        population.add(best);
        population.add(mid);
        population.add(worst);

        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());

        List<FitnessFunction<TestSuiteChromosome>> ffs = Collections.singletonList(
                new MaximizingSuiteFitness());

        adapter.inject(Collections.singletonList(tc), population, ffs, 3);

        assertEquals(3, population.size());
        // The worst (lowest fitness=1.0) should be trimmed; the injected new suite (no fitness)
        // or the worst existing should be gone. Best (10.0) must survive.
        assertTrue(population.contains(best),
                "Best fitness suite (10.0) must survive trimming for maximization");
    }

    @Test
    void suiteAdapterTrimmingKeepsBestForMinimization() {
        TestSuiteChromosomeInjectionAdapter adapter = new TestSuiteChromosomeInjectionAdapter();

        List<TestSuiteChromosome> population = new ArrayList<>();
        TestSuiteChromosome best = new TestSuiteChromosome();
        best.addTest(new DefaultTestCase());
        best.setFitness(null, 0.1); // lowest = best for minimization
        TestSuiteChromosome mid = new TestSuiteChromosome();
        mid.addTest(new DefaultTestCase());
        mid.setFitness(null, 3.0);
        TestSuiteChromosome worst = new TestSuiteChromosome();
        worst.addTest(new DefaultTestCase());
        worst.setFitness(null, 9.0); // highest = worst for minimization
        population.add(best);
        population.add(mid);
        population.add(worst);

        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());

        // empty fitness functions = minimization (default)
        adapter.inject(Collections.singletonList(tc), population, Collections.emptyList(), 3);

        assertEquals(3, population.size());
        assertTrue(population.contains(best),
                "Best fitness suite (0.1) must survive trimming for minimization");
    }

    // --- Helper classes ---

    private static class MaximizingSuiteFitness extends TestSuiteFitnessFunction {
        private static final long serialVersionUID = 1L;

        @Override
        public double getFitness(TestSuiteChromosome individual) {
            return individual.getFitness();
        }

        @Override
        public boolean isMaximizationFunction() {
            return true;
        }
    }
}
