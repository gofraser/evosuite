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
package org.evosuite.strategy;

import org.evosuite.Properties;
import org.evosuite.coverage.TestFitnessFactory;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MOSuiteStrategyTest {

    private final Properties.Criterion[] originalCriteria = Properties.CRITERION;

    @AfterEach
    void restoreCriteria() {
        Properties.CRITERION = originalCriteria;
    }

    @Test
    void testZeroGoalsSkipsSearch() {
        Properties.CRITERION = new Properties.Criterion[0];

        @SuppressWarnings("unchecked")
        GeneticAlgorithm<TestSuiteChromosome> algorithm = mock(GeneticAlgorithm.class);

        MOSuiteStrategy strategy = new MOSuiteStrategy() {
            @Override
            protected GeneticAlgorithm<TestSuiteChromosome> createSearchAlgorithm() {
                return algorithm;
            }

            @Override
            protected List<TestFitnessFactory<? extends TestFitnessFunction>> getConfiguredGoalFactories() {
                return Collections.emptyList();
            }

            @Override
            protected boolean canGenerateTestsForSUT() {
                return true;
            }

            @Override
            protected void sendExecutionStatistics() {
                // no-op for focused unit test
            }
        };

        TestSuiteChromosome suite = strategy.generateTests();

        Assertions.assertNotNull(suite);
        Assertions.assertEquals(0, suite.size());
        verify(algorithm, never()).generateSolution();
    }
}
