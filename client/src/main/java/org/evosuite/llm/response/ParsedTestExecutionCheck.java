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
package org.evosuite.llm.response;

import org.evosuite.Properties;
import org.evosuite.assertion.Assertion;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testparser.ParseResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.evosuite.llm.response.MockRuntimeExceptions.isRaisedByEvoSuiteRuntime;
import static org.evosuite.llm.response.MockRuntimeExceptions.unmockedExceptionClass;

/**
 * Runs a parsed LLM test and decides whether it failed: an exception the test
 * does not declare, or, when assertion failures are repaired, an assertion that
 * does not hold or was never reached. The verdict carries the formatted feedback.
 */
final class ParsedTestExecutionCheck {

    private final TestRepairLoop.TestExecutor testExecutor;
    private final boolean repairOnAssertionFailures;
    private final ExecutionFailureFormatter failureFormatter;

    ParsedTestExecutionCheck(TestRepairLoop.TestExecutor testExecutor,
                             boolean repairOnAssertionFailures,
                             ExecutionFailureFormatter failureFormatter) {
        this.testExecutor = testExecutor;
        this.repairOnAssertionFailures = repairOnAssertionFailures;
        this.failureFormatter = failureFormatter;
    }

    /**
     * Whether {@code thrown} at {@code position} is a failure the repair loop acts on: an
     * exception the test does not declare, and an assertion failure only when repairing those.
     */
    boolean countsAsExecutionFailure(TestCase testCase, String expectedExceptionClass,
                                             Integer position, Throwable thrown) {
        return isUndeclaredException(testCase, expectedExceptionClass, position, thrown)
                && (repairOnAssertionFailures || !(thrown instanceof AssertionError));
    }

    ExecutionFailureContext checkExecution(ParseResult parseResult) {
        ExecutionResult executionResult;
        try {
            executionResult = testExecutor.execute(parseResult.getTestCase());
            if (executionResult != null) {
                for (Integer position : executionResult.getPositionsWhereExceptionsWereThrown()) {
                    Throwable thrown = executionResult.getExceptionThrownAtPosition(position);
                    if (!countsAsExecutionFailure(parseResult.getTestCase(),
                            parseResult.getExpectedExceptionClass(), position, thrown)) {
                        continue;
                    }
                    Integer diagnosticPosition =
                            failureFormatter.resolveDiagnosticStatementPosition(parseResult, position, thrown);
                    String message = "Execution error in test '" + parseResult.getOriginalMethodName()
                            + "': "
                            + failureFormatter.formatThrowableWithStackExcerpt(thrown, parseResult, diagnosticPosition)
                            + failureFormatter.buildExecutionContext(parseResult, diagnosticPosition, thrown);
                    return new ExecutionFailureContext(message, thrown, diagnosticPosition);
                }
            }
        } catch (Throwable executionFailure) {
            String message = "Execution failure in test '" + parseResult.getOriginalMethodName()
                    + "': " + failureFormatter.formatThrowableWithStackExcerpt(executionFailure, parseResult, null)
                    + failureFormatter.buildExecutionContext(parseResult, null, executionFailure);
            return new ExecutionFailureContext(message, executionFailure, null);
        }
        if (repairOnAssertionFailures) {
            ExecutionFailureContext assertionError = checkAssertions(parseResult, executionResult);
            if (assertionError != null) {
                return assertionError;
            }
        }
        return null;
    }

    private ExecutionFailureContext checkAssertions(ParseResult parseResult, ExecutionResult executionResult) {
        if (parseResult == null || parseResult.getTestCase() == null || executionResult == null) {
            return null;
        }
        Scope finalScope = executionResult.getFinalScope();
        if (finalScope == null) {
            return null;
        }
        // Statements from the first exception onwards never completed, so their
        // assertions say nothing about the SUT; evaluating them against the final
        // scope reports misleading "Assertion failed at statement N" feedback.
        int executedCount = executedStatementCount(parseResult.getTestCase(), executionResult);
        if (executedCount < parseResult.getTestCase().size()) {
            ExecutionFailureContext unreachable = checkUnreachableAssertions(
                    parseResult, executionResult, executedCount);
            if (unreachable != null) {
                return unreachable;
            }
        }
        for (int i = 0; i < executedCount; i++) {
            org.evosuite.testcase.statements.Statement stmt = parseResult.getTestCase().getStatement(i);
            if (stmt == null || stmt.getAssertions() == null || stmt.getAssertions().isEmpty()) {
                continue;
            }
            for (Assertion assertion : stmt.getAssertions()) {
                if (assertion == null) {
                    continue;
                }
                final boolean holds;
                try {
                    holds = assertion.evaluate(finalScope);
                } catch (Throwable assertionFailure) {
                    String message = "Execution error in test '" + parseResult.getOriginalMethodName()
                            + "': Assertion evaluation error at statement " + i + " - "
                            + failureFormatter.formatThrowableWithStackExcerpt(assertionFailure, parseResult, i)
                            + failureFormatter.buildExecutionContext(parseResult, i, assertionFailure);
                    return new ExecutionFailureContext(message, assertionFailure, i);
                }
                if (!holds) {
                    String message = "Execution error in test '" + parseResult.getOriginalMethodName()
                            + "': Assertion failed at statement " + i + " - "
                            + assertion.getCode()
                            + failureFormatter.buildExecutionContext(parseResult, i, null);
                    return new ExecutionFailureContext(message, null, i);
                }
            }
        }
        return null;
    }

