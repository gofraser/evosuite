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
package org.evosuite.coverage.io.output;

import org.evosuite.coverage.io.IOCoverageTestFitness;
import org.evosuite.testcase.execution.ExecutionResult;

import java.util.Collection;
import java.util.Set;

/**
 * Fitness function for evaluating individual test coverage of method return values.
 * Calculates a distance-based fitness value for each output coverage goal.
 *
 * @author Jose Miguel Rojas
 */
public class OutputCoverageTestFitness extends IOCoverageTestFitness<OutputCoverageGoal> {

    private static final long serialVersionUID = 1383064944691491355L;

    /**
     * Constructor - fitness is specific to a method.
     *
     * @param goal the coverage goal
     */
    public OutputCoverageTestFitness(OutputCoverageGoal goal) {
        super(goal, OutputObserver.class, OutputObserver::new);
    }

    @Override
    protected String getCriterionName() {
        return "Output";
    }

    @Override
    protected Collection<Set<OutputCoverageGoal>> getCoveredGoals(ExecutionResult result) {
        return result.getOutputGoals().values();
    }

}
