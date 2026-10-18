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

import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.exception.ExceptionCoverageTestFitness;
import org.evosuite.coverage.io.input.InputCoverageTestFitness;
import org.evosuite.coverage.io.output.OutputCoverageTestFitness;
import org.evosuite.coverage.line.LineCoverageTestFitness;
import org.evosuite.coverage.method.MethodCoverageTestFitness;
import org.evosuite.coverage.method.MethodNoExceptionCoverageTestFitness;
import org.evosuite.coverage.method.MethodTraceCoverageTestFitness;
import org.evosuite.coverage.mutation.Mutation;
import org.evosuite.coverage.mutation.MutationTestFitness;
import org.evosuite.coverage.statement.StatementCoverageTestFitness;
import org.evosuite.llm.prompt.BranchGoalView;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.setup.CallContext;
import org.evosuite.testcase.TestFitnessFunction;

/**
 * Builds a stable identifier for a {@link TestFitnessFunction}, of the form
 * {@code <kind>|<targetClass>|<targetMethod>|<discriminator>}, from typed
 * accessors rather than {@link Object#toString()} for the goal kinds known
 * below. Display strings can change independently of the goal they describe
 * (wording tweaks, formatting fixes); an id built from typed accessors does
 * not, so it is safe to use as a persistent key (e.g. the repeated-injection
 * memory, or a disruption-analysis CSV column).
 */
public final class GoalId {

    private GoalId() {
    }

    /** The stable identifier for {@code goal}; see the class javadoc for the format. */
    public static String of(TestFitnessFunction goal) {
        if (goal == null) {
            return "";
        }
        BranchGoalView branchGoal = BranchGoalView.of(goal);
        if (branchGoal != null) {
            // CBranch and IBranch are scoped to a call context: two goals on the same
            // branch/polarity but different call sites are different goals and need different ids.
            return format(goal, branchDiscriminator(branchGoal.getBranch(), branchGoal.getValue())
                    + (branchGoal.isContextScoped() ? contextSuffix(branchGoal.getContext()) : ""));
        }
        if (goal instanceof LineCoverageTestFitness) {
            return format(goal, "line=" + ((LineCoverageTestFitness) goal).getLine());
        }
        if (goal instanceof StatementCoverageTestFitness) {
            BytecodeInstruction instruction = ((StatementCoverageTestFitness) goal).getGoalInstruction();
            return format(goal, "stmt=" + (instruction == null ? "" : instruction.getInstructionId()));
        }
        if (goal instanceof MutationTestFitness) {
            Mutation mutation = ((MutationTestFitness) goal).getMutation();
            return format(goal, "mutation=" + (mutation == null ? "" : mutation.getId()));
        }
        if (goal instanceof MethodCoverageTestFitness
                || goal instanceof MethodNoExceptionCoverageTestFitness
                || goal instanceof MethodTraceCoverageTestFitness) {
            return format(goal, "");
        }
        if (goal instanceof ExceptionCoverageTestFitness) {
            // ExceptionCoverageTestFitness exposes no public accessor for its ExceptionType;
            // getKey() is a typed (non-toString) accessor that already folds it in.
            return format(goal, "exception=" + ((ExceptionCoverageTestFitness) goal).getKey());
        }
        if (goal instanceof InputCoverageTestFitness) {
            InputCoverageTestFitness f = (InputCoverageTestFitness) goal;
            return format(goal, f.getType() + ":" + f.getValueDescriptor());
        }
        if (goal instanceof OutputCoverageTestFitness) {
            OutputCoverageTestFitness f = (OutputCoverageTestFitness) goal;
            return format(goal, f.getType() + ":" + f.getValueDescriptor());
        }
        return format(goal, "text=" + goal);
    }

    private static String branchDiscriminator(Branch branch, boolean value) {
        String branchPart = branch == null ? "root" : String.valueOf(branch.getActualBranchId());
        return "branch=" + branchPart + ":" + value;
    }

    private static String contextSuffix(CallContext context) {
        return "|ctx=" + (context == null ? "" : context.toString());
    }

    private static String format(TestFitnessFunction goal, String discriminator) {
        return kindOf(goal.getClass()) + "|" + nullToEmpty(goal.getTargetClass())
                + "|" + nullToEmpty(goal.getTargetMethod()) + "|" + discriminator;
    }

    /** The concrete class's simple name, minus a trailing "CoverageTestFitness"/"TestFitness". */
    private static String kindOf(Class<?> clazz) {
        String simple = clazz.getSimpleName();
        if (simple.endsWith("CoverageTestFitness")) {
            return simple.substring(0, simple.length() - "CoverageTestFitness".length());
        }
        if (simple.endsWith("TestFitness")) {
            return simple.substring(0, simple.length() - "TestFitness".length());
        }
        return simple;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
