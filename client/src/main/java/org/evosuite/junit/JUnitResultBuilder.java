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
package org.evosuite.junit;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.internal.Throwables;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Creates a JUnitResult instance from
 * a org.junit.runner.Result object.
 *
 * @author galeotti
 */
public class JUnitResultBuilder {

    private static final Logger logger = LoggerFactory.getLogger(JUnitResultBuilder.class);

    /**
     * Translates <i>part</i> of the org.junit.runner.Result object
     * into an evosuite independent object.
     *
     * @param result the result to build from
     * @return the junit result
     */
    public JUnitResult build(Result result) {
        boolean wasSuccessful = result.wasSuccessful();
        int failureCount = result.getFailureCount();
        int runCount = result.getRunCount();

        JUnitResult junitResult = new JUnitResult(wasSuccessful, failureCount,
                runCount);

        List<Failure> failures = result.getFailures();

        for (Failure failure : failures) {
            junitResult.addFailure(toFailure(failure));
        }
        return junitResult;
    }

    /**
     * Translates one JUnit 4 failure into an evosuite independent object.
     *
     * @param failure the failure to translate
     * @return the translated failure
     */
    static JUnitFailure toFailure(Failure failure) {
        String descriptionMethodName = failure.getDescription().getMethodName();
        String exceptionClassName = failure.getException().getClass().toString();
        String message = failure.getMessage();
        String trace = failure.getTrace();
        boolean isAssertionError = (failure.getException() instanceof java.lang.AssertionError);

        JUnitFailure junitFailure = new JUnitFailure(message, exceptionClassName,
                failure.getDescription().getClassName(), descriptionMethodName, isAssertionError, trace);

        for (StackTraceElement elem : failure.getException().getStackTrace()) {
            junitFailure.addToExceptionStackTrace(elem.toString());
        }
        return junitFailure;
    }

    /**
     * Translates one JUnit 4 failure without calling into its exception, which belongs to the code
     * under test: the message and trace are the name of the class of the exception, and the stack
     * trace is left empty.
     *
     * @param failure the failure to translate
     * @return the translated failure
     */
    static JUnitFailure toFailureWithoutDetail(Failure failure) {
        Throwable exception = failure.getException();
        String name = exception == null ? "unknown" : exception.getClass().getName();
        String exceptionClassName = exception == null ? "unknown" : exception.getClass().toString();
        return new JUnitFailure(name, exceptionClassName, failure.getDescription().getClassName(),
                failure.getDescription().getMethodName(), exception instanceof AssertionError, name);
    }

    /**
     * The name of the test method of a JUnit 5 test. The display name carries trailing
     * parentheses (e.g. "test0()"), while the tests of a suite are named by the bare method
     * name (e.g. "test0").
     *
     * @param identifier the test
     * @return the method name
     */
    static String methodNameOf(TestIdentifier identifier) {
        String name = identifier.getDisplayName();
        if (name != null && name.endsWith("()")) {
            name = name.substring(0, name.length() - 2);
        }
        return name;
    }

    /**
     * The class of a JUnit 5 test, read from its source.
     *
     * @param identifier the test
     * @return the class name, or null if the source does not name a method
     */
    static String classNameOf(TestIdentifier identifier) {
        return identifier.getSource()
                .filter(MethodSource.class::isInstance)
                .map(source -> ((MethodSource) source).getClassName())
                .orElse(null);
    }

    /**
     * The class a JUnit 5 container stands for, read from its source.
     *
     * @param identifier the container
     * @return the class name, or null if the container is not a class, such as the engine
     */
    static String classNameOfContainer(TestIdentifier identifier) {
        return identifier.getSource()
                .filter(ClassSource.class::isInstance)
                .map(source -> ((ClassSource) source).getClassName())
                .orElse(null);
    }

    /**
     * Builds a JUnitResult from a list of test execution results.
     *
     * @param results list of pairs containing test identifiers and execution results
     * @return the built JUnitResult
     */
    public JUnitResult build(List<Pair<TestIdentifier, TestExecutionResult>> results) {
        return build(results, JUnitResultBuilder::toFailure);
    }

    /**
     * Builds a JUnitResult from a list of test execution results without calling into the
     * exceptions of the failures, which belong to the code under test: each failure is reported
     * by the class of its exception alone.
     *
     * @param results list of pairs containing test identifiers and execution results
     * @return the built JUnitResult
     */
    public JUnitResult buildWithoutDetail(List<Pair<TestIdentifier, TestExecutionResult>> results) {
        return build(results, JUnitResultBuilder::toFailureWithoutDetail);
    }

    private static JUnitResult build(List<Pair<TestIdentifier, TestExecutionResult>> results,
                                     BiFunction<TestIdentifier, TestExecutionResult, JUnitFailure> convert) {
        boolean wasSuccessful = results.stream().map(Pair::getRight)
                .noneMatch(r -> r.getStatus() != TestExecutionResult.Status.SUCCESSFUL);
        int failureCount = (int) results.stream().map(Pair::getRight)
                .filter(r -> r.getStatus() != TestExecutionResult.Status.SUCCESSFUL).count();
        int runCount = results.size();

        JUnitResult junitResult = new JUnitResult(wasSuccessful, failureCount, runCount);

        List<Pair<TestIdentifier, TestExecutionResult>> failures =
                results.stream().filter(r -> r.getRight().getStatus() == TestExecutionResult.Status.FAILED)
                        .collect(Collectors.toList());
        failures.stream().map(f -> convert.apply(f.getLeft(), f.getRight())).forEach(junitResult::addFailure);
        return junitResult;
    }

    private static JUnitFailure toFailureWithoutDetail(TestIdentifier identifier, TestExecutionResult failure) {
        Throwable throwable = failure.getThrowable().orElse(null);
        String name = throwable == null ? "unknown" : throwable.getClass().getName();
        return new JUnitFailure(name, name, classNameOf(identifier), methodNameOf(identifier),
                throwable instanceof AssertionError, name);
    }

    private static JUnitFailure toFailure(TestIdentifier identifier, TestExecutionResult failure) {
        // handleTestsThatAreUnstable matches failures to test cases by the bare method name.
        String descriptionMethodName = methodNameOf(identifier);
        Throwable throwable = failure.getThrowable().get();
        String exceptionClassName = throwable.getClass().getName();
        String message = throwable.getMessage();
        String trace = Throwables.getStacktrace(throwable);
        boolean isAssertionError = (throwable instanceof java.lang.AssertionError);

        JUnitFailure junitFailure = new JUnitFailure(message, exceptionClassName, classNameOf(identifier),
                descriptionMethodName, isAssertionError, trace);
        for (StackTraceElement elem : throwable.getStackTrace()) {
            String elemToString = elem.toString();
            junitFailure.addToExceptionStackTrace(elemToString);
        }
        return junitFailure;
    }
}
