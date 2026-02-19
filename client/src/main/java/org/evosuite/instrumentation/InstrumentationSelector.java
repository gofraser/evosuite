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

package org.evosuite.instrumentation;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.instrumentation.coverage.BranchInstrumentation;
import org.evosuite.instrumentation.coverage.DefUseInstrumentation;
import org.evosuite.instrumentation.coverage.MethodInstrumentation;
import org.evosuite.instrumentation.coverage.MutationInstrumentation;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.utils.ArrayUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Responsible for selecting the appropriate {@link MethodInstrumentation}s
 * based on the current configuration and criteria.
 */
public class InstrumentationSelector {

    /**
     * Determines which method instrumentations should be applied to a given method.
     *
     * @param className  The name of the class containing the method.
     * @param methodName The name (and descriptor) of the method.
     * @return A list of MethodInstrumentation instances to apply.
     */
    public static List<MethodInstrumentation> getInstrumentations(String className, String methodName) {
        List<MethodInstrumentation> instrumentations = new ArrayList<>();

        if (DependencyAnalysis.shouldInstrument(className, methodName)) {
            if (isDefUse()) {
                instrumentations.add(new BranchInstrumentation());
                instrumentations.add(new DefUseInstrumentation());
            } else if (isMutationCriterion()) {
                instrumentations.add(new BranchInstrumentation());
                instrumentations.add(new MutationInstrumentation());
            } else {
                instrumentations.add(new BranchInstrumentation());
            }
        }
        return instrumentations;
    }

    /**
     * Checks if any mutation-related criterion is active that requires
     * return value instrumentation (used by {@code ExecutionPathClassAdapter}).
     * Note: ONLYMUTATION is deliberately excluded to match the original behavior.
     */
    public static boolean isMutation() {
        return ArrayUtil.contains(Properties.CRITERION, Criterion.MUTATION)
                || ArrayUtil.contains(Properties.CRITERION, Criterion.STRONGMUTATION)
                || ArrayUtil.contains(Properties.CRITERION, Criterion.WEAKMUTATION);
    }

    /**
     * Checks if any mutation-related criterion is active (including ONLYMUTATION),
     * used for selecting {@link MutationInstrumentation} in the CFG analysis.
     */
    public static boolean isMutationCriterion() {
        return isMutation()
                || ArrayUtil.contains(Properties.CRITERION, Criterion.ONLYMUTATION);
    }

    /**
     * Checks if any Def-Use-related criterion is active.
     */
    public static boolean isDefUse() {
        return ArrayUtil.contains(Properties.CRITERION, Criterion.DEFUSE)
                || ArrayUtil.contains(Properties.CRITERION, Criterion.ALLDEFS);
    }
}
