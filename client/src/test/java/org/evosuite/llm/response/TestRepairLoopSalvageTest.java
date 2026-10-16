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
import org.evosuite.assertion.PrimitiveAssertion;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testparser.ParseDiagnostic;
import org.evosuite.testparser.ParseResult;
import org.evosuite.testparser.TestParser;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Final-resort salvage of failing LLM candidates when the assertion policy
 * requires the LLM's assertions to be kept.
 */
class TestRepairLoopSalvageTest {

    private String originalTargetClass;

    @BeforeEach
    void setTarget() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = SalvageTarget.class.getName();
    }

    @AfterEach
    void restoreTarget() {
        Properties.TARGET_CLASS = originalTargetClass;
        TestRepairLoop.resetForRunCompletion();
    }

    @Test
    void keepPolicyRemovesOnlyTheFailingAssertion() {
        TestRepairLoop loop = keepAssertionLoop(0, source -> Collections.singletonList(
                new ParseResult(targetSizeTest(), "sizeTest")));

        RepairResult result = loop.attemptParse("```java\n@org.junit.Test public void t(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")), LlmFeature.TEST_REPAIR);

        assertTrue(result.isSuccess(), String.valueOf(result.getDiagnostics()));
        assertEquals(1, result.getTestCases().size());
        TestCase salvaged = result.getTestCases().get(0);
        assertEquals(3, salvaged.size());
        assertEquals(Collections.singletonList(5), assertedValues(salvaged.getStatement(0)));
        assertEquals(Collections.singletonList(0), assertedValues(salvaged.getStatement(2)));
    }

    @Test
    void keepPolicyKeepsCoveringThrowAtSutCallWithPassingAssertions() {
        TestRepairLoop loop = keepAssertionLoop(0, source -> Collections.singletonList(
                new ParseResult(targetGetTest(), "getTest")));

        RepairResult result = loop.attemptParse("```java\n@org.junit.Test public void t(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")), LlmFeature.TEST_REPAIR);

        assertTrue(result.isSuccess(), String.valueOf(result.getDiagnostics()));
        TestCase salvaged = result.getTestCases().get(0);
        // The statement after the throwing SUT call is removed, the call is kept.
        assertEquals(3, salvaged.size());
        assertTrue(salvaged.getStatement(2) instanceof MethodStatement);
        assertEquals(Collections.singletonList(5), assertedValues(salvaged.getStatement(0)));
        assertTrue(salvaged.getStatement(2).getAssertions().isEmpty());
    }

    @Test
    void keepPolicySalvagesCandidatesDroppedInEarlierAttempts() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test public void repaired(){}\n```");
        AtomicInteger parseCalls = new AtomicInteger();
        TestRepairLoop loop = keepAssertionLoop(llmService, 1, source -> {
            if (parseCalls.getAndIncrement() == 0) {
                return Collections.singletonList(new ParseResult(targetSizeTest(), "sizeTest"));
            }
            // The repair turn replaces the test with one that does not parse.
            ParseResult broken = new ParseResult(new DefaultTestCase(), "brokenTest");
            broken.addDiagnostic(new ParseDiagnostic(ParseDiagnostic.Severity.ERROR,
                    "syntax error", 1, "broken"));
            return Collections.singletonList(broken);
        });

        RepairResult result = loop.attemptParse("```java\n@org.junit.Test public void t(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")), LlmFeature.TEST_REPAIR);

        assertTrue(result.isSuccess(), String.valueOf(result.getDiagnostics()));
        assertEquals(1, result.getTestCases().size());
        assertEquals(Collections.singletonList(0),
                assertedValues(result.getTestCases().get(0).getStatement(2)));
    }

    /** Target class: {@code size()} returns 0 and {@code get(int)} always throws. */
    public static class SalvageTarget {
        public int size() {
            return 0;
        }

        public int get(int index) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
    }

    private interface ParseFunction {
        List<ParseResult> parse(String source);
    }

    private TestRepairLoop keepAssertionLoop(int maxAttempts, ParseFunction parseFunction) {
        return keepAssertionLoop(mock(LlmService.class), maxAttempts, parseFunction);
    }

    private TestRepairLoop keepAssertionLoop(LlmService llmService, int maxAttempts,
                                             ParseFunction parseFunction) {
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public List<ParseResult> parseTestClass(String sourceCode) {
                return parseFunction.parse(sourceCode);
            }
        };
        return new TestRepairLoop(llmService, parser, new LlmResponseParser(),
                mock(ClusterExpansionManager.class), TestRepairLoopSalvageTest::simulate,
                maxAttempts, null, null, TestRepairLoop.RepairOptions.forAssertionPolicy(true));
    }

    /**
     * Minimal interpreter for the statements used here: int literals, the
     * target constructor, {@code size()} and {@code get(int)}, which always throws.
     */
    static ExecutionResult simulate(TestCase testCase) {
        ExecutionResult result = new ExecutionResult(testCase);
        Scope scope = new Scope();
        for (int i = 0; i < testCase.size(); i++) {
            Statement statement = testCase.getStatement(i);
            if (statement instanceof IntPrimitiveStatement) {
                scope.setObject(statement.getReturnValue(), ((IntPrimitiveStatement) statement).getValue());
            } else if (statement instanceof ConstructorStatement) {
                scope.setObject(statement.getReturnValue(), new SalvageTarget());
            } else if (statement instanceof MethodStatement) {
                String name = ((MethodStatement) statement).getMethodName();
                if ("get".equals(name)) {
                    result.reportNewThrownException(i, new IndexOutOfBoundsException("Index: 5, Size: 0"));
                    break;
                }
                scope.setObject(statement.getReturnValue(), 0);
            }
        }
        result.setFinalScope(scope);
        return result;
    }

    /** {@code int int0 = 5; SalvageTarget t = new SalvageTarget(); int int1 = t.size();} */
    static DefaultTestCase targetSizeTest() {
        DefaultTestCase testCase = new DefaultTestCase();
        IntPrimitiveStatement literal = new IntPrimitiveStatement(testCase, 5);
        testCase.addStatement(literal);
        addPrimitiveAssertion(literal, 5);
        VariableReference target = addTargetConstructor(testCase);
        MethodStatement size = new MethodStatement(testCase, targetMethod("size"), target,
                Collections.<VariableReference>emptyList());
        testCase.addStatement(size);
        addPrimitiveAssertion(size, 0);
        addPrimitiveAssertion(size, 3); // wrong oracle
        return testCase;
    }

    /** Like {@link #targetSizeTest} but calls {@code get(int0)}, which throws, followed by a literal. */
    static DefaultTestCase targetGetTest() {
        DefaultTestCase testCase = new DefaultTestCase();
        IntPrimitiveStatement literal = new IntPrimitiveStatement(testCase, 5);
        testCase.addStatement(literal);
        addPrimitiveAssertion(literal, 5);
        addPrimitiveAssertion(literal, 6); // wrong oracle
        VariableReference target = addTargetConstructor(testCase);
        MethodStatement get = new MethodStatement(testCase, targetMethod("get", int.class), target,
                Collections.singletonList(literal.getReturnValue()));
        testCase.addStatement(get);
        IntPrimitiveStatement after = new IntPrimitiveStatement(testCase, 7);
        testCase.addStatement(after);
        addPrimitiveAssertion(after, 7);
        return testCase;
    }

    private static VariableReference addTargetConstructor(DefaultTestCase testCase) {
        try {
            GenericConstructor constructor = new GenericConstructor(
                    SalvageTarget.class.getConstructor(), SalvageTarget.class);
            return testCase.addStatement(new ConstructorStatement(testCase, constructor,
                    Collections.<VariableReference>emptyList()));
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static GenericMethod targetMethod(String name, Class<?>... parameterTypes) {
        try {
            return new GenericMethod(SalvageTarget.class.getMethod(name, parameterTypes), SalvageTarget.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static void addPrimitiveAssertion(Statement statement, int value) {
        PrimitiveAssertion assertion = new PrimitiveAssertion();
        assertion.setSource(statement.getReturnValue());
        assertion.setValue(value);
        statement.addAssertion(assertion);
    }

    private static List<Object> assertedValues(Statement statement) {
        Set<Assertion> assertions = statement.getAssertions();
        return assertions.stream().map(Assertion::getValue).collect(Collectors.toList());
    }
}
