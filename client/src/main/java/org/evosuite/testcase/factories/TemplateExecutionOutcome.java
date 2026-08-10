/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors
 */
package org.evosuite.testcase.factories;

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
            org.evosuite.testcase.statements.Statement entry = test.findTargetExecutableStatement();
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
