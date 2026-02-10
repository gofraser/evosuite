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
package org.evosuite.coverage.ambiguity;

import org.evosuite.coverage.line.LineCoverageTestFitness;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;

import java.util.*;

/**
 * Test suite fitness function for ambiguity coverage.
 *
 * @author José Campos
 */
public class AmbiguityCoverageSuiteFitness extends TestSuiteFitnessFunction {

    private static final long serialVersionUID = -2721073655092419390L;


    private final Set<Integer> goals;


    public AmbiguityCoverageSuiteFitness() {

        this.goals = new LinkedHashSet<>();
        for (LineCoverageTestFitness goal : AmbiguityCoverageFactory.getGoals()) {
            this.goals.add(goal.getLine());
        }
    }

    @Override
    public double getFitness(TestSuiteChromosome suite) {

        List<StringBuilder> transposedMatrix = new ArrayList<>(AmbiguityCoverageFactory.getTransposedMatrix());
        List<Set<Integer>> coveredLines = new ArrayList<>();

        // Execute test cases and collect the covered lines
        List<ExecutionResult> results = runTestSuite(suite);
        for (ExecutionResult result : results) {
            coveredLines.add(result.getTrace().getCoveredLines());
        }

        Map<String, Integer> groups = new HashMap<>();
        int goalIndex = 0;

        for (Integer goal : this.goals) {
            StringBuffer str = null;

            if (transposedMatrix.size() > goalIndex) {
                str = new StringBuffer(transposedMatrix.get(goalIndex).length() + coveredLines.size());
                str.append(transposedMatrix.get(goalIndex));
            } else {
                str = new StringBuffer(coveredLines.size());
            }

            for (Set<Integer> covered : coveredLines) {
                str.append(covered.contains(goal) ? "1" : "0");
            }

            if (!groups.containsKey(str.toString())) {
                // in the beginning they are ambiguity, so they belong to the same group '1'
                groups.put(str.toString(), 1);
            } else {
                groups.put(str.toString(), groups.get(str.toString()) + 1);
            }

            goalIndex++;
        }

        //double fitness = AmbiguityCoverageFactory.getAmbiguity(this.goals.size(), groups) * 1.0 
        // / AmbiguityCoverageFactory.getMaxAmbiguityScore();
        double fitness = TestFitnessFunction.normalize(
                AmbiguityCoverageFactory.getAmbiguity(this.goals.size(), groups));
        updateIndividual(suite, fitness);

        return fitness;
    }
}
