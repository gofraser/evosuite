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
package org.evosuite.llm.search;

import org.evosuite.llm.prompt.GoalDescriptionMapper;
import org.evosuite.testcase.TestFitnessFunction;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-control-dependency signal: how many times the dominating branch fired in
 * which direction. Powers {@link ProblemCardType#CDG_BOTTLENECK}.
 */
final class CdgDependencySignal {
    final String methodKey;
    final int branchId;
    final boolean desiredValue;
    final GoalDescriptionMapper.BranchTarget branchTarget;
    final List<TestFitnessFunction> relatedGoals = new ArrayList<>();
    int desiredHits;
    int oppositeHits;
    int predicateExecutions;

    CdgDependencySignal(String methodKey,
                        int branchId,
                        boolean desiredValue,
                        GoalDescriptionMapper.BranchTarget branchTarget) {
        this.methodKey = methodKey;
        this.branchId = branchId;
        this.desiredValue = desiredValue;
        this.branchTarget = branchTarget;
    }

    String signalKey() {
        return methodKey + "#" + branchId + "#" + desiredValue;
    }
}
