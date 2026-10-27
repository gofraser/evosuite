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
package org.evosuite.testcase.execution;

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;

import java.util.HashSet;
import java.util.Set;

/**
 * Abstract base class of all execution observers.
 *
 * @author Gordon Fraser
 */
public abstract class ExecutionObserver {

    /**
     * The test case being monitored and executed.
     */
    protected static TestCase currentTest = null;

    /**
     * Setter method for current test case.
     *
     * @param test a {@link org.evosuite.testcase.TestCase} object.
     */
    public static void setCurrentTest(TestCase test) {
        currentTest = test;
    }

    /**
     * Getter method for current test case.
     *
     * @return a {@link org.evosuite.testcase.TestCase} object.
     */
    public static TestCase getCurrentTest() {
        return currentTest;
    }

    /**
     * Called immediately before a statement is executed. Does nothing by default.
     *
     * @param statement the statement.
     * @param scope the scope.
     */
    public void beforeStatement(Statement statement, Scope scope) {
        // do nothing
    }

    /**
     * After execution of a statement, the result is passed to the observer.
     *
     * @param statement a {@link org.evosuite.testcase.statements.Statement} object.
     * @param scope     a {@link org.evosuite.testcase.execution.Scope} object.
     * @param exception a {@link java.lang.Throwable} object.
     */
    public abstract void afterStatement(Statement statement, Scope scope,
                                        Throwable exception);

    /**
     * Allow observers to update the execution result at the end the execution of a test.
     * Does nothing by default.
     *
     * @param r the execution result.
     * @param s the scope of execution.
     */
    public void testExecutionFinished(ExecutionResult r, Scope s) {
        // do nothing
    }

    /**
     * Need a way to clear previously produced results. Does nothing by default.
     */
    public void clear() {
        // do nothing
    }

    /**
     * Determine the set of variables that somehow lead to this statement.
     *
     * @param statement a {@link org.evosuite.testcase.statements.Statement} object.
     * @return a {@link java.util.Set} object.
     */
    protected Set<VariableReference> getDependentVariables(Statement statement) {
        Set<VariableReference> dependencies = new HashSet<>();
        for (VariableReference var : statement.getVariableReferences()) {
            dependencies.add(var);
            dependencies.addAll(currentTest.getDependencies(var));
        }
        return dependencies;
    }
}
