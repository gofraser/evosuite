/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.llm.response;

import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testparser.ParseDiagnostic;
import org.evosuite.testparser.ParseResult;
import org.evosuite.testparser.TestParser;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TestRepairLoopTest {

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
}
