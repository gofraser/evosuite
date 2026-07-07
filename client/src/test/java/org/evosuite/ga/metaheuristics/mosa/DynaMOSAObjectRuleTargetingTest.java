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
package org.evosuite.ga.metaheuristics.mosa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageGoal;
import org.evosuite.coverage.branch.BranchFitness;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class DynaMOSAObjectRuleTargetingTest {

    @Test
    public void frontierIntersectionKeepsFullUncoveredOrder() {
        Branch b1 = mock(Branch.class);
        Branch b2 = mock(Branch.class);
        Branch b3 = mock(Branch.class);

        List<Branch> intersection = DynaMOSA.frontierCandidateBranches(
                Arrays.asList(b1, b2, b3),
                Arrays.asList(branchGoal(b3), nonBranchGoal(), branchGoal(b1)));

        assertEquals(Arrays.asList(b1, b3), intersection);
    }

    @Test
    public void frontierIntersectionIsEmptyWhenNoCurrentBranchMatches() {
        Branch uncovered = mock(Branch.class);
        Branch other = mock(Branch.class);

        List<Branch> intersection = DynaMOSA.frontierCandidateBranches(
                Collections.singletonList(uncovered),
                Collections.singletonList(branchGoal(other)));

        assertEquals(Collections.emptyList(), intersection);
    }

    private static TestFitnessFunction branchGoal(Branch branch) {
        TestFitnessFunction fitness = mock(TestFitnessFunction.class,
                Mockito.withSettings().extraInterfaces(BranchFitness.class));
        BranchCoverageGoal goal = mock(BranchCoverageGoal.class);
        when(goal.getBranch()).thenReturn(branch);
        when(((BranchFitness) fitness).getBranchGoal()).thenReturn(goal);
        return fitness;
    }

    private static TestFitnessFunction nonBranchGoal() {
        return mock(TestFitnessFunction.class);
    }
}
