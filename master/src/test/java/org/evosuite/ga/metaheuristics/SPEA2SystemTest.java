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
package org.evosuite.ga.metaheuristics;

import org.evosuite.EvoSuite;
import org.evosuite.Properties;
import org.evosuite.Properties.Algorithm;
import org.evosuite.Properties.Criterion;
import org.evosuite.Properties.StoppingCondition;
import org.evosuite.coverage.ambiguity.AmbiguityCoverageFactory;
import org.evosuite.coverage.rho.RhoCoverageFactory;
import org.evosuite.SystemTestBase;
import org.evosuite.ga.Chromosome;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.problems.metrics.Spacing;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.examples.with.different.packagename.BMICalculator;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEA2SystemTest.
 *
 * @author José Campos
 */
public class SPEA2SystemTest extends SystemTestBase {

    @BeforeEach
    public void reset() {
        RhoCoverageFactory.getGoals().clear();
        AmbiguityCoverageFactory.getGoals().clear();
    }

    public double[][] test(String targetClass) {
        Properties.CRITERION = new Criterion[2];
        Properties.CRITERION[0] = Criterion.RHO;
        Properties.CRITERION[1] = Criterion.AMBIGUITY;

        Properties.ALGORITHM = Algorithm.SPEA2;
        Properties.SELECTION_FUNCTION = Properties.SelectionFunction.BINARY_TOURNAMENT;
        Properties.STOPPING_CONDITION = StoppingCondition.MAXGENERATIONS;
        Properties.MINIMIZE = false;

        EvoSuite evosuite = new EvoSuite();

        Properties.TARGET_CLASS = targetClass;

        String[] command = new String[]{"-generateSuite", "-class", targetClass};

        Object result = evosuite.parseCommandLine(command);
        Assertions.assertNotNull(result);

        GeneticAlgorithm<TestSuiteChromosome> ga = (GeneticAlgorithm<TestSuiteChromosome>) getGAFromResult(result);

        final FitnessFunction<TestSuiteChromosome> branch = ga.getFitnessFunctions().get(0);
        final FitnessFunction<TestSuiteChromosome> rho = ga.getFitnessFunctions().get(1);

        List<TestSuiteChromosome> population = new ArrayList<>(ga.getBestIndividuals());

        double[][] front = new double[population.size()][2];
        for (int i = 0; i < population.size(); i++) {
            Chromosome<TestSuiteChromosome> c = population.get(i);
            front[i][0] = c.getFitness(branch);
            front[i][1] = c.getFitness(rho);
        }

        return front;
    }

    @Test
    public void minimalSpacing() {
        String targetClass = BMICalculator.class.getCanonicalName();

        Properties.POPULATION = 10;
        Properties.SEARCH_BUDGET = 40;
        double[][] front = test(targetClass);

        Spacing sp = new Spacing();
        double[] max = sp.getMaximumValues(front);
        double[] min = sp.getMinimumValues(front);

        double[][] frontNormalized = sp.getNormalizedFront(front, max, min);
        double spacing = sp.evaluate(frontNormalized);
        assertTrue(spacing <= 0.3, "Expected low spacing for a simple benchmark front, but got " + spacing);
    }
}
