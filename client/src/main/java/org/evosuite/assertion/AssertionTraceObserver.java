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

package org.evosuite.assertion;

import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.ExecutionObserver;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.FieldStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * <p>
 * Abstract AssertionTraceObserver class.
 * </p>
 *
 * @author Gordon Fraser
 */
public abstract class AssertionTraceObserver<T extends OutputTraceEntry> extends
        ExecutionObserver {

    /**
     * Constant <code>logger</code>.
     */
    protected static final Logger logger = LoggerFactory.getLogger(AssertionTraceObserver.class);

    protected OutputTrace<T> trace = new OutputTrace<>();

    protected boolean checkThread() {
        return ExecutionTracer.isThreadNeqCurrentThread();
    }

    /* (non-Javadoc)
     * @see org.evosuite.testcase.ExecutionObserver#output(int, java.lang.String)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void output(int position, String output) {
        // Default behavior is to ignore console output

    }

    /**
     * <p>
     * visitDependencies.
     * </p>
     *
     * @param statement a {@link org.evosuite.testcase.statements.Statement} object.
     * @param scope     a {@link org.evosuite.testcase.execution.Scope} object.
     */
    protected void visitDependencies(Statement statement, Scope scope) {
        visitDependencies(statement, scope, (VariableReference[]) null);
    }

    /**
     * Visit dependencies, optionally excluding specific variables (e.g. the
     * RHS and LHS of an assignment).
     */
    protected void visitDependencies(Statement statement, Scope scope,
                                     VariableReference... excludeVars) {
        Set<VariableReference> dependencies = currentTest.getDependencies(statement.getReturnValue());

        for (VariableReference var : dependencies) {
            if (var.isVoid()) {
                continue;
            }
            if (isExcluded(var, excludeVars)) {
                continue;
            }
            // No assertions on mocked objects
            if (statement.getTestCase().getStatement(var.getStPosition()) instanceof FunctionalMockStatement) {
                continue;
            }
            try {
                visit(statement, scope, var);
            } catch (CodeUnderTestException e) {
                // ignore
            }
        }
    }

    private boolean isExcluded(VariableReference var, VariableReference[] excludeVars) {
        if (excludeVars == null) {
            return false;
        }
        for (VariableReference ex : excludeVars) {
            if (ex != null && var.getStPosition() == ex.getStPosition()) {
                return true;
            }
        }
        return false;
    }


    /**
     * <p>
     * visitReturnValue.
     * </p>
     *
     * @param statement a {@link org.evosuite.testcase.statements.Statement} object.
     * @param scope     a {@link org.evosuite.testcase.execution.Scope} object.
     */
    protected void visitReturnValue(Statement statement, Scope scope) {
        if (statement.getReturnClass().equals(void.class)) {
            return;
        }

        // No need to assert anything about values just assigned
        if (statement.isAssignmentStatement()) {
            return;
        }

        try {
            visit(statement, scope, statement.getReturnValue());
        } catch (CodeUnderTestException e) {
            // ignore
        }

    }

    /**
     * <p>
     * visit.
     * </p>
     *
     * @param statement a {@link org.evosuite.testcase.statements.Statement} object.
     * @param scope     a {@link org.evosuite.testcase.execution.Scope} object.
     * @param var       a {@link org.evosuite.testcase.variable.VariableReference} object.
     * @throws org.evosuite.testcase.execution.CodeUnderTestException if any.
     */
    protected abstract void visit(Statement statement, Scope scope,
                                  VariableReference var) throws CodeUnderTestException;

    /* (non-Javadoc)
     * @see org.evosuite.testcase.ExecutionObserver#statement(org.evosuite.testcase.StatementInterface,
     * org.evosuite.testcase.Scope, java.lang.Throwable)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void afterStatement(Statement statement, Scope scope,
                                            Throwable exception) {
        //if(checkThread())
        //    return;

        // No assertions are created for mock statements
        if (statement instanceof FunctionalMockStatement) {
            return;
        }

        // No assertions for primitives
        if (statement instanceof PrimitiveStatement<?>) {
            return;
        }

        // By default, no assertions are created for statements that threw exceptions
        if (exception != null) {
            return;
        }

        if (statement instanceof FieldStatement) {
            // Only need to check returnvalue here, nothing else can have changed
            visitReturnValue(statement, scope);
        } else if (statement.isAssignmentStatement()) {
            // Assignment modifies the owner object's state, so visit its
            // dependencies — but exclude the RHS value (not modified) and
            // the LHS field reference (value was just assigned, already known)
            AssignmentStatement assign = (AssignmentStatement) statement;
            visitDependencies(statement, scope,
                    assign.getValue(), statement.getReturnValue());
        } else {
            visitDependencies(statement, scope);
        }
    }

    /* (non-Javadoc)
     * @see org.evosuite.testcase.ExecutionObserver#beforeStatement(org.evosuite.testcase.StatementInterface,
     * org.evosuite.testcase.Scope)
     */
    @Override
    public synchronized void beforeStatement(Statement statement, Scope scope) {
        // Do nothing
    }

    /* (non-Javadoc)
     * @see org.evosuite.testcase.ExecutionObserver#clear()
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void clear() {
        //if(!checkThread())
        //    return;

        trace.clear();
    }

    /**
     * <p>
     * Getter for the field <code>trace</code>.
     * </p>
     *
     * @return a {@link org.evosuite.assertion.OutputTrace} object.
     */
    public synchronized OutputTrace<T> getTrace() {
        return trace.clone();
    }

    /**
     * Returns the class of the trace entry handled by this observer.
     *
     * @return the trace entry class
     */
    public abstract Class<T> getTraceEntryClass();

}
