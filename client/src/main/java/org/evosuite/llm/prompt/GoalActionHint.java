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

import org.evosuite.coverage.exception.ExceptionCoverageTestFitness;
import org.evosuite.coverage.io.input.InputCoverageTestFitness;
import org.evosuite.coverage.io.output.OutputCoverageTestFitness;
import org.evosuite.coverage.line.LineCoverageTestFitness;
import org.evosuite.coverage.method.MethodCoverageTestFitness;
import org.evosuite.coverage.method.MethodNoExceptionCoverageTestFitness;
import org.evosuite.coverage.mutation.MutationTestFitness;
import org.evosuite.coverage.statement.StatementCoverageTestFitness;
import org.evosuite.testcase.TestFitnessFunction;

/**
 * The action a prompt suggests for a target goal that has no diagnostic card
 * (cards carry their own, more specific hint). One hint per goal family; the
 * single home of these wordings.
 */
public final class GoalActionHint {

    static final String BRANCH = "Keep the call sequence that already reaches this predicate and change the "
            + "smallest input or receiver-state condition so it takes the stated outcome.";
    static final String LINE = "Extend or redirect the existing call sequence so execution reaches this line.";
    static final String METHOD = "Invoke this method directly on a valid receiver with valid arguments.";
    static final String METHOD_NO_EXCEPTION = "Invoke this method with arguments and receiver state for which it "
            + "returns normally instead of throwing.";
    static final String EXCEPTION = "Drive this call into the stated exceptional condition: an invalid argument, "
            + "an unexpected receiver state, or a failing collaborator.";
    static final String MUTATION = "Choose inputs for which the mutated expression at this location yields a "
            + "different observable result than the original code.";
    static final String INPUT = "Call the method with an argument in the value regime this goal names.";
    static final String OUTPUT = "Call the method so its return value falls into the regime this goal names.";
    static final String DEFAULT = "Reach this goal with the smallest change to the existing test.";

    private GoalActionHint() {}

    public static String forGoal(TestFitnessFunction goal) {
        if (BranchGoalView.isBranchGoal(goal)) {
            return BRANCH;
        }
        if (goal instanceof LineCoverageTestFitness || goal instanceof StatementCoverageTestFitness) {
            return LINE;
        }
        if (goal instanceof MethodNoExceptionCoverageTestFitness) {
            return METHOD_NO_EXCEPTION;
        }
        if (goal instanceof MethodCoverageTestFitness) {
            return METHOD;
        }
        if (goal instanceof ExceptionCoverageTestFitness) {
            return EXCEPTION;
        }
        if (goal instanceof MutationTestFitness) {
            return MUTATION;
        }
        if (goal instanceof InputCoverageTestFitness) {
            return INPUT;
        }
        if (goal instanceof OutputCoverageTestFitness) {
            return OUTPUT;
        }
        return DEFAULT;
    }
}
