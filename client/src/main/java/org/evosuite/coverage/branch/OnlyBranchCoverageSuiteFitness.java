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
package org.evosuite.coverage.branch;

import org.evosuite.Properties;
import org.evosuite.ga.archive.Archive;

import java.util.List;

/**
 * Fitness function for a whole test suite for all branches, without the
 * requirement to call every method.
 *
 * @author Gordon Fraser, Jose Miguel Rojas
 */
public class OnlyBranchCoverageSuiteFitness extends BranchCoverageSuiteFitness {

    private static final long serialVersionUID = 2991632394620406243L;

    @Override
    protected int countBranches(ClassLoader classLoader, String prefix) {
        if (Properties.TARGET_METHOD.isEmpty()) {
            return super.countBranches(classLoader, prefix);
        }
        return BranchPool.getInstance(classLoader).getBranchCountForMethod(prefix, Properties.TARGET_METHOD);
    }

    @Override
    protected boolean includesMethodGoals() {
        return false;
    }

    /**
     * Initialize the set of known coverage goals.
     */
    @Override
    protected void determineCoverageGoals(boolean updateArchive) {
        List<OnlyBranchCoverageTestFitness> goals = new OnlyBranchCoverageFactory().getCoverageGoals();
        for (OnlyBranchCoverageTestFitness goal : goals) {
            if (updateArchive && Properties.TEST_ARCHIVE) {
                Archive.getArchiveInstance().addTarget(goal);
            }

            branchesId.add(goal.getBranch().getActualBranchId());
            if (goal.getBranchExpressionValue()) {
                branchCoverageTrueMap.put(goal.getBranch().getActualBranchId(), goal);
            } else {
                branchCoverageFalseMap.put(goal.getBranch().getActualBranchId(), goal);
            }
        }
    }
}
