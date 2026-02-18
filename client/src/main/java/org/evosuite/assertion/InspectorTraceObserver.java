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

import org.evosuite.runtime.mock.EvoSuiteMock;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;

import java.util.List;
import java.util.concurrent.TimeoutException;

public class InspectorTraceObserver extends AssertionTraceObserver<InspectorTraceEntry> {

    /* (non-Javadoc)
     * @see org.evosuite.assertion.AssertionTraceObserver#visit(org.evosuite.testcase.StatementInterface,
     * org.evosuite.testcase.Scope, org.evosuite.testcase.VariableReference)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    protected void visit(Statement statement, Scope scope, VariableReference var) {
        // TODO: Check the variable class is complex?

        // We don't want inspector checks on string constants
        Statement declaringStatement = currentTest.getStatement(var.getStPosition());
        if (declaringStatement instanceof PrimitiveStatement<?>) {
            return;
        }

        if (statement.isAssignmentStatement() && statement.getReturnValue().isArrayIndex()) {
            return;
        }

        if (statement instanceof ConstructorStatement) {
            if (statement.getReturnValue().isWrapperType()
                    || statement.getReturnValue().isAssignableTo(EvoSuiteMock.class)) {
                return;
            }
        }

        if (var.isPrimitive() || var.isString() || var.isWrapperType()) {
            return;
        }

        logger.debug("Checking for inspectors of " + var + " at statement "
                + statement.getPosition());
        List<Inspector> inspectors = InspectorManager.getInstance().getInspectors(var.getVariableClass());

        InspectorTraceEntry entry = new InspectorTraceEntry(var);

        for (Inspector i : inspectors) {

            // No inspectors from java.lang.Object
            if (i.getMethod().getDeclaringClass().equals(Object.class)) {
                continue;
            }

            try {
                Object target = var.getObject(scope);
                if (target != null) {

                    if (StringValueFilter.isMockitoProxy(target)) {
                        return;
                    }

                    Object value = i.getValue(target);
                    logger.debug("Inspector " + i.getMethodCall() + " is: " + value);

                    if (value instanceof String && StringValueFilter.shouldFilter((String) value, target)) {
                        continue;
                    }

                    entry.addValue(i, value);
                }
            } catch (Exception e) {
                if (e instanceof TimeoutException) {
                    logger.debug("Timeout during inspector call - deactivating inspector "
                            + i.getMethodCall());
                    InspectorManager.getInstance().removeInspector(var.getVariableClass(), i);
                }
                logger.debug("Exception " + e + " / " + e.getCause());
                if (e.getCause() != null
                        && !e.getCause().getClass().equals(NullPointerException.class)) {
                    logger.debug("Exception during call to inspector: " + e + " - "
                            + e.getCause());
                }
            }
        }
        // Also process chained inspectors (e.g., getList().size())
        List<ChainedInspector> chainedInspectors = InspectorManager.getInstance()
                .getChainedInspectors(var.getVariableClass());

        for (ChainedInspector ci : chainedInspectors) {

            if (ci.getOuterMethod().getDeclaringClass().equals(Object.class)) {
                continue;
            }

            try {
                Object target = var.getObject(scope);
                if (target != null) {

                    if (StringValueFilter.isMockitoProxy(target)) {
                        break;
                    }

                    Object value = ci.getValue(target);
                    if (value == null) {
                        continue;
                    }
                    logger.debug("Chained inspector " + ci.getMethodCall() + " is: " + value);

                    if (value instanceof String && StringValueFilter.shouldFilter((String) value, target)) {
                        continue;
                    }

                    entry.addValue(ci, value);
                }
            } catch (Exception e) {
                if (e instanceof TimeoutException) {
                    logger.debug("Timeout during chained inspector call - deactivating "
                            + ci.getMethodCall());
                }
                logger.debug("Exception " + e + " / " + e.getCause());
            }
        }

        logger.debug("Found " + entry.size() + " inspectors for " + var
                + " at statement " + statement.getPosition());

        trace.addEntry(statement.getPosition(), var, entry);
    }

    @Override
    public void testExecutionFinished(ExecutionResult r, Scope s) {
        // do nothing
    }

    @Override
    public Class<InspectorTraceEntry> getTraceEntryClass() {
        return InspectorTraceEntry.class;
    }

}