    /**
     * Number of leading statements that completed: the position of the first
     * thrown exception, or the test size when nothing was thrown or execution
     * continued past the exception.
     */
    static int executedStatementCount(TestCase testCase, ExecutionResult executionResult) {
        Integer firstException = executionResult.getFirstPositionOfThrownException();
        if (firstException == null || firstException < 0) {
            return testCase.size();
        }
        if (!Properties.BREAK_ON_EXCEPTION && !(executionResult.getExceptionThrownAtPosition(firstException)
                instanceof org.evosuite.runtime.System.SystemExitException)) {
            return testCase.size();
        }
        return Math.min(firstException, testCase.size());
    }

    /**
     * When statements carrying assertions were skipped because of an exception
     * the test neither expects nor can avoid (it was not raised by the EvoSuite
     * runtime), the intended oracles never ran: report the exception rather
     * than the assertions. Returns null when no assertion was skipped or the
     * exception is expected.
     */
    private ExecutionFailureContext checkUnreachableAssertions(ParseResult parseResult,
                                                               ExecutionResult executionResult,
                                                               int exceptionPosition) {
        TestCase testCase = parseResult.getTestCase();
        List<Integer> skipped = new ArrayList<>();
        for (int i = exceptionPosition; i < testCase.size(); i++) {
            org.evosuite.testcase.statements.Statement stmt = testCase.getStatement(i);
            if (stmt != null && stmt.getAssertions() != null && !stmt.getAssertions().isEmpty()) {
                skipped.add(i);
            }
        }
        if (skipped.isEmpty()) {
            return null;
        }
        Throwable thrown = executionResult.getExceptionThrownAtPosition(exceptionPosition);
        if (thrown == null
                || matchesExpectedException(parseResult.getExpectedExceptionClass(), thrown)
                || isRaisedByEvoSuiteRuntime(thrown)) {
            return null;
        }
        String message = "Execution error in test '" + parseResult.getOriginalMethodName()
                + "': statement " + exceptionPosition + " threw "
                + failureFormatter.formatThrowableWithStackExcerpt(thrown, parseResult, exceptionPosition)
                + "\nThe assertions at statement(s) " + skipped
                + " were never reached because of this exception."
                + failureFormatter.buildExecutionContext(parseResult, exceptionPosition, thrown);
        return new ExecutionFailureContext(message, thrown, exceptionPosition);
    }

    private boolean isUndeclaredException(TestCase testCase, String expectedExceptionClass,
                                          Integer position, Throwable thrown) {
        if (testCase == null || position == null || thrown == null) {
            return false;
        }
        if (matchesExpectedException(expectedExceptionClass, thrown)) {
            return false;
        }
        // Exceptions raised by the EvoSuite runtime itself (simulated I/O and
        // network failures, System.exit) are environment-induced, deterministic,
        // and unavoidable for any test that reaches the call site. Accept them as
        // if they were declared so the test is kept as a covering chromosome.
        if (isRaisedByEvoSuiteRuntime(thrown)) {
            return false;
        }
        if (position < 0 || position >= testCase.size()) {
            // If execution reports an exception at an invalid position, treat it as undeclared.
            // Silently ignoring it can incorrectly mark a failing candidate as successful.
            return true;
        }
        Set<Class<?>> declared = testCase.getStatement(position).getDeclaredExceptions();
        return !declared.contains(thrown.getClass())
                && !declared.contains(unmockedExceptionClass(thrown.getClass()));
    }

    private static boolean matchesExpectedException(String expected, Throwable thrown) {
        if (thrown == null) {
            return false;
        }
        if (expected == null || expected.trim().isEmpty()) {
            return false;
        }
        Class<?> current = thrown.getClass();
        while (current != null) {
            if (matchesExpectedExceptionName(current, expected)) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    static boolean matchesExpectedExceptionName(Class<?> actualClass, String expected) {
        if (actualClass == null || expected == null) {
            return false;
        }
        String normalized = expected.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        return actualClass.getName().equals(normalized)
                || actualClass.getSimpleName().equals(normalized)
                || actualClass.getName().endsWith("." + normalized);
    }
}
