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
package org.evosuite.testcase.factories;

import org.evosuite.testcase.synthesizer.TargetContext;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;

/**
 * Result of executing a synthesized template.
 *
 * <p>Entry reachability is a property of the generated test and its execution trace. Keeping it
 * here avoids coupling template acceptance to a GA fitness implementation.
 */
final class TemplateExecutionOutcome {

    private final ExecutionResult executionResult;
    private final boolean reachedEntry;
    private final double workingGoalFitness;

    TemplateExecutionOutcome(
            ExecutionResult executionResult, boolean reachedEntry, double workingGoalFitness) {
        this.executionResult = executionResult;
        this.reachedEntry = reachedEntry;
        this.workingGoalFitness = workingGoalFitness;
    }

    static TemplateExecutionOutcome from(
            TestCase test, ExecutionResult result, double workingGoalFitness) {
        boolean reached = false;
        if (test != null && result != null && !result.hasTimeout()
                && result.noThrownExceptions()) {
            org.evosuite.testcase.statements.Statement entry = TargetContext.findTargetExecutableStatement(test);
            reached = entry != null && result.getExecutedStatements() > entry.getPosition();
        }
        return new TemplateExecutionOutcome(result, reached, workingGoalFitness);
    }

    ExecutionResult getExecutionResult() {
        return executionResult;
    }

    boolean reachedEntry() {
        return reachedEntry;
    }

    double workingGoalFitness() {
        return workingGoalFitness;
    }
}
