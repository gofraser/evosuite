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
import org.evosuite.assertion.PrimitiveAssertion;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;
import org.evosuite.runtime.mock.java.lang.MockIllegalArgumentException;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testparser.ParseResult;
import org.evosuite.testparser.TestParser;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Classification and reporting of exceptions observed while executing
 * LLM-generated tests.
 */
class TestRepairLoopExecutionFeedbackTest {

    private String originalTargetClass;

    @BeforeEach
    void setTarget() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = FeedbackTarget.class.getName();
    }

    @AfterEach
    void restoreTarget() {
        Properties.TARGET_CLASS = originalTargetClass;
        TestRepairLoop.resetForRunCompletion();
    }

    /** Target class for the parsed tests. */
    public static class FeedbackTarget {
        public int load() throws IOException {
            throw new IOException("disk");
        }
    }

    @Test
    void sutExceptionRewrittenToMockClassIsReportedUnderJdkName() {
        // What instrumentation turns `throw new IllegalArgumentException(..)`
        // in SUT code into: the mock class, thrown from a SUT frame.
        Throwable sutException = new MockIllegalArgumentException(
                "Duplicate declaration of OAIVerb 'ListRecords'");
        assertFalse(TestRepairLoop.isRaisedByEvoSuiteRuntime(sutException));

        RepairResult result = runWithExceptionAtFirstStatement(testCase -> sutException);

        String error = executionError(result);
        assertTrue(error.contains("java.lang.IllegalArgumentException - Duplicate declaration"), error);
        assertFalse(error.contains("MockIllegalArgumentException"), error);
    }

    @Test
    void exceptionThrownByMockRuntimeIsAcceptedAsEnvironmentInduced() {
        IOException simulated = new IOException("Simulated IO exception");
        simulated.setStackTrace(new StackTraceElement[]{
                new StackTraceElement(TestRepairLoop.MOCK_RUNTIME_PACKAGE_PREFIX + "java.net.MockSocket",
                        "connect", "MockSocket.java", 42),
                new StackTraceElement(FeedbackTarget.class.getName(), "open", "FeedbackTarget.java", 7)});
        assertTrue(TestRepairLoop.isRaisedByEvoSuiteRuntime(simulated));

        RepairResult result = runWithExceptionAtFirstStatement(testCase -> simulated);

        assertTrue(result.isSuccess(), String.valueOf(result.getDiagnostics()));
        assertEquals(1, result.getTestCases().size());
        assertTrue(result.getDiagnostics().stream().noneMatch(d -> d.startsWith("Execution error")),
                String.valueOf(result.getDiagnostics()));
    }

    @Test
    void jdkFramesAboveTheMockRuntimeFrameAreSkipped() {
        NullPointerException npe = new NullPointerException();
        npe.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("java.util.Objects", "requireNonNull", "Objects.java", 209),
                new StackTraceElement(TestRepairLoop.MOCK_RUNTIME_PACKAGE_PREFIX + "java.io.MockFile",
                        "<init>", "MockFile.java", 10)});
        assertTrue(TestRepairLoop.isRaisedByEvoSuiteRuntime(npe));

        npe.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("java.util.Objects", "requireNonNull", "Objects.java", 209),
                new StackTraceElement(FeedbackTarget.class.getName(), "<init>", "FeedbackTarget.java", 3),
                new StackTraceElement(TestRepairLoop.MOCK_RUNTIME_PACKAGE_PREFIX + "java.lang.MockThread",
                        "run", "MockThread.java", 10)});
        assertFalse(TestRepairLoop.isRaisedByEvoSuiteRuntime(npe));
    }

    @Test
    void systemExitIsAcceptedAsEnvironmentInduced() {
        RepairResult result = runWithExceptionAtFirstStatement(
                testCase -> new org.evosuite.runtime.System.SystemExitException());

        assertTrue(result.isSuccess(), String.valueOf(result.getDiagnostics()));
        assertTrue(result.getDiagnostics().stream().noneMatch(d -> d.startsWith("Execution error")),
                String.valueOf(result.getDiagnostics()));
    }

    @Test
    void assertionsAfterAnUnexpectedExceptionReportTheException() {
        RepairResult result = runLoadThenAssert(null);

        String error = executionError(result);
        assertTrue(error.contains("statement 1 threw java.io.IOException - disk"), error);
        assertTrue(error.contains("assertions at statement(s) [2] were never reached"), error);
        assertTrue(result.getDiagnostics().stream().noneMatch(d -> d.contains("Assertion failed")
                        || d.contains("Assertion evaluation error")),
                String.valueOf(result.getDiagnostics()));
    }

    @Test
    void assertionsAfterAnExpectedExceptionAreNotEvaluated() {
        RepairResult result = runLoadThenAssert("IOException");

        assertTrue(result.isSuccess(), String.valueOf(result.getDiagnostics()));
        assertTrue(result.getDiagnostics().stream().noneMatch(d -> d.startsWith("Execution error")),
                String.valueOf(result.getDiagnostics()));
        assertEquals(1, result.getTestCases().get(0).getStatement(2).getAssertions().size());
    }

    /**
     * {@code new FeedbackTarget(); target.load(); int int0 = 3; assertEquals(4, int0);}
     * where {@code load()} throws its declared IOException.
     */
    private RepairResult runLoadThenAssert(String expectedException) {
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase testCase = constructorOnlyTest();
                try {
                    testCase.addStatement(new MethodStatement(testCase,
                            new GenericMethod(FeedbackTarget.class.getMethod("load"), FeedbackTarget.class),
                            testCase.getStatement(0).getReturnValue(),
                            Collections.<VariableReference>emptyList()));
                } catch (NoSuchMethodException e) {
                    throw new AssertionError(e);
                }
                IntPrimitiveStatement literal = new IntPrimitiveStatement(testCase, 3);
                testCase.addStatement(literal);
                PrimitiveAssertion assertion = new PrimitiveAssertion();
                assertion.setSource(literal.getReturnValue());
                assertion.setValue(4);
                literal.addAssertion(assertion);
                ParseResult parsed = new ParseResult(testCase, "ctorTest");
                parsed.setExpectedExceptionClass(expectedException);
                return Collections.singletonList(parsed);
            }
        };
        TestRepairLoop loop = new TestRepairLoop(mock(LlmService.class), parser, new LlmResponseParser(),
                mock(ClusterExpansionManager.class),
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    Scope scope = new Scope();
                    scope.setObject(testCase.getStatement(0).getReturnValue(), new FeedbackTarget());
                    result.reportNewThrownException(1, new IOException("disk"));
                    result.setFinalScope(scope);
                    return result;
                },
                0, null, null, TestRepairLoop.RepairOptions.forAssertionPolicy(true));
        return loop.attemptParse("```java\n@org.junit.Test public void t(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")), LlmFeature.TEST_REPAIR);
    }

    private RepairResult runWithExceptionAtFirstStatement(Function<TestCase, Throwable> exception) {
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public List<ParseResult> parseTestClass(String sourceCode) {
                return Collections.singletonList(new ParseResult(constructorOnlyTest(), "ctorTest"));
            }
        };
        TestRepairLoop loop = new TestRepairLoop(mock(LlmService.class), parser, new LlmResponseParser(),
                mock(ClusterExpansionManager.class),
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(0, exception.apply(testCase));
                    return result;
                },
                0, null, null, TestRepairLoop.RepairOptions.forAssertionPolicy(true));
        return loop.attemptParse("```java\n@org.junit.Test public void t(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")), LlmFeature.TEST_REPAIR);
    }

    private static String executionError(RepairResult result) {
        return result.getDiagnostics().stream()
                .filter(d -> d.startsWith("Execution error in test 'ctorTest'"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no execution error in " + result.getDiagnostics()));
    }

    private static DefaultTestCase constructorOnlyTest() {
        DefaultTestCase testCase = new DefaultTestCase();
        try {
            GenericConstructor constructor = new GenericConstructor(
                    FeedbackTarget.class.getConstructor(), FeedbackTarget.class);
            testCase.addStatement(new ConstructorStatement(testCase, constructor,
                    Collections.<VariableReference>emptyList()));
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
        return testCase;
    }
}
