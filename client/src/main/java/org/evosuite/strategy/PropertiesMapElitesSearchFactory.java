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
import org.evosuite.ga.metaheuristics.mapelites.MAPElites;
import org.evosuite.ga.stoppingconditions.StoppingCondition;
import org.evosuite.statistics.StatisticsListener;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.secondaryobjectives.TestCaseSecondaryObjective;
import org.evosuite.testsuite.RelativeSuiteLengthBloatControl;
import org.evosuite.utils.ResourceController;

/**
 * Factory for MAP Elites Search.
 */
public class PropertiesMapElitesSearchFactory
        extends PropertiesSearchAlgorithmFactory<TestChromosome> {

    @Override
    public MAPElites getSearchAlgorithm() {
        ChromosomeFactory<TestChromosome> factory = testChromosomeFactory();
        MAPElites ga = new MAPElites(factory);

        if (Properties.NEW_STATISTICS) {
            ga.addListener(new StatisticsListener<>());
        }

        // When to stop the search
        StoppingCondition<TestChromosome> stoppingCondition = getStoppingCondition();
        setUpStoppingConditions(ga, stoppingCondition);

        if (usesMutationCriterion()) {
            // MutationTestPool is suite-only and incompatible with TestChromosome.
            throw new RuntimeException("Broken code :(");
        }
        ga.resetStoppingConditions();

        if (Properties.CHECK_BEST_LENGTH) {
            RelativeSuiteLengthBloatControl<TestChromosome> bloatControl =
                    new RelativeSuiteLengthBloatControl<>();
            ga.addBloatControl(bloatControl);
            ga.addListener(bloatControl);
        }

        TestCaseSecondaryObjective.setSecondaryObjectives();

        if (Properties.DYNAMIC_LIMIT) {
            applyDynamicLimit(stoppingCondition);
        }

        if (Properties.LOCAL_SEARCH_RESTORE_COVERAGE) {
            // BranchCoverageMap is suite-only and incompatible with TestChromosome.
            throw new RuntimeException("Broken code :(");
        }

        if (Properties.SHUTDOWN_HOOK) {
            addShutdownStoppingConditions(ga);
        }

        ga.addListener(new ResourceController<>());
        return ga;
    }
}
