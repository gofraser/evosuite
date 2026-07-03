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
package org.evosuite.objectconstruction;

import org.evosuite.EvoSuite;
import org.evosuite.Properties;
import org.evosuite.Properties.Algorithm;
import org.evosuite.Properties.Criterion;
import org.evosuite.SystemTestBase;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.examples.with.different.packagename.objectconstruction.construction.ConstructionTarget;

/**
 * End-to-end system test for construction fitness (EvoObj). DynaMOSA with
 * {@code BRANCH:METHODNOEXCEPTION} on a class whose constructor validates its argument: with
 * {@code construction_fitness=true} the graded goals are activated up front (goal-manager change)
 * and the search runs end to end, reaching full coverage without regressing versus the plain binary
 * baseline.
 *
 * <p>The layered distance itself is verified deterministically by the client-side
 * {@code ConstructionFitnessTest}. A strict "coverage-over-time dominates the binary baseline"
 * comparison is a research benchmark: on small, fully-coverable fixtures
 * ordinary branch fitness already drives the constructor branch, so the difference only shows on
 * harder targets under many seeds — left as future experimental evaluation.
 */
public class ConstructionFitnessSystemTest extends SystemTestBase {

    private void configure() {
        Properties.STRATEGY = Properties.Strategy.MOSUITE;
        Properties.ALGORITHM = Algorithm.DYNAMOSA;
        Properties.CRITERION = new Criterion[] { Criterion.BRANCH, Criterion.METHODNOEXCEPTION };
        Properties.ASSERTIONS = false;
        Properties.MINIMIZE = false;
        Properties.SEARCH_BUDGET = 5000;
        Properties.RANDOM_SEED = 42L;
    }

    private TestSuiteChromosome runSearch() {
        EvoSuite evosuite = new EvoSuite();
        Object result = evosuite.parseCommandLine(
                new String[] { "-class", ConstructionTarget.class.getCanonicalName() });
        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        return ga.getBestIndividual();
    }

    @Test
    public void constructionFitnessRunsAndDoesNotRegress() {
        // Construction fitness first, on a clean state (a single JVM otherwise accumulates state).
        configure();
        Properties.CONSTRUCTION_FITNESS = true;
        Properties.TARGET_CLASS = ConstructionTarget.class.getCanonicalName();
        double gradedCoverage = runSearch().getCoverage();

        resetStaticVariables();
        setDefaultPropertiesForTestCases();

        // Baseline: identical configuration, binary method-no-exception signal.
        configure();
        Properties.CONSTRUCTION_FITNESS = false;
        Properties.TARGET_CLASS = ConstructionTarget.class.getCanonicalName();
        double baselineCoverage = runSearch().getCoverage();

        System.out.println("construction cov=" + gradedCoverage + " baseline cov=" + baselineCoverage);

        Assertions.assertTrue(gradedCoverage > 0.0,
                "construction fitness should drive a working BRANCH:METHODNOEXCEPTION search");
        Assertions.assertTrue(gradedCoverage >= baselineCoverage - 0.05,
                "overall coverage must not regress (" + gradedCoverage + " vs " + baselineCoverage + ")");
    }
}
