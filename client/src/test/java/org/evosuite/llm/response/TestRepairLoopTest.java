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
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.assertion.PrimitiveAssertion;
import org.evosuite.testcase.statements.UninterpretedStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testparser.ParseDiagnostic;
import org.evosuite.testparser.ParseResult;
import org.evosuite.testparser.TestParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

}
