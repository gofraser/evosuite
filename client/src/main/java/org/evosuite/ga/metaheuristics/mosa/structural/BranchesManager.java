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
package org.evosuite.ga.metaheuristics.mosa.structural;

import org.evosuite.testcase.TestFitnessFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * This Class manages the goals to consider during the search according to their structural
 * dependencies
 *
 * @author Annibale Panichella, Fitsum Meshesha Kifetew
 */
public class BranchesManager extends StructuralGoalManager {

    private static final Logger logger = LoggerFactory.getLogger(BranchesManager.class);
    private static final long serialVersionUID = 6453893627503159175L;

    /**
     * Constructor used to initialize the set of uncovered goals, and the initial set
     * of goals to consider as initial contrasting objectives
     *
     * @param fitnessFunctions List of all FitnessFunction<T>
     */
    public BranchesManager(List<TestFitnessFunction> fitnessFunctions) {
        super(fitnessFunctions);

        graph = new BranchFitnessGraph(new HashSet<>(fitnessFunctions));

        // initialize current goals
        this.currentGoals.addAll(graph.getRootBranches());

        // initialize the maps
        initializeMaps(new HashSet<>(fitnessFunctions));
    }

    @Override
    protected Set<TestFitnessFunction> getDependencies(TestFitnessFunction fitnessFunction) {
        return graph.getStructuralChildren(fitnessFunction);
    }
}
