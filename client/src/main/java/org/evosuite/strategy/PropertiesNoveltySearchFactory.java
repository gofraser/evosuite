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
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.metaheuristics.NoveltySearch;
import org.evosuite.ga.operators.crossover.CrossOverFunction;
import org.evosuite.ga.operators.ranking.RankingFunction;
import org.evosuite.ga.operators.selection.SelectionFunction;
import org.evosuite.ga.stoppingconditions.StoppingCondition;
import org.evosuite.statistics.StatisticsListener;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.secondaryobjectives.TestCaseSecondaryObjective;
import org.evosuite.testsuite.RelativeSuiteLengthBloatControl;
import org.evosuite.utils.ResourceController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for Novelty Search.
 */
public class PropertiesNoveltySearchFactory extends PropertiesSearchAlgorithmFactory<TestChromosome> {

    private static final Logger logger = LoggerFactory.getLogger(PropertiesNoveltySearchFactory.class);

    @Override
    //public GeneticAlgorithm<TestChromosome> getSearchAlgorithm() {
    public NoveltySearch getSearchAlgorithm() {
        ChromosomeFactory<TestChromosome> factory = testChromosomeFactory();

        NoveltySearch ga = new NoveltySearch(factory);

        if (Properties.NEW_STATISTICS) {
            ga.addListener(new StatisticsListener<>());
        }

        // How to select candidates for reproduction
        SelectionFunction<TestChromosome> selectionFunction = selectionFunction(false);
        selectionFunction.setMaximize(false);
        ga.setSelectionFunction(selectionFunction);

        RankingFunction<TestChromosome> rankingFunction = rankingFunction();
        ga.setRankingFunction(rankingFunction);

        // When to stop the search
        StoppingCondition<TestChromosome> stoppingCondition = getStoppingCondition();
        setUpStoppingConditions(ga, stoppingCondition);

        ga.resetStoppingConditions();
        ga.setPopulationLimit(getPopulationLimit());

        // How to cross over
        CrossOverFunction<TestChromosome> crossoverFunction = crossoverFunction(false);
        ga.setCrossOverFunction(crossoverFunction);

        // What to do about bloat
        if (Properties.CHECK_BEST_LENGTH) {
            RelativeSuiteLengthBloatControl<TestChromosome> bloatControl =
                    new RelativeSuiteLengthBloatControl<>();
            ga.addBloatControl(bloatControl);
            ga.addListener(bloatControl);
        }

        TestCaseSecondaryObjective.setSecondaryObjectives();

        // Some statistics
        //if (Properties.STRATEGY == Strategy.EVOSUITE)
        //    ga.addListener(SearchStatistics.getInstance());
        // ga.addListener(new MemoryMonitor());
        // ga.addListener(MutationStatistics.getInstance());
        // ga.addListener(BestChromosomeTracker.getInstance());

        if (Properties.DYNAMIC_LIMIT) {
            applyDynamicLimit(stoppingCondition);
        }

        if (Properties.LOCAL_SEARCH_RESTORE_COVERAGE) {
            // Novelty search does not use local search yet
            // hence we don't need to add the BranchCoverageMap
            // SearchListener here
            // SearchListener<TestChromosome> map = BranchCoverageMap.getInstance();
            // ga.addListener(map);
        }

        if (Properties.SHUTDOWN_HOOK) {
            addShutdownStoppingConditions(ga);
        }

        ga.addListener(new ResourceController<>());
        return ga;
    }


}
