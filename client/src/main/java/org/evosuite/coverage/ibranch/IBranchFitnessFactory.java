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

package org.evosuite.coverage.ibranch;

import org.evosuite.coverage.branch.BranchCoverageFactory;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.setup.CallContext;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.callgraph.CallGraph;
import org.evosuite.testsuite.AbstractFitnessFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Create the IBranchTestFitness goals for the class under test.
 *
 * @author mattia, Gordon Fraser
 */
public class IBranchFitnessFactory extends AbstractFitnessFactory<IBranchTestFitness> {

    private static final Logger logger = LoggerFactory.getLogger(IBranchFitnessFactory.class);

    /**
     * {@inheritDoc}
     */
    @Override
    public List<IBranchTestFitness> getCoverageGoals() {
        Set<IBranchTestFitness> goals = new HashSet<>();

        // retrieve set of branches
        BranchCoverageFactory branchFactory = new BranchCoverageFactory();
        List<BranchCoverageTestFitness> branchGoals = branchFactory.getCoverageGoalsForAllKnownClasses();

        CallGraph callGraph = DependencyAnalysis.getCallGraph();

        // try to find all occurrences of this branch in the call tree
        for (BranchCoverageTestFitness branchGoal : branchGoals) {
            logger.debug("Adding context branches for {}", branchGoal);
            for (CallContext context : callGraph.getAllContextsFromTargetClass(branchGoal.getClassName(),
                    branchGoal.getMethod())) {
                //if is not possible to reach this branch from the target class, continue.
                if (context.isEmpty()) {
                    continue;
                }
                goals.add(new IBranchTestFitness(branchGoal.getBranchGoal(), context));
            }
        }
        logger.info("Created {} goals", goals.size());

        return new ArrayList<>(goals);
    }
}
