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
package org.evosuite.coverage.io.input;

import org.evosuite.coverage.io.IOCoverageTestFitness;
import org.evosuite.testcase.execution.ExecutionResult;

import java.util.Collection;
import java.util.Set;

/**
 * Fitness function for evaluating individual test coverage of method input parameters.
 * Calculates a distance-based fitness value for each input coverage goal.
 *
 * @author Jose Miguel Rojas
 */
public class InputCoverageTestFitness extends IOCoverageTestFitness<InputCoverageGoal> {

    private static final long serialVersionUID = 6630097528288524492L;

    /**
     * Constructor - fitness is specific to a method.
     *
     * @param goal the coverage goal
     */
    public InputCoverageTestFitness(InputCoverageGoal goal) {
        super(goal, InputObserver.class, InputObserver::new);
    }

    /**
     * Gets the index of the argument that the goal covers.
     *
     * @return the argument index.
     */
    public int getArgIndex() {
        return goal.getArgIndex();
    }

    @Override
    protected String getCriterionName() {
        return "Input";
    }

    @Override
    protected Collection<Set<InputCoverageGoal>> getCoveredGoals(ExecutionResult result) {
        return result.getInputGoals().values();
    }

}
