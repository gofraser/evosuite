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
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCodeVisitor;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.assertion.CodeAssertion;
import org.evosuite.assertion.PrimitiveAssertion;
import org.evosuite.testcase.statements.UninterpretedStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testparser.ParseDiagnostic;
import org.evosuite.testparser.ParseResult;
import org.evosuite.testparser.TestParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.awt.AWTError;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TestRepairLoopTest {

    private final boolean originalTruncationRecovery = Properties.LLM_ENABLE_TRUNCATION_RECOVERY;

    @AfterEach
    void restoreProperties() {
        Properties.LLM_ENABLE_TRUNCATION_RECOVERY = originalTruncationRecovery;
    }

    @Test
    void truncatedResponseRecoveredWithoutRepairCall() {
        Properties.LLM_ENABLE_TRUNCATION_RECOVERY = true;
        LlmService llmService = mock(LlmService.class);
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                assertFalse(sourceCode.contains("public void broken()"));
                return Collections.singletonList(new ParseResult(new DefaultTestCase(), "test"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> new ExecutionResult(testCase),
                2);

        String truncated = "```java\n"
                + "public class GeneratedLlmTest {\n"
                + "  @org.junit.Test\n"
                + "  public void test(){ int a = 1; }\n"
                + "  @org.junit.Test\n"
                + "  public void broken(){ int x =\n";

        RepairResult result = loop.attemptParse(truncated,
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertTrue(result.isSuccess());
        assertTrue(result.getDiagnostics().stream()
                .anyMatch(d -> d.contains("Applied truncation recovery")));
        verify(llmService, never()).query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());
    }

    @Test
    void expectedExceptionFromAssertThrowsPreventsSpuriousRepair() {
        LlmService llmService = mock(LlmService.class);
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new UninterpretedStatement(tc, "ConfigurationReader.read(null);"));
                ParseResult result = new ParseResult(tc, "read_nullInputStream_throws");
                result.setExpectedExceptionClass("IllegalArgumentException");
                return Collections.singletonList(result);
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(0, new IllegalArgumentException("InputStream cannot be null"));
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertTrue(result.isSuccess());
        verify(llmService, never()).query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());
    }

    @Test
    void reflectiveAssertThrowsIsRewrittenBeforeParsing() {
        LlmService llmService = mock(LlmService.class);
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                assertTrue(sourceCode.contains(
                        "java.lang.reflect.InvocationTargetException invocationTargetException0 "
                                + "= org.junit.jupiter.api.Assertions.assertThrows("
                                + "java.lang.reflect.InvocationTargetException.class"),
                        "Expected reflective assertThrows capture to use fully-qualified Assertions.assertThrows");
                assertTrue(sourceCode.contains(
                        "CheckstylePluginException expectedCause0 = "
                                + "(CheckstylePluginException) invocationTargetException0.getCause();"),
                        "Expected rewritten source to validate wrapped cause type with a direct cast");
                assertFalse(sourceCode.contains(
                        "assertThrows(CheckstylePluginException.class, () -> method0.invoke(null, "
                                + "java.util.Collections.emptyList()));"),
                        "Original reflective assertThrows form should be removed");

                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testReflectiveRewrite"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> new ExecutionResult(testCase),
                0);

        String reflectiveResponse = "import static org.junit.jupiter.api.Assertions.*;\n"
                + "import java.lang.reflect.Method;\n"
                + "import com.atlassw.tools.eclipse.checkstyle.util.CheckstylePluginException;\n"
                + "public class GeneratedLlmTest {\n"
                + "  @org.junit.jupiter.api.Test\n"
                + "  public void test0() throws Throwable {\n"
                + "    Method method0 = Object.class.getDeclaredMethod(\"toString\");\n"
                + "    assertThrows(CheckstylePluginException.class, () -> method0.invoke(null, "
                + "java.util.Collections.emptyList()));\n"
                + "  }\n"
                + "}\n";

        RepairResult result = loop.attemptParse(
                reflectiveResponse,
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.SEEDING);

        assertTrue(result.isSuccess());
        assertTrue(result.getDiagnostics().stream().anyMatch(
                d -> d.contains("Normalized 1 reflective assertThrows invocation(s)")));
        verify(llmService, never()).query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());
    }

    @Test
    void retriesSameResponseAfterSuccessfulClusterExpansion() {
        LlmService llmService = mock(LlmService.class);
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);
        when(expansionManager.tryExpandFrom(anyList())).thenReturn(true);
        when(expansionManager.getLastExpandedClasses()).thenReturn(Arrays.asList("java.util.ArrayList"));

        AtomicInteger calls = new AtomicInteger();
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                if (calls.getAndIncrement() == 0) {
                    ParseResult error = new ParseResult(new DefaultTestCase(), "test");
                    error.addDiagnostic(new ParseDiagnostic(ParseDiagnostic.Severity.ERROR,
                            "cannot find symbol java.util.ArrayList", 1,
                            "ArrayList list = new ArrayList();"));
                    return Collections.singletonList(error);
                }
                return Collections.singletonList(new ParseResult(new DefaultTestCase(), "test"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> new ExecutionResult(testCase),
                2);

        RepairResult result = loop.attemptParse("```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertTrue(result.isSuccess());
        assertEquals(2, result.getAttemptsUsed());
        verify(expansionManager, times(1)).tryExpandFrom(anyList());
        verify(llmService, never()).query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());
    }

    @Test
    void requestsRepairWhenParseErrorCannotBeExpanded() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");

        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);
        when(expansionManager.tryExpandFrom(anyList())).thenReturn(false);

        AtomicInteger calls = new AtomicInteger();
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                if (calls.getAndIncrement() == 0) {
                    ParseResult error = new ParseResult(new DefaultTestCase(), "test");
                    error.addDiagnostic(new ParseDiagnostic(ParseDiagnostic.Severity.ERROR,
                            "syntax error", 1,
                            "broken"));
                    return Collections.singletonList(error);
                }
                return Collections.singletonList(new ParseResult(new DefaultTestCase(), "test"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> new ExecutionResult(testCase),
                2);

        RepairResult result = loop.attemptParse("broken",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertTrue(result.isSuccess());
        verify(llmService, times(1)).query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());
    }

    @Test
    void parserExceptionIsCapturedAndRepairAttempted() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");

        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        AtomicInteger calls = new AtomicInteger();
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                if (calls.getAndIncrement() == 0) {
                    throw new IllegalStateException("parser crashed");
                }
                return Collections.singletonList(new ParseResult(new DefaultTestCase(), "test"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> new ExecutionResult(testCase),
                1);

        RepairResult result = loop.attemptParse("broken",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertTrue(result.isSuccess());
        assertTrue(result.getDiagnostics().stream().anyMatch(d -> d.contains("Parser failure")));
        verify(llmService, times(1)).query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());
    }

    @Test
    void executorExceptionIsCapturedAndRepairAttempted() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");

        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                return Collections.singletonList(new ParseResult(new DefaultTestCase(), "test"));
            }
        };

        AtomicInteger executions = new AtomicInteger();
        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    if (executions.getAndIncrement() == 0) {
                        throw new RuntimeException("executor crashed");
                    }
                    return new ExecutionResult(testCase);
                },
                1);

        RepairResult result = loop.attemptParse("```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertTrue(result.isSuccess());
        assertTrue(result.getDiagnostics().stream().anyMatch(d -> d.contains("Execution failure")));
        verify(llmService, times(1)).query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());
    }

    @Test
    void emptyParseResultTriggersRepairInsteadOfSuccess() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");

        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        AtomicInteger calls = new AtomicInteger();
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                if (calls.getAndIncrement() == 0) {
                    return Collections.emptyList();
                }
                return Collections.singletonList(new ParseResult(new DefaultTestCase(), "test"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> new ExecutionResult(testCase),
                1);

        RepairResult result = loop.attemptParse("```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertTrue(result.isSuccess());
        assertTrue(result.getDiagnostics().stream().anyMatch(d -> d.contains("no test methods")));
        verify(llmService, times(1)).query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());
    }

    @Test
    void stopsRepairWhenErrorDiffersOnlyInLineNumbers() {
        LlmService llmService = mock(LlmService.class);
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        // Parser returns errors that differ only in line numbers
        AtomicInteger calls = new AtomicInteger();
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                int call = calls.getAndIncrement();
                int line = 10 + call;  // line number changes each time
                ParseResult error = new ParseResult(new DefaultTestCase(), "test");
                error.addDiagnostic(new ParseDiagnostic(ParseDiagnostic.Severity.ERROR,
                        "No matching constructor found for Foo with args ()", line,
                        "new Foo()"));
                return Collections.singletonList(error);
            }
        };

        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void test(){ new Foo(); }\n```");

        TestRepairLoop loop = new TestRepairLoop(
                llmService, parser, new LlmResponseParser(), expansionManager,
                testCase -> new ExecutionResult(testCase), 3);

        RepairResult result = loop.attemptParse("```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        assertTrue(result.getDiagnostics().stream().anyMatch(d -> d.contains("identical error repeated")));
        // Should stop after 2 attempts (initial + 1 repair), not exhaust all 3
        verify(llmService, atMost(1)).query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());
    }

    @Test
    void assertionFailuresCanBeIgnoredByPolicy() {
        LlmService llmService = mock(LlmService.class);
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase testCase = new DefaultTestCase();
                testCase.addStatement(new IntPrimitiveStatement(testCase, 1));
                return Collections.singletonList(new ParseResult(testCase, "test"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(0, new AssertionError("boom"));
                    return result;
                },
                0,
                null,
                null,
                new TestRepairLoop.RepairOptions(false, false, true));

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertTrue(result.isSuccess());
        verify(llmService, never()).query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());
    }

    @Test
    void semanticAssertionFailureTriggersRepairWhenEnabled() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        AtomicInteger parseCalls = new AtomicInteger();
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase testCase = new DefaultTestCase();
                IntPrimitiveStatement stmt = new IntPrimitiveStatement(testCase, 1);
                testCase.addStatement(stmt);
                PrimitiveAssertion assertion = new PrimitiveAssertion();
                assertion.setSource(stmt.getReturnValue());
                assertion.setValue(parseCalls.getAndIncrement() == 0 ? 2 : 1);
                stmt.addAssertion(assertion);
                return Collections.singletonList(new ParseResult(testCase, "test"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    Scope scope = new Scope();
                    IntPrimitiveStatement stmt = (IntPrimitiveStatement) testCase.getStatement(0);
                    scope.setObject(stmt.getReturnValue(), stmt.getValue());
                    result.setFinalScope(scope);
                    return result;
                },
                1,
                null,
                null,
                TestRepairLoop.RepairOptions.forAssertionPolicy(true));

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertTrue(result.isSuccess());
        assertTrue(result.getDiagnostics().stream().anyMatch(d -> d.contains("Assertion failed")));
        verify(llmService, times(1)).query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());
    }

    @Test
    void codeAssertionFailureTriggersRepairWhenEnabled() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        AtomicInteger parseCalls = new AtomicInteger();
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase testCase = new DefaultTestCase();
                IntPrimitiveStatement stmt = new IntPrimitiveStatement(testCase, 1);
                testCase.addStatement(stmt);
                TestCodeVisitor visitor = new TestCodeVisitor();
                testCase.accept(visitor);
                String varName = visitor.getVariableName(stmt.getReturnValue());
                int expected = parseCalls.getAndIncrement() == 0 ? 2 : 1;
                CodeAssertion assertion = new CodeAssertion(
                        "assertEquals(" + expected + ", " + varName + ");");
                assertion.setSource(stmt.getReturnValue());
                stmt.addAssertion(assertion);
                return Collections.singletonList(new ParseResult(testCase, "test"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    Scope scope = new Scope();
                    IntPrimitiveStatement stmt = (IntPrimitiveStatement) testCase.getStatement(0);
                    scope.setObject(stmt.getReturnValue(), stmt.getValue());
                    result.setFinalScope(scope);
                    return result;
                },
                1,
                null,
                null,
                TestRepairLoop.RepairOptions.forAssertionPolicy(true));

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertTrue(result.isSuccess());
        assertTrue(result.getDiagnostics().stream().anyMatch(d -> d.contains("Assertion failed")));
        verify(llmService, times(1)).query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());
    }

    @Test
    void semanticAssertionFailureCanBeIgnoredByRepairPolicy() {
        LlmService llmService = mock(LlmService.class);
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase testCase = new DefaultTestCase();
                IntPrimitiveStatement stmt = new IntPrimitiveStatement(testCase, 1);
                testCase.addStatement(stmt);
                PrimitiveAssertion assertion = new PrimitiveAssertion();
                assertion.setSource(stmt.getReturnValue());
                assertion.setValue(2);
                stmt.addAssertion(assertion);
                return Collections.singletonList(new ParseResult(testCase, "test"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    Scope scope = new Scope();
                    IntPrimitiveStatement stmt = (IntPrimitiveStatement) testCase.getStatement(0);
                    scope.setObject(stmt.getReturnValue(), stmt.getValue());
                    result.setFinalScope(scope);
                    return result;
                },
                0,
                null,
                null,
                new TestRepairLoop.RepairOptions(true, false, false));

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertTrue(result.isSuccess());
        verify(llmService, never()).query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());
    }

    @Test
    void codeAssertionFailureCanBeIgnoredByRepairPolicy() {
        LlmService llmService = mock(LlmService.class);
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase testCase = new DefaultTestCase();
                IntPrimitiveStatement stmt = new IntPrimitiveStatement(testCase, 1);
                testCase.addStatement(stmt);
                TestCodeVisitor visitor = new TestCodeVisitor();
                testCase.accept(visitor);
                String varName = visitor.getVariableName(stmt.getReturnValue());
                CodeAssertion assertion = new CodeAssertion("assertEquals(2, " + varName + ");");
                assertion.setSource(stmt.getReturnValue());
                stmt.addAssertion(assertion);
                return Collections.singletonList(new ParseResult(testCase, "test"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    Scope scope = new Scope();
                    IntPrimitiveStatement stmt = (IntPrimitiveStatement) testCase.getStatement(0);
                    scope.setObject(stmt.getReturnValue(), stmt.getValue());
                    result.setFinalScope(scope);
                    return result;
                },
                0,
                null,
                null,
                new TestRepairLoop.RepairOptions(true, false, false));

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertTrue(result.isSuccess());
        verify(llmService, never()).query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());
    }

    @Test
    void sanitizerRemovesAssertionUninterpretedStatements() {
        DefaultTestCase testCase = new DefaultTestCase();
        testCase.addStatement(new UninterpretedStatement(testCase, "assertEquals(1, 1);"));
        assertEquals(1, testCase.size());

        int removed = LlmAssertionSanitizer.sanitize(testCase);

        assertTrue(removed > 0);
        assertEquals(0, testCase.size());
    }

    @Test
    void sanitizerKeepsNonAssertionCallsThatStartWithAssertPrefix() {
        DefaultTestCase testCase = new DefaultTestCase();
        testCase.addStatement(new UninterpretedStatement(testCase, "validator.assertState();"));
        assertEquals(1, testCase.size());

        int removed = LlmAssertionSanitizer.sanitize(testCase);

        assertEquals(0, removed);
        assertEquals(1, testCase.size());
    }

    @Test
    void repairPromptUsesConfiguredDropPolicyToAvoidAssertions() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\nbroken\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                ParseResult error = new ParseResult(new DefaultTestCase(), "test");
                error.addDiagnostic(new ParseDiagnostic(ParseDiagnostic.Severity.ERROR,
                        "syntax error", 1, "broken"));
                return Collections.singletonList(error);
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> new ExecutionResult(testCase),
                1,
                null,
                null,
                TestRepairLoop.RepairOptions.forAssertionPolicy(false));

        RepairResult result = loop.attemptParse(
                "broken",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Do NOT include assertions"));
    }

    @Test
    void repairPromptIncludesActionableHintsFromParseDiagnostics() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\nbroken\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                ParseResult error = new ParseResult(new DefaultTestCase(), "test");
                error.addDiagnostic(new ParseDiagnostic(ParseDiagnostic.Severity.ERROR,
                        "Unresolved variable: missingVar LLM_REPAIR_ACTION_REQUIRED: declare the variable earlier",
                        3, "int x = missingVar;"));
                return Collections.singletonList(error);
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> new ExecutionResult(testCase),
                1);

        RepairResult result = loop.attemptParse(
                "broken",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Repair hints:"));
        assertTrue(userRepairMessage.contains("declare the variable earlier"));
    }

    @Test
    void dependencyMissingErrorsAreRepairableAndPromptGetsTargetedInstructions() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                return Collections.singletonList(new ParseResult(new DefaultTestCase(), "test"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(0, new NoClassDefFoundError("com/example/MissingFramework"));
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Dependency-missing repair instructions"));
        assertTrue(userRepairMessage.contains("Do NOT reference or instantiate missing external/framework classes"));
        assertTrue(userRepairMessage.contains("Initialization/dependency failure hint"));
        assertTrue(userRepairMessage.contains("com/example/MissingFramework"));
    }

    @Test
    void fallbackArtifactsAddTargetedRepairInstructions() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\nbroken\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                ParseResult error = new ParseResult(new DefaultTestCase(), "test");
                error.addDiagnostic(new ParseDiagnostic(ParseDiagnostic.Severity.ERROR,
                        "cannot find symbol: setField(...) with __llm_fallback0",
                        10,
                        "setField(target, \"x\", __llm_fallback0);"));
                return Collections.singletonList(error);
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> new ExecutionResult(testCase),
                1);

        RepairResult result = loop.attemptParse(
                "broken",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Fallback-strategy repair instructions"));
        assertTrue(userRepairMessage.contains("parser-generated placeholders"));
        assertTrue(userRepairMessage.contains("trigger synthetic __llm_fallback variables"));
        assertTrue(userRepairMessage.contains("Do not define anonymous classes"));
    }

    @Test
    void nullPointerExecutionErrorAddsNpePreconditionRepairInstructions() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testNpe"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(0, new NullPointerException(
                            "Cannot invoke \"x.y.Z.getDestSession()\" because "
                                    + "\"sessionInfoProv\" is null"));
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("NPE precondition hint"));
        assertTrue(userRepairMessage.contains("sessionInfoProv"));
        assertTrue(userRepairMessage.contains("Keep all already executable tests unchanged"));
        assertTrue(userRepairMessage.contains("Do not call the target method with null"));
        assertTrue(userRepairMessage.contains("do NOT use new on that type"));
        assertTrue(userRepairMessage.contains("Mockito.mock(Type.class)"));
        assertTrue(userRepairMessage.contains("Failure stack excerpt:"));
        assertTrue(userRepairMessage.contains("Parsed test code excerpt:"));
    }

    @Test
    void executionErrorIncludesSutStackExcerptInRepairMessage() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testStackExcerpt"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    NullPointerException npe = new NullPointerException(
                            "Cannot invoke \"org.objectweb.asm.jip.ClassReader.getItem(int)\" because \"b\" is null");
                    npe.setStackTrace(new StackTraceElement[]{
                            new StackTraceElement("org.objectweb.asm.jip.ClassReader",
                                    "getItem", "ClassReader.java", 123),
                            new StackTraceElement("org.evosuite.testcase.execution.TestCaseExecutor",
                                    "runTest", "TestCaseExecutor.java", 42),
                            new StackTraceElement("java.lang.Thread",
                                    "run", "Thread.java", 840)
                    });
                    result.reportNewThrownException(0, npe);
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Failure stack excerpt:"));
        assertTrue(userRepairMessage.contains("org.objectweb.asm.jip.ClassReader.getItem(ClassReader.java:123)"));
    }

    @Test
    void wrappedCodeUnderTestExceptionIncludesRootCauseAndFailingStatementContext() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                tc.addStatement(new IntPrimitiveStatement(tc, 2));
                return Collections.singletonList(new ParseResult(tc, "testWrappedNpe"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    NullPointerException npe = new NullPointerException(
                            "Cannot invoke \"net.sourceforge.squirrel_sql.plugins.dbcopy.SessionInfoProvider.getDestSession()\" because \"sessionInfoProv\" is null");
                    npe.setStackTrace(new StackTraceElement[]{
                            new StackTraceElement("net.sourceforge.squirrel_sql.plugins.dbcopy.actions.PasteTableUtil",
                                    "excePasteTable", "PasteTableUtil.java", 25)
                    });
                    CodeUnderTestException wrapper = new CodeUnderTestException(npe);
                    wrapper.setStackTrace(new StackTraceElement[]{
                            new StackTraceElement("shaded.org.evosuite.testcase.statements.MethodStatement$1",
                                    "execute", "MethodStatement.java", 405),
                            new StackTraceElement("shaded.org.evosuite.testcase.statements.AbstractStatement",
                                    "exceptionHandler", "AbstractStatement.java", 180)
                    });
                    result.reportNewThrownException(1, wrapper);
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Root cause: java.lang.NullPointerException"));
        assertTrue(userRepairMessage.contains("sessionInfoProv"));
        assertTrue(userRepairMessage.contains("PasteTableUtil.excePasteTable(PasteTableUtil.java:25)"));
        assertTrue(userRepairMessage.contains("Failing statement (index 1, zero-based):"));
        assertTrue(userRepairMessage.contains("Parsed test code excerpt:"));
    }

    @Test
    void wrappedCodeUnderTestExceptionWithFallbackReceiverGapAddsReceiverSetupNote() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new UninterpretedStatement(tc,
                        "net.sourceforge.squirrel_sql.plugins.dbcopy.SessionInfoProvider __llm_fallback0 = null;"));
                tc.addStatement(new UninterpretedStatement(tc,
                        "sessionInfoProvider0.setDestSession(iSession0);"));
                return Collections.singletonList(new ParseResult(tc, "testFallbackReceiverGap"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    NullPointerException npe = new NullPointerException();
                    CodeUnderTestException wrapper = new CodeUnderTestException(npe);
                    wrapper.setStackTrace(new StackTraceElement[]{
                            new StackTraceElement("shaded.org.evosuite.testcase.statements.MethodStatement$1",
                                    "execute", "MethodStatement.java", 405),
                            new StackTraceElement("shaded.org.evosuite.testcase.statements.AbstractStatement",
                                    "exceptionHandler", "AbstractStatement.java", 180)
                    });
                    result.reportNewThrownException(1, wrapper);
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Failure stack excerpt:"));
        assertTrue(userRepairMessage.contains("sessionInfoProvider0.setDestSession(failing statement)"));
        assertTrue(userRepairMessage.contains("Receiver setup note: the receiver variable 'sessionInfoProvider0'"));
        assertTrue(userRepairMessage.contains("has no visible earlier initialization in the parsed test excerpt"));
        assertTrue(userRepairMessage.contains("SessionInfoProvider __llm_fallback... = null"));
        assertTrue(userRepairMessage.contains("fallback-based collaborator chain"));
    }

    @Test
    void timeoutExecutionErrorIncludesTimedOutStatementContext() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                tc.addStatement(new IntPrimitiveStatement(tc, 2));
                return Collections.singletonList(new ParseResult(tc, "testTimeout"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    TestCaseExecutor.TimeoutExceeded timeout = new TestCaseExecutor.TimeoutExceeded(
                            "Test execution timed out in worker thread TEST_EXECUTION_THREAD_0 [state=WAITING]",
                            new StackTraceElement[]{
                                    new StackTraceElement("java.util.concurrent.locks.LockSupport",
                                            "park", "LockSupport.java", 211)
                            },
                            1,
                            "int int1 = 2;");
                    result.reportNewThrownException(testCase.size(), timeout);
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Timed out statement (index 1, zero-based):"));
        assertTrue(userRepairMessage.contains("int int1 = 2;"));
        assertTrue(userRepairMessage.contains("Parsed test code excerpt:"));
    }

    @Test
    void executionContextNotesWhenLaterAssertThrowsWasNeverReached() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                tc.addStatement(new IntPrimitiveStatement(tc, 2));
                tc.addStatement(new UninterpretedStatement(tc,
                        "org.junit.jupiter.api.Assertions.assertThrows(java.lang.RuntimeException.class, () -> helper());"));
                return Collections.singletonList(new ParseResult(tc, "testSetupFailsBeforeAssertThrows"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(1, new NullPointerException("boom"));
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Failing statement (index 1, zero-based):"));
        assertTrue(userRepairMessage.contains("Assertion reachability note:"));
        assertTrue(userRepairMessage.contains("later assertThrows(...) statement exists at index 2 (zero-based)"));
        assertTrue(userRepairMessage.contains("so that assertion was never reached"));
    }

    @Test
    void parsedTestCodeExcerptUsesInternalStatementIndicesAndRawStatementCode() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                VariableReference nullRef = tc.addStatement(new org.evosuite.testcase.statements.NullStatement(tc, Object.class));
                Map<String, VariableReference> bindings = new LinkedHashMap<>();
                bindings.put("__llm_fallback0", nullRef);
                tc.addStatement(new UninterpretedStatement(
                        tc,
                        java.util.List.class,
                        "java.util.List __llm_fallback0 = null;",
                        bindings,
                        "__llm_fallback0"));
                return Collections.singletonList(new ParseResult(tc, "testIndexedExcerpt"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(1, new NullPointerException("boom"));
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("// [0]"));
        assertTrue(userRepairMessage.contains("// [1]"));
        assertTrue(userRepairMessage.contains("java.util.List __llm_fallback0 = null;"));
        assertFalse(userRepairMessage.contains("= __llm_fallback0;"),
                "Excerpt should show raw internal statement code, not extra alias lines from TestCodeVisitor");
    }

    @Test
    void nullPointerExecutionErrorWithSyntheticLocalAddsGenericReceiverGuidance() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testSyntheticLocalNpe"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(0, new NullPointerException(
                            "Cannot invoke \"map.TileMap.breadthFirstSearch(map.Node, map.Node)\" because "
                                    + "\"<local1>\" is null"));
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("compiler-synthetic"));
        assertTrue(userRepairMessage.contains("explicitly initialize each call receiver"));
        assertTrue(userRepairMessage.contains("Do not assume complex SUT methods succeed"));
        assertFalse(userRepairMessage.contains("object named '<local1>'"));
    }

    @Test
    void streamRelatedExecutionErrorAddsStreamPreconditionRepairInstructions() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testStream"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(0,
                            new IllegalArgumentException("InputStream cannot be null"));
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Stream precondition hint"));
        assertTrue(userRepairMessage.contains("ByteArrayInputStream"));
        assertTrue(userRepairMessage.contains("StringReader"));
    }

    @Test
    void reflectiveInvocationMismatchAddsWrapperRepairInstructions() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testReflectiveInvocation"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(0, new AssertionError(
                            "Unexpected exception type thrown, expected: "
                                    + "<com.atlassw.tools.eclipse.checkstyle.util.CheckstylePluginException> "
                                    + "but was: <java.lang.reflect.InvocationTargetException>"));
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Reflection wrapper hint:"));
        assertTrue(userRepairMessage.contains("expect InvocationTargetException from assertThrows"));
        assertTrue(userRepairMessage.contains("assert the wrapped cause type/message"));
        assertTrue(userRepairMessage.contains("Prefer direct invocation of accessible SUT methods over reflection."));
    }

    @Test
    void reflectiveAssertThrowsFallbackErrorsAddSpecificRepairInstructions() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                tc.addStatement(new IntPrimitiveStatement(tc, 2));
                return Collections.singletonList(new ParseResult(tc, "testReflectiveFallback"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(1, new RuntimeException(
                            "shaded.org.evosuite.testcase.execution.ExecutableSnippetEngine$SnippetCompilationException "
                                    + "- Snippet compilation interrupted\n"
                                    + "Failing statement (index 1):\n"
                                    + "java.lang.reflect.InvocationTargetException __llm_fallback0 = null;\n"
                                    + "Parsed test code excerpt:\n```java\n"
                                    + "ChangeNodeLevelAction changeNodeLevelAction0 = new ChangeNodeLevelAction();\n"
                                    + "java.lang.reflect.InvocationTargetException __llm_fallback0 = null;\n"
                                    + "InvocationTargetException invocationTargetException0 = __llm_fallback0;\n"
                                    + "```"));
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Reflective assertThrows fallback hint:"));
        assertTrue(userRepairMessage.contains("parser-generated fallback"));
        assertTrue(userRepairMessage.contains("Replace the entire failing reflective assertion fragment"));
        assertTrue(userRepairMessage.contains("capture InvocationTargetException with Assertions.assertThrows(...)"));
        assertTrue(userRepairMessage.contains("invocationTargetException.getCause()"));
    }

    @Test
    void typedCollaboratorFallbackErrorsAddSpecificRepairInstructions() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testTypedFallback"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(0, new RuntimeException(
                            "shaded.org.evosuite.testcase.execution.ExecutableSnippetEngine$SnippetCompilationException "
                                    + "- Snippet compilation interrupted\n"
                                    + "Failing statement (index 0):\n"
                                    + "net.sourceforge.squirrel_sql.plugins.dbcopy.SessionInfoProvider __llm_fallback0 = null;\n"
                                    + "Parsed test code excerpt:\n```java\n"
                                    + "net.sourceforge.squirrel_sql.plugins.dbcopy.SessionInfoProvider __llm_fallback0 = null;\n"
                                    + "SessionInfoProvider sessionInfoProvider0 = __llm_fallback0;\n"
                                    + "IApplication iApplication0 = mock(IApplication.class, new ViolatedAssumptionAnswer());\n"
                                    + "PasteTableUtil.excePasteTable(null, iApplication0, \"TBL\");\n"
                                    + "sessionInfoProvider0.getDestSession();\n"
                                    + "```"));
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Collaborator fallback hint:"));
        assertTrue(userRepairMessage.contains("SessionInfoProvider __llm_fallback"));
        assertTrue(userRepairMessage.contains("parser-generated placeholder"));
        assertTrue(userRepairMessage.contains("passing null instead of a real SessionInfoProvider value"));
        assertTrue(userRepairMessage.contains("Mockito.mock(SessionInfoProvider.class, new ViolatedAssumptionAnswer())"));
    }

    @Test
    void unresolvedConstructorFallbackAddsAvailableConstructorGuidance() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new UninterpretedStatement(tc, "JTable jTable0 = new JTable();"));
                tc.addStatement(new UninterpretedStatement(tc,
                        "net.sourceforge.squirrel_sql.fw.datasetviewer.ColumnDisplayDefinition __llm_fallback14 = null;"));
                tc.addStatement(new UninterpretedStatement(tc,
                        "DataTypeBigDecimal dataTypeBigDecimal0 = new DataTypeBigDecimal(jTable0, columnDisplayDefinition0);"));
                ParseResult parseResult = new ParseResult(tc, "testValidateAndConvertInPopupDelegates");
                parseResult.addDiagnostic(new ParseDiagnostic(
                        ParseDiagnostic.Severity.WARNING,
                        "No matching constructor: No matching constructor found for "
                                + "net.sourceforge.squirrel_sql.fw.datasetviewer.ColumnDisplayDefinition with args "
                                + "(int, java.lang.String, java.lang.String, java.lang.String, int, java.lang.String, "
                                + "boolean, int, int, int, boolean, boolean, boolean, "
                                + "net.sourceforge.squirrel_sql.fw.datasetviewer.cellcomponent.DataTypeBigDecimal$DialectType). "
                                + "Available constructors: ColumnDisplayDefinition(int, String); "
                                + "ColumnDisplayDefinition(int, String, String, String, int, String, boolean, int, int, int, "
                                + "boolean, boolean, boolean, net.sourceforge.squirrel_sql.fw.dialects.DialectType, "
                                + "ResultMetaDataTable)",
                        2,
                        "new ColumnDisplayDefinition(0, \"\", \"\", \"\", 0, \"\", false, 0, 10, 2, false, false, false, "
                                + "net.sourceforge.squirrel_sql.fw.datasetviewer.cellcomponent.DataTypeBigDecimal.DialectType.GENERIC)"));
                return Collections.singletonList(parseResult);
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    NullPointerException npe = new NullPointerException(
                            "Cannot invoke \"net.sourceforge.squirrel_sql.fw.datasetviewer.ColumnDisplayDefinition.isNullable()\" because \"colDef\" is null");
                    npe.setStackTrace(new StackTraceElement[]{
                            new StackTraceElement(
                                    "net.sourceforge.squirrel_sql.fw.datasetviewer.cellcomponent.DataTypeBigDecimal",
                                    "<init>",
                                    "DataTypeBigDecimal.java",
                                    124)
                    });
                    result.reportNewThrownException(2, npe);
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Fallback origin note:"));
        assertTrue(userRepairMessage.contains("Available constructors:"));
        assertTrue(userRepairMessage.contains("net.sourceforge.squirrel_sql.fw.dialects.DialectType"));
        assertTrue(userRepairMessage.contains("Use one of the listed existing constructors exactly"));
    }

    @Test
    void illegalArgumentExecutionErrorAddsArgumentPreconditionRepairInstructions() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testIllegalArgument"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(0,
                            new IllegalArgumentException("The scheme has no lines."));
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Argument precondition hint"));
        assertTrue(userRepairMessage.contains("minimally valid non-empty values"));
        assertTrue(userRepairMessage.contains("assertThrows"));
    }

    @Test
    void npeExecutionErrorAddsReceiverTypeAwareRepairInstructions() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testNpeModel"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(0, new NullPointerException(
                            "Cannot invoke \"org.w3c.dom.Element.getAttribute(String)\" because \"model\" is null"));
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Detected null dereference"));
        assertTrue(userRepairMessage.contains("org.w3c.dom.Element.getAttribute(String)"));
        assertTrue(userRepairMessage.contains("model"));
        assertTrue(userRepairMessage.contains("DocumentBuilderFactory"));
        assertTrue(userRepairMessage.contains("createElement(\"model\")"));
    }

    @Test
    void npeExecutionErrorClarifiesInternalSutReceiverNames() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testInternalReceiverNpe"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(0, new RuntimeException(
                            "java.lang.NullPointerException - Cannot invoke "
                                    + "\"net.sourceforge.squirrel_sql.client.session.ISession.getObjectTreeAPIOfActiveSessionWindow()\" "
                                    + "because \"destSession\" is null\n"
                                    + "Parsed test code excerpt:\n```java\n"
                                    + "SessionInfoProvider sessionInfoProvider0 = mock(SessionInfoProvider.class, new ViolatedAssumptionAnswer());\n"
                                    + "doReturn(null).when(sessionInfoProvider0).getDestSession();\n"
                                    + "IApplication iApplication0 = mock(IApplication.class, new ViolatedAssumptionAnswer());\n"
                                    + "PasteTableUtil.excePasteTable(sessionInfoProvider0, iApplication0, \"ANY\");\n"
                                    + "```"));
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("The JVM-reported null receiver name 'destSession' comes from inside the failing SUT call path"));
        assertTrue(userRepairMessage.contains("not necessarily a direct test variable"));
        assertTrue(userRepairMessage.contains("likely a parameter or local variable inside the SUT"));
        assertTrue(userRepairMessage.contains("Here, 'destSession' is the null receiver inside the SUT call"));
        assertTrue(userRepairMessage.contains("likely as a parameter or local variable there"));
        assertTrue(userRepairMessage.contains("Stub or initialize the upstream collaborator/return value"));
        assertFalse(userRepairMessage.contains("ensure the object named 'destSession' is initialized before invoking the SUT"));
    }

    @Test
    void anonymousInterfaceCompileErrorAddsMockBasedRepairInstructions() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testAnonymousInterfaceCompileFailure"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(0, new RuntimeException(
                            "shaded.org.evosuite.testcase.execution.ExecutableSnippetEngine$SnippetCompilationException "
                                    + "- Snippet compilation failed for EvosuiteSnippet_1:\n"
                                    + "/tmp/EvosuiteSnippet_1.java:42: error: <anonymous EvosuiteSnippet_1$1> is not abstract "
                                    + "and does not override abstract method getMultipleWindowsHandler() in IApplication\n"
                                    + "net.sourceforge.squirrel_sql.client.IApplication app = new "
                                    + "net.sourceforge.squirrel_sql.client.IApplication() {\n"
                                    + "    @Override public void showErrorDialog(String message) { fail(\"boom\"); }\n"
                                    + "};\n"
                                    + "1 error\n"
                                    + "Parsed test code excerpt:\n```java\n"
                                    + "net.sourceforge.squirrel_sql.client.IApplication app = new "
                                    + "net.sourceforge.squirrel_sql.client.IApplication() {\n"
                                    + "    @Override\n"
                                    + "    public void showErrorDialog(String message) {\n"
                                    + "        fail(\"showErrorDialog should not be called\");\n"
                                    + "    }\n"
                                    + "};\n"
                                    + "```\n"));
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Anonymous implementation repair hint:"));
        assertTrue(userRepairMessage.contains("partial anonymous implementation of 'net.sourceforge.squirrel_sql.client.IApplication'"));
        assertTrue(userRepairMessage.contains("Do NOT write new IApplication() { ... } just to override one method."));
        assertTrue(userRepairMessage.contains("Mockito.mock(net.sourceforge.squirrel_sql.client.IApplication.class, new ViolatedAssumptionAnswer())"));
        assertTrue(userRepairMessage.contains("verify the method is never invoked instead of calling fail(...) inside an anonymous override"));
    }

    @Test
    void repeatedDependencyMissingErrorUsesDedicatedStopDiagnostic() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                return Collections.singletonList(new ParseResult(new DefaultTestCase(), "test"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(0, new NoClassDefFoundError("com/example/MissingFramework"));
                    return result;
                },
                3);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        assertTrue(result.getDiagnostics().stream()
                .anyMatch(d -> d.contains("dependency-missing error persisted")));
        assertFalse(result.getDiagnostics().stream()
                .anyMatch(d -> d.contains("identical error repeated")));
        verify(llmService, atMost(1)).query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());
    }

    @Test
    void repairPromptUsesConfiguredKeepPolicy() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\nbroken\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                ParseResult error = new ParseResult(new DefaultTestCase(), "test");
                error.addDiagnostic(new ParseDiagnostic(ParseDiagnostic.Severity.ERROR,
                        "syntax error", 1, "broken"));
                return Collections.singletonList(error);
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> new ExecutionResult(testCase),
                1,
                null,
                null,
                TestRepairLoop.RepairOptions.forAssertionPolicy(true));

        RepairResult result = loop.attemptParse(
                "broken",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertFalse(userRepairMessage.contains("Do NOT include assertions"));
    }

    @Test
    void normalizeErrorReplacesLineNumbers() {
        assertEquals(
                "ERROR (line N): Failed at position N",
                TestRepairLoop.normalizeError("ERROR (line 42): Failed at position 7"));
        assertEquals(
                "ERROR (line N): something\nERROR (line N): other",
                TestRepairLoop.normalizeError("ERROR (line 10): something\nERROR (line 20): other"));
        assertEquals("", TestRepairLoop.normalizeError(null));
        assertEquals("no numbers here", TestRepairLoop.normalizeError("no numbers here"));
    }

    @Test
    void unfixableErrorClassifierAllowsDependencyMissingForRepair() {
        assertFalse(TestRepairLoop.isUnfixableError(
                "Execution error in test 'x': java.lang.NoClassDefFoundError - com/example/Missing"));
        assertTrue(TestRepairLoop.isUnfixableError(
                "Execution error in test 'x': java.lang.UnsatisfiedLinkError - native"));
        assertTrue(TestRepairLoop.isUnfixableError(
                "Execution error in test 'x': shaded.org.evosuite.testcase.execution.ExecutableSnippetEngine$SnippetCompilationException "
                        + "- Snippet compilation failed for EvosuiteSnippet_1: error: error reading /sf110/105_freemind/lib/batik-squiggle.jar; "
                        + "java.net.URISyntaxException: Illegal character in path at index 29: file:/sf110/105_freemind/lib/\\"));
    }

    @Test
    void snippetCompilerInfrastructureErrorsDoNotTriggerLlmRepair() {
        LlmService llmService = mock(LlmService.class);
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                return Collections.singletonList(new ParseResult(new DefaultTestCase(), "test"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(0, new RuntimeException(
                            "shaded.org.evosuite.testcase.execution.ExecutableSnippetEngine$SnippetCompilationException "
                                    + "- Snippet compilation failed for EvosuiteSnippet_1: error: error reading "
                                    + "/sf110/105_freemind/lib/batik-squiggle.jar; java.net.URISyntaxException: "
                                    + "Illegal character in path at index 29: file:/sf110/105_freemind/lib/\\"));
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        assertTrue(result.getDiagnostics().stream()
                .anyMatch(d -> d.contains("Skipped repair: unfixable environment error")));
        verify(llmService, never()).query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());
    }

    @Test
    void initializationFailureClassifierDetectsInitializerErrors() {
        assertTrue(TestRepairLoop.isInitializationFailureError(
                "Execution error: java.lang.ExceptionInInitializerError - null"));
        assertTrue(TestRepairLoop.isInitializationFailureError(
                "Execution error: java.lang.NoClassDefFoundError - Could not initialize class a.b.C"));
        assertTrue(TestRepairLoop.isInitializationFailureError(
                "Execution error: java.awt.AWTError - Local GraphicsEnvironment must not be null"));
        assertFalse(TestRepairLoop.isInitializationFailureError(
                "Execution error: java.lang.NullPointerException - x is null"));
    }

    @Test
    void awtInitializationErrorAddsGuiSpecificRepairInstructions() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testAwt"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(0,
                            new AWTError("Local GraphicsEnvironment must not be null"));
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Initialization/dependency failure hint"));
        assertTrue(userRepairMessage.contains("AWT/Swing initialization failed in headless execution"));
    }

    @Test
    void instantiationFailureClassifierDetectsInstantiationErrors() {
        assertTrue(TestRepairLoop.isInstantiationFailureError(
                "Execution error: java.lang.InstantiationException - null"));
        assertTrue(TestRepairLoop.isInstantiationFailureError(
                "compile error: X is abstract; cannot be instantiated"));
        assertFalse(TestRepairLoop.isInstantiationFailureError(
                "Execution error: java.lang.NullPointerException - x is null"));
    }

    @Test
    void mockingMisuseClassifierDetectsMockitoMisuseErrors() {
        assertTrue(TestRepairLoop.isMockingMisuseError(
                "Execution error: org.mockito.exceptions.misusing.NotAMockException - ..."));
        assertTrue(TestRepairLoop.isMockingMisuseError(
                "Execution error: org.mockito.exceptions.misusing.UnfinishedStubbingException - ..."));
        assertFalse(TestRepairLoop.isMockingMisuseError(
                "Execution error: java.lang.NullPointerException - x is null"));
    }

    @Test
    void instantiationExecutionErrorAddsInstantiationRepairInstructions() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testInstantiation"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(0, new InstantiationException());
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Instantiation failure hint"));
        assertTrue(userRepairMessage.contains("do NOT instantiate abstract classes or interfaces"));
        assertTrue(userRepairMessage.contains("use a known concrete subtype or a mock"));
    }

    @Test
    void mockingMisuseExecutionErrorAddsMockitoRepairInstructions() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testMockito"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(0, new RuntimeException(
                            "org.mockito.exceptions.misusing.NotAMockException"));
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Mockito usage hint"));
        assertTrue(userRepairMessage.contains("actual mocks/spies"));
        assertTrue(userRepairMessage.contains("Do NOT stub or verify real objects"));
        assertTrue(userRepairMessage.contains("Mockito.mock(Type.class)"));
    }

    @Test
    void contextSpecificRepairFactsSuggestConcreteSubtypesForNonInstantiableTypes() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testContext"));
            }
        };

        String sutSummary = "// UserPreferences [abstract]\n"
                + "  concrete subtypes: DefaultUserPreferences(), FileUserPreferences()\n"
                + "// DefaultUserPreferences [class]\n";
        String llmResponse = "public class X {\n"
                + "  @org.junit.Test\n"
                + "  public void test0() {\n"
                + "    UserPreferences preferences = new UserPreferences();\n"
                + "  }\n"
                + "}";

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(0, new NullPointerException(
                            "Cannot invoke \"a.b.C.m()\" because \"preferences\" is null"));
                    return result;
                },
                1,
                null,
                sutSummary,
                TestRepairLoop.RepairOptions.defaults());

        RepairResult result = loop.attemptParse(
                llmResponse,
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Context-specific repair facts"));
        assertTrue(userRepairMessage.contains("Detected construction of non-instantiable type 'UserPreferences'"));
        assertTrue(userRepairMessage.contains("DefaultUserPreferences()"));
    }

    @Test
    void contextSpecificRepairFactsProvideSymbolConsistencyHints() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                ParseResult error = new ParseResult(new DefaultTestCase(), "test");
                error.addDiagnostic(new ParseDiagnostic(ParseDiagnostic.Severity.ERROR,
                        "cannot find symbol\nsymbol: class SearchControls", 1, "broken"));
                return Collections.singletonList(error);
            }
        };

        String sutSummary = "// SearchControls [class]\n";

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> new ExecutionResult(testCase),
                1,
                null,
                sutSummary,
                TestRepairLoop.RepairOptions.defaults());

        RepairResult result = loop.attemptParse(
                "broken",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Context-specific repair facts"));
        assertTrue(userRepairMessage.contains("Symbol 'SearchControls'"));
        assertTrue(userRepairMessage.contains("exact context form/package"));
    }

    @Test
    void partialSuccessKeepsExecutableTestsWhileRepairingFailingOnes() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        AtomicInteger parseCalls = new AtomicInteger();
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase passing = new DefaultTestCase();
                passing.addStatement(new IntPrimitiveStatement(passing, 1));
                ParseResult passResult = new ParseResult(passing, "testPassing");

                DefaultTestCase failing = new DefaultTestCase();
                failing.addStatement(new IntPrimitiveStatement(failing, 2));
                ParseResult failResult = new ParseResult(failing, "testFailing");

                if (parseCalls.getAndIncrement() == 0) {
                    return Arrays.asList(passResult, failResult);
                }

                DefaultTestCase repaired = new DefaultTestCase();
                repaired.addStatement(new IntPrimitiveStatement(repaired, 3));
                ParseResult repairedResult = new ParseResult(repaired, "testRepaired");
                return Collections.singletonList(repairedResult);
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    if (testCase.size() > 0 && testCase.getStatement(0) instanceof IntPrimitiveStatement) {
                        IntPrimitiveStatement stmt = (IntPrimitiveStatement) testCase.getStatement(0);
                        if (stmt.getValue() == 2) {
                            result.reportNewThrownException(0, new IllegalStateException("boom"));
                        }
                    }
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertTrue(result.isSuccess());
        assertEquals(2, result.getTestCases().size());
        assertTrue(result.getParseResults().stream().anyMatch(r -> "testPassing".equals(r.getOriginalMethodName())));
        assertTrue(result.getParseResults().stream().anyMatch(r -> "testRepaired".equals(r.getOriginalMethodName())));
        assertTrue(result.getDiagnostics().stream().anyMatch(d -> d.contains("Partial success: kept")));
    }

    @Test
    void droppedAtParseTriggersRepairAndIncludesAllDiagnostics() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        AtomicInteger parseCalls = new AtomicInteger();
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                if (parseCalls.getAndIncrement() == 0) {
                    // Three tests: one executable, one dropped at parse, one that will throw at execution.
                    DefaultTestCase executable = new DefaultTestCase();
                    executable.addStatement(new IntPrimitiveStatement(executable, 1));
                    ParseResult keptResult = new ParseResult(executable, "testKept");

                    ParseResult parseErrorResult = new ParseResult(new DefaultTestCase(), "testDroppedAtParse");
                    parseErrorResult.addDiagnostic(new ParseDiagnostic(
                            ParseDiagnostic.Severity.ERROR,
                            "Cannot resolve type: InventedType",
                            7,
                            "InventedType x = new InventedType();"));

                    DefaultTestCase willThrow = new DefaultTestCase();
                    willThrow.addStatement(new IntPrimitiveStatement(willThrow, 2));
                    ParseResult execErrorResult = new ParseResult(willThrow, "testDroppedAtExecution");

                    return Arrays.asList(keptResult, parseErrorResult, execErrorResult);
                }
                DefaultTestCase repaired = new DefaultTestCase();
                repaired.addStatement(new IntPrimitiveStatement(repaired, 3));
                return Collections.singletonList(new ParseResult(repaired, "testRepaired"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    if (testCase.size() > 0 && testCase.getStatement(0) instanceof IntPrimitiveStatement) {
                        IntPrimitiveStatement stmt = (IntPrimitiveStatement) testCase.getStatement(0);
                        if (stmt.getValue() == 2) {
                            result.reportNewThrownException(0, new IllegalStateException("boom"));
                        }
                    }
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertTrue(result.isSuccess());
        // Kept test + repaired test both survive.
        assertTrue(result.getParseResults().stream().anyMatch(r -> "testKept".equals(r.getOriginalMethodName())));
        assertTrue(result.getParseResults().stream().anyMatch(r -> "testRepaired".equals(r.getOriginalMethodName())));

        // Repair must have been invoked exactly once (one dropped-at-parse + one dropped-at-execution).
        ArgumentCaptor<List<LlmMessage>> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());

        // The repair prompt (the last user message) must carry diagnostics for
        // both dropped-at-parse and dropped-at-execution tests, plus the kept test.
        List<LlmMessage> conversation = conversationCaptor.getValue();
        String repairPrompt = conversation.get(conversation.size() - 1).getContent();
        assertTrue(repairPrompt.contains("testKept"),
                "repair prompt should mention the executable test name");
        assertTrue(repairPrompt.contains("testDroppedAtParse"),
                "repair prompt should mention the parse-failed test name");
        assertTrue(repairPrompt.contains("Cannot resolve type: InventedType"),
                "repair prompt should include the parse ERROR diagnostic text");
        assertTrue(repairPrompt.contains("testDroppedAtExecution"),
                "repair prompt should mention the execution-failed test name");
        assertTrue(repairPrompt.contains("IllegalStateException"),
                "repair prompt should include the execution exception class");
        assertTrue(repairPrompt.contains("Keep the executable tests verbatim"),
                "repair prompt should instruct the LLM to preserve executable tests");
    }

    @Test
    void allCleanAndExecutableReturnsSuccessWithoutRepair() {
        LlmService llmService = mock(LlmService.class);
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase testCase = new DefaultTestCase();
                testCase.addStatement(new IntPrimitiveStatement(testCase, 1));
                return Collections.singletonList(new ParseResult(testCase, "testClean"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> new ExecutionResult(testCase),
                2);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertTrue(result.isSuccess());
        verify(llmService, never()).query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());
    }

}
