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
package org.evosuite.ga.archive;

import org.evosuite.Properties;
import org.evosuite.coverage.FitnessFunctions;
import org.evosuite.coverage.ibranch.IBranchTestFitness;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.utils.ArrayUtil;

public final class ArchiveUtils {

    /**
     * Checks whether a specific goal (i.e., a {@link org.evosuite.testcase.TestFitnessFunction}
     * object) is of an enabled criterion. A criterion is considered enabled if and only if defined in
     * {@link org.evosuite.Properties.CRITERION}.
     *
     * @param goal a {@link org.evosuite.testcase.TestFitnessFunction} object
     * @return true if criterion of goal is enabled, false otherwise
     */
    public static boolean isCriterionEnabled(FitnessFunction<TestChromosome> goal) {
        for (Properties.Criterion criterion : Properties.CRITERION) {
            if (FitnessFunctions.getTestFitnessFunctionClass(criterion).isInstance(goal)) {
                return true;
            }
        }
        if (ArrayUtil.contains(Properties.SECONDARY_OBJECTIVE, Properties.SecondaryObjective.IBRANCH)) {
            return goal instanceof IBranchTestFitness;
        }

        return false;
    }
}
