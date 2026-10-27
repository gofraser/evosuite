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
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.ga.operators.crossover.CoverageCrossOver;
import org.evosuite.ga.operators.crossover.SinglePointCrossOver;
import org.evosuite.ga.operators.crossover.UniformCrossOver;
import org.evosuite.ga.operators.ranking.FastNonDominatedSorting;
import org.evosuite.ga.operators.ranking.RankBasedPreferenceSorting;
import org.evosuite.ga.operators.selection.BestKSelection;
import org.evosuite.ga.operators.selection.RandomKSelection;
import org.evosuite.ga.operators.selection.RankSelection;
import org.evosuite.testcase.TestChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the operator switches shared by the search algorithm factories, including the values
 * that only some factories support.
 */
class PropertiesSearchAlgorithmFactoryTest {

    private final PropertiesSearchAlgorithmFactory<TestChromosome> testFactory =
            new PropertiesSearchAlgorithmFactory<TestChromosome>() {
                @Override
                public GeneticAlgorithm<TestChromosome> getSearchAlgorithm() {
                    throw new UnsupportedOperationException();
                }
            };

    @BeforeEach
    void setUp() {
        Properties.getInstance().resetToDefaults();
    }

    @AfterEach
    void tearDown() {
        Properties.getInstance().resetToDefaults();
    }

    @Test
    void kSelectionIsOnlyAvailableWhenRequested() {
        Properties.SELECTION_FUNCTION = Properties.SelectionFunction.BESTK;
        assertInstanceOf(BestKSelection.class, testFactory.selectionFunction(true));
        assertInstanceOf(RankSelection.class, testFactory.selectionFunction(false));
        Properties.SELECTION_FUNCTION = Properties.SelectionFunction.RANDOMK;
        assertInstanceOf(RandomKSelection.class, testFactory.selectionFunction(true));
        assertInstanceOf(RankSelection.class, testFactory.selectionFunction(false));
    }

    @Test
    void uniformCrossoverIsOnlyAvailableWhenRequested() {
        Properties.CROSSOVER_FUNCTION = Properties.CrossoverFunction.UNIFORM;
        assertInstanceOf(UniformCrossOver.class, testFactory.crossoverFunction(true));
        assertThrows(RuntimeException.class, () -> testFactory.crossoverFunction(false));
    }

    @Test
    void coverageCrossoverIsLeftToTheSuiteFactory() {
        Properties.CROSSOVER_FUNCTION = Properties.CrossoverFunction.COVERAGE;
        assertThrows(RuntimeException.class, () -> testFactory.crossoverFunction(true));
        Properties.STRATEGY = Properties.Strategy.EVOSUITE;
        assertInstanceOf(CoverageCrossOver.class, new PropertiesSuiteGAFactory().getCrossoverFunction());
        Properties.STRATEGY = Properties.Strategy.MOSUITE;
        assertThrows(RuntimeException.class, () -> new PropertiesSuiteGAFactory().getCrossoverFunction());
    }

    @Test
    void singlePointCrossoverIsSupportedEverywhere() {
        Properties.CROSSOVER_FUNCTION = Properties.CrossoverFunction.SINGLEPOINT;
        assertInstanceOf(SinglePointCrossOver.class, testFactory.crossoverFunction(false));
    }

    @Test
    void rankingFollowsTheConfiguredType() {
        Properties.RANKING_TYPE = Properties.RankingType.FAST_NON_DOMINATED_SORTING;
        assertInstanceOf(FastNonDominatedSorting.class, testFactory.rankingFunction());
        Properties.RANKING_TYPE = Properties.RankingType.PREFERENCE_SORTING;
        assertInstanceOf(RankBasedPreferenceSorting.class, testFactory.rankingFunction());
    }
}
