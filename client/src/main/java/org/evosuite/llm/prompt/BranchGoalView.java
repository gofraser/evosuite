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
package org.evosuite.llm.prompt;

import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.coverage.branch.OnlyBranchCoverageTestFitness;
import org.evosuite.coverage.cbranch.CBranchTestFitness;
import org.evosuite.coverage.ibranch.IBranchTestFitness;
import org.evosuite.setup.CallContext;
import org.evosuite.testcase.TestFitnessFunction;

/**
 * The {@code (branch, polarity, call context)} triple behind any of the four
 * branch goal types — Branch, OnlyBranch, CBranch and IBranch — so the LLM
 * prompt and telemetry code dispatches over them once, here, instead of each
 * consumer carrying its own {@code instanceof} ladder.
 */
public final class BranchGoalView {

    private final Branch branch;
    private final boolean value;
    private final boolean contextScoped;
    private final CallContext context;

    private BranchGoalView(Branch branch, boolean value, boolean contextScoped, CallContext context) {
        this.branch = branch;
        this.value = value;
        this.contextScoped = contextScoped;
        this.context = context;
    }

    /** The view of {@code goal}, or null when it is not a branch goal. */
    public static BranchGoalView of(TestFitnessFunction goal) {
        if (goal instanceof BranchCoverageTestFitness) {
            BranchCoverageTestFitness f = (BranchCoverageTestFitness) goal;
            return new BranchGoalView(f.getBranch(), f.getValue(), false, null);
        }
        if (goal instanceof OnlyBranchCoverageTestFitness) {
            OnlyBranchCoverageTestFitness f = (OnlyBranchCoverageTestFitness) goal;
            return new BranchGoalView(f.getBranch(), f.getValue(), false, null);
        }
        if (goal instanceof CBranchTestFitness) {
            CBranchTestFitness f = (CBranchTestFitness) goal;
            return new BranchGoalView(f.getBranch(), f.getValue(), true, f.getContext());
        }
        if (goal instanceof IBranchTestFitness) {
            IBranchTestFitness f = (IBranchTestFitness) goal;
            return new BranchGoalView(f.getBranch(), f.getValue(), true, f.getContext());
        }
        return null;
    }

    /** True if {@code goal} is one of the four branch goal types. */
    public static boolean isBranchGoal(TestFitnessFunction goal) {
        return of(goal) != null;
    }

    /** The branch, or null for a branchless-method root goal. */
    public Branch getBranch() {
        return branch;
    }

    /** Whether the goal is the jump being taken (true) or falling through (false). */
    public boolean getValue() {
        return value;
    }

    /** True for the goal types scoped to a call site (CBranch, IBranch), even when that context is null. */
    public boolean isContextScoped() {
        return contextScoped;
    }

    /** The call context of a CBranch/IBranch goal; null for the context-free goal types. */
    public CallContext getContext() {
        return context;
    }

    /** Whether the goal is scoped to a specific call site. */
    public boolean hasNonEmptyContext() {
        return context != null && !context.isEmpty();
    }
}
