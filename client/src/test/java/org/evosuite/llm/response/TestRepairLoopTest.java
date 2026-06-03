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
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestCodeVisitor;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.runtime.FalsePositiveException;
import org.evosuite.assertion.CodeAssertion;
import org.evosuite.assertion.PrimitiveAssertion;
import org.evosuite.testcase.statements.ArrayStatement;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.UninterpretedStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.variable.VariableReferenceImpl;
import org.evosuite.testparser.ParseDiagnostic;
import org.evosuite.testparser.ParseResult;
import org.evosuite.testparser.TestParser;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.awt.AWTError;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TestRepairLoopTest {

    private final boolean originalTruncationRecovery = Properties.LLM_ENABLE_TRUNCATION_RECOVERY;
    private final String originalTargetClass = Properties.TARGET_CLASS;

    @AfterEach
    void restoreProperties() {
        Properties.LLM_ENABLE_TRUNCATION_RECOVERY = originalTruncationRecovery;
        Properties.TARGET_CLASS = originalTargetClass;
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
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
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
    void compilationProbeKeepsSingleMockitoNamespace() throws Exception {
        LlmService llmService = mock(LlmService.class);
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);
        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                new TestParser(getClass().getClassLoader()),
                new LlmResponseParser(),
                expansionManager,
                testCase -> new ExecutionResult(testCase),
                0);

        Set<Class<?>> imports = new LinkedHashSet<>();
        imports.add(org.mockito.Mockito.class);
        imports.add(org.mockito.ArgumentMatchers.class);
        imports.add(java.util.Date.class);
        imports.add(java.sql.Date.class);

        ParseResult parseResult = new ParseResult(new DefaultTestCase(), "testPasteTable");
        Method buildProbe = TestRepairLoop.class.getDeclaredMethod(
                "buildCompilationProbeSource", ParseResult.class, String.class, Set.class);
        buildProbe.setAccessible(true);
        String source = (String) buildProbe.invoke(loop, parseResult, "Mockito.mock((Class) class0);", imports);

        assertTrue(source.contains("import org.mockito.Mockito;"), source);
        assertTrue(source.contains("import static org.mockito.Mockito.*;"), source);
        assertTrue(source.contains("import org.mockito.ArgumentMatchers;"), source);
        assertTrue(source.contains("import static org.mockito.ArgumentMatchers.*;"), source);
        assertTrue(source.contains("import java.util.Date;"), source);
        assertFalse(source.contains("import java.sql.Date;"), source);
        assertEquals(source.indexOf("import java.util.Date;"),
                source.lastIndexOf("import java.util.Date;"), source);
        assertEquals(source.indexOf("import org.mockito.Mockito;"),
                source.lastIndexOf("import org.mockito.Mockito;"), source);
        assertEquals(source.indexOf("import org.mockito.ArgumentMatchers;"),
                source.lastIndexOf("import org.mockito.ArgumentMatchers;"), source);
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
    void deadlineSkipReturnsPartialExecutableTestsWithoutRepairCall() {
        LlmService llmService = mock(LlmService.class);
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                ParseResult executable = new ParseResult(new DefaultTestCase(), "kept");
                ParseResult dropped = new ParseResult(new DefaultTestCase(), "dropped");
                dropped.addDiagnostic(new ParseDiagnostic(ParseDiagnostic.Severity.ERROR,
                        "syntax error", 1, "broken"));
                return Arrays.asList(executable, dropped);
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
                LlmFeature.TEST_REPAIR,
                System.nanoTime() + 1L);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getParseResults().size());
        assertTrue(result.getDiagnostics().stream()
                .anyMatch(d -> d.contains("sync deadline too close")));
        verify(llmService, never()).query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());
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
    void noTestMethodsRepairExplainsMalformedSlashImports() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");

        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                if (sourceCode.contains("import br/com/jnfe/base/service.SimpleSecurityHandlerBean;")) {
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

        RepairResult result = loop.attemptParse(
                "```java\n"
                        + "import static org.junit.jupiter.api.Assertions.*;\n\n"
                        + "import br/com/jnfe/base/service.SimpleSecurityHandlerBean;\n"
                        + "import br/com/jnfe/base/service.SecurityCallBack;\n"
                        + "import org.junit.jupiter.api.Test;\n\n"
                        + "public class SimpleSecurityHandlerBeanTest {\n"
                        + "    @Test\n"
                        + "    void test0() {\n"
                        + "        assertDoesNotThrow(SimpleSecurityHandlerBean::new);\n"
                        + "    }\n"
                        + "}\n"
                        + "```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertTrue(result.isSuccess());
        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> sentConversation = conversationCaptor.getValue();
        String userRepairMessage = sentConversation.get(sentConversation.size() - 1).getContent();
        assertTrue(userRepairMessage.contains("Parser produced no test methods."));
        assertTrue(userRepairMessage.contains("Detected malformed import statement(s):"));
        assertTrue(userRepairMessage.contains("import br/com/jnfe/base/service.SimpleSecurityHandlerBean;"));
        assertTrue(userRepairMessage.contains("Java import declarations must use package dots, not file-path slashes"));
        assertTrue(userRepairMessage.contains("No-test-method parsing hint:"));
        assertTrue(userRepairMessage.contains("invalid Java import syntax with file-path separators instead of package dots"));
        assertTrue(userRepairMessage.contains("import br.com.jnfe.base.service.SimpleSecurityHandlerBean;"));
    }

    @Test
    void aliasedImportsAreSanitizedBeforeReachingParser() {
        // LlmResponseParser rewrites Kotlin/Scala-style alias imports into FQN
        // references before any parser sees the source, so the alias never
        // reaches TestRepairLoop and no repair call is needed. This test pins
        // that behavior end-to-end; the alias-import diagnostic in
        // appendNoTestMethodsRepairInstructions is a defensive remnant for
        // edge cases where the sanitizer is bypassed (see LlmResponseParserTest
        // for the unit-level coverage).
        LlmService llmService = mock(LlmService.class);
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        java.util.concurrent.atomic.AtomicReference<String> sourceSeenByParser =
                new java.util.concurrent.atomic.AtomicReference<>();
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                sourceSeenByParser.set(sourceCode);
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

        RepairResult result = loop.attemptParse(
                "```java\n"
                        + "import net.sourceforge.beanbin.query.Query as BeanBinQuery;\n"
                        + "import org.junit.jupiter.api.Test;\n\n"
                        + "public class EJB3SearcherTest {\n"
                        + "    @Test\n"
                        + "    void test0() {\n"
                        + "        BeanBinQuery q = new BeanBinQuery();\n"
                        + "    }\n"
                        + "}\n"
                        + "```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertTrue(result.isSuccess(),
                "sanitized alias import should yield a successful parse on the first attempt");
        verify(llmService, never()).query(anyList(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        String parserInput = sourceSeenByParser.get();
        assertNotNull(parserInput);
        assertFalse(parserInput.contains(" as BeanBinQuery"),
                "alias import line must be removed before parsing");
        assertFalse(parserInput.contains("BeanBinQuery"),
                "alias references must be rewritten to fully qualified names before parsing");
        assertTrue(parserInput.contains("net.sourceforge.beanbin.query.Query q ="),
                "alias usages should be rewritten to FQN at the call site");
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
        assertTrue(result.getDiagnostics().stream().anyMatch(
                d -> d.contains("identical-error escalation hint injected")));
        // Identical-error escalation grants exactly one bonus turn before
        // bailing, so the loop makes the initial repair plus one escalation
        // repair (2 queries) and then stops, instead of exhausting maxAttempts.
        verify(llmService, atMost(2)).query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());
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
    void sanitizerRemovesMockitoVerifySnippets() {
        DefaultTestCase testCase = new DefaultTestCase();
        testCase.addStatement(new UninterpretedStatement(testCase, "verify(resultSet0).getBigDecimal(1);"));
        testCase.addStatement(new UninterpretedStatement(testCase, "Mockito.verifyNoMoreInteractions(resultSet0);"));
        assertEquals(2, testCase.size());

        int removed = LlmAssertionSanitizer.sanitize(testCase);

        assertEquals(2, removed);
        assertEquals(0, testCase.size());
    }

    @Test
    void sanitizerDropsCheckedDoThrowOnMethodWithoutDeclaredException() {
        DefaultTestCase testCase = new DefaultTestCase();
        Map<String, VariableReference> bindings = new LinkedHashMap<>();
        bindings.put("target0", new VariableReferenceImpl(testCase, CheckedStubTarget.class));
        testCase.addStatement(new UninterpretedStatement(
                testCase,
                "doThrow(new java.io.IOException(\"boom\")).when(target0).noThrows(anyString());",
                bindings));
        assertEquals(1, testCase.size());

        int removed = LlmAssertionSanitizer.sanitize(testCase);

        assertEquals(1, removed);
        assertEquals(0, testCase.size());
    }

    @Test
    void sanitizerKeepsCheckedDoThrowWhenMethodDeclaresException() {
        DefaultTestCase testCase = new DefaultTestCase();
        Map<String, VariableReference> bindings = new LinkedHashMap<>();
        bindings.put("target0", new VariableReferenceImpl(testCase, CheckedStubTarget.class));
        testCase.addStatement(new UninterpretedStatement(
                testCase,
                "doThrow(new java.io.IOException(\"boom\")).when(target0).declaresChecked(anyString());",
                bindings));
        assertEquals(1, testCase.size());

        int removed = LlmAssertionSanitizer.sanitize(testCase);

        assertEquals(0, removed);
        assertEquals(1, testCase.size());
    }

    @Test
    void bruteForceSalvageKeepsConstructorOnlyTargetCoverageAndDropsAssertionSnippets() throws Exception {
        Properties.TARGET_CLASS = Object.class.getName();

        LlmService llmService = mock(LlmService.class);
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);
        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                new TestParser(getClass().getClassLoader()),
                new LlmResponseParser(),
                expansionManager,
                testCase -> new ExecutionResult(testCase),
                0);

        DefaultTestCase testCase = new DefaultTestCase();
        GenericConstructor objectConstructor = new GenericConstructor(Object.class.getConstructor(), Object.class);
        testCase.addStatement(new ConstructorStatement(
                testCase, objectConstructor, Collections.<VariableReference>emptyList()));
        testCase.addStatement(new UninterpretedStatement(testCase, "assertNotNull(object0);"));

        Method salvage = TestRepairLoop.class.getDeclaredMethod(
                "performBruteForceSalvage", org.evosuite.testcase.TestCase.class);
        salvage.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Optional<TestCase> result = (java.util.Optional<TestCase>) salvage.invoke(loop, testCase);

        assertTrue(result.isPresent());
        TestCase salvaged = result.get();
        assertEquals(1, salvaged.size());
        assertInstanceOf(ConstructorStatement.class, salvaged.getStatement(0));
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
        assertTrue(userRepairMessage.contains("assertThrows(NullPointerException.class"));
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
                        "Object __llm_fallback0 = null;"));
                tc.addStatement(new UninterpretedStatement(tc,
                        "java.util.List sessionInfoProvider0 = (java.util.List) __llm_fallback0;"));
                tc.addStatement(new UninterpretedStatement(tc,
                        "sessionInfoProvider0.size();"));
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
                    result.reportNewThrownException(2, wrapper);
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
        assertTrue(userRepairMessage.contains("sessionInfoProvider0"));
        assertTrue(userRepairMessage.contains("The following tests were dropped because they failed to parse;")
                        || userRepairMessage.contains("The following tests parsed but failed at execution;"));
        assertTrue(userRepairMessage.contains("__llm_fallback"));
        assertTrue(userRepairMessage.contains("fallback-based collaborator chain")
                        || userRepairMessage.contains("parser-generated placeholder"));
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
    void parsedExcerptPreservesDataflowAcrossRenderedStatements() throws Exception {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                try {
                    DefaultTestCase tc = new DefaultTestCase();

                    ArrayStatement arrayStatement = new ArrayStatement(tc, Object[].class, 2);
                    ArrayReference objectArray0 = (ArrayReference) tc.addStatement(arrayStatement);

                    GenericConstructor objectConstructor =
                            new GenericConstructor(Object.class.getConstructor(), Object.class);
                    VariableReference object0 = tc.addStatement(new ConstructorStatement(
                            tc, objectConstructor, Collections.<VariableReference>emptyList()));
                    tc.addStatement(new AssignmentStatement(tc, new ArrayIndex(tc, objectArray0, 0), object0));

                    VariableReference object1 = tc.addStatement(new ConstructorStatement(
                            tc, objectConstructor, Collections.<VariableReference>emptyList()));
                    tc.addStatement(new AssignmentStatement(tc, new ArrayIndex(tc, objectArray0, 1), object1));

                    Method asListMethod = Arrays.class.getMethod("asList", Object[].class);
                    GenericMethod asList = new GenericMethod(asListMethod, Arrays.class);
                    VariableReference list0 = tc.addStatement(new MethodStatement(
                            tc, asList, null, Collections.singletonList(objectArray0)));

                    Method sizeMethod = List.class.getMethod("size");
                    GenericMethod size = new GenericMethod(sizeMethod, List.class);
                    tc.addStatement(new MethodStatement(
                            tc, size, list0, Collections.<VariableReference>emptyList()));

                    return Collections.singletonList(new ParseResult(tc, "testRenderedExcerpt"));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    ClassCastException cast = new ClassCastException(
                            "class java.lang.Object cannot be cast to class org.eclipse.core.resources.IProject");
                    cast.setStackTrace(new StackTraceElement[]{
                            new StackTraceElement(
                                    "com.atlassw.tools.eclipse.checkstyle.builder.CheckstyleBuilder",
                                    "buildProjects",
                                    "CheckstyleBuilder.java",
                                    134)
                    });
                    result.reportNewThrownException(6, cast);
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
        assertTrue(userRepairMessage.contains("Arrays.asList("));
        assertFalse(userRepairMessage.contains("// [5]\nArrays.asList("));
        assertTrue(userRepairMessage.contains(".size();"));
    }

    @Test
    void staticMethodExecutionErrorDoesNotTreatTypeNameAsReceiverVariable() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new UninterpretedStatement(tc, "java.util.List list0 = null;"));
                tc.addStatement(new UninterpretedStatement(tc, "CheckstyleBuilder.buildProjects(list0);"));
                return Collections.singletonList(new ParseResult(tc, "testStaticCallReceiver"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    ClassCastException cast = new ClassCastException("boom");
                    cast.setStackTrace(new StackTraceElement[]{
                            new StackTraceElement(
                                    "com.atlassw.tools.eclipse.checkstyle.builder.CheckstyleBuilder",
                                    "buildProjects",
                                    "CheckstyleBuilder.java",
                                    134)
                    });
                    result.reportNewThrownException(1, cast);
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
        assertFalse(userRepairMessage.contains("Receiver setup note: the receiver variable 'CheckstyleBuilder'"));
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
                        "org.junit.jupiter.api.Assertions.assertThrows(java.lang.RuntimeException.class, "
                                + "() -> { throw new RuntimeException(); });"));
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
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                tc.addStatement(new UninterpretedStatement(tc, "java.util.List __llm_fallback0 = null;"));
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
                                + "ResultMetaDataTable) "
                                + "LLM_REPAIR_ACTION_REQUIRED: Use one of the listed existing constructors exactly.",
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
        assertTrue(userRepairMessage.contains("Available constructors:"));
        assertTrue(userRepairMessage.contains("net.sourceforge.squirrel_sql.fw.dialects.DialectType"));
        assertTrue(userRepairMessage.contains("Use one of the listed existing constructors exactly"));
    }

    @Test
    void namedTypedNullFallbackStillAddsAvailableConstructorGuidance() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new UninterpretedStatement(tc,
                        "org.firebirdsql.gds.impl.GDSHelper helper = null;"));
                tc.addStatement(new UninterpretedStatement(tc,
                        "FBParameterMetaData nullRef0 = null;"));
                tc.addStatement(new UninterpretedStatement(tc,
                        "Class<?> class0 = FBParameterMetaData.class;"));
                tc.addStatement(new UninterpretedStatement(tc,
                        "null.unwrap((Class) class0);"));
                ParseResult parseResult = new ParseResult(tc, "testUnwrapWrapper_ReturnsCastInstance");
                parseResult.addDiagnostic(new ParseDiagnostic(
                        ParseDiagnostic.Severity.WARNING,
                        "No matching constructor: No matching constructor found for "
                                + "org.firebirdsql.jdbc.FBParameterMetaData with args (). "
                                + "Available constructors: FBParameterMetaData(XSQLVAR[], GDSHelper) "
                                + "LLM_REPAIR_ACTION_REQUIRED: Use one of the listed existing constructors exactly.",
                        1,
                        "new FBParameterMetaData()"));
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
                    NullPointerException npe = new NullPointerException();
                    CodeUnderTestException wrapper = new CodeUnderTestException(npe);
                    wrapper.setStackTrace(new StackTraceElement[]{
                            new StackTraceElement("shaded.org.evosuite.testcase.statements.MethodStatement$1",
                                    "execute", "MethodStatement.java", 405),
                            new StackTraceElement("shaded.org.evosuite.testcase.statements.AbstractStatement",
                                    "exceptionHandler", "AbstractStatement.java", 180)
                    });
                    result.reportNewThrownException(3, wrapper);
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
        assertTrue(userRepairMessage.contains("FBParameterMetaData"));
        assertTrue(userRepairMessage.contains("Available constructors: FBParameterMetaData(XSQLVAR[], GDSHelper)"));
        assertTrue(userRepairMessage.contains("Use one of the listed existing constructors exactly"));
    }

    @Test
    void fallbackOriginNoteExplainsInventedHelperTypeCauseExplicitly() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new UninterpretedStatement(tc,
                        "Newzgrabber.BufferedCustomInputStream __llm_fallback2 = null;"));
                tc.addStatement(new UninterpretedStatement(tc,
                        "BufferedCustomInputStream bufferedCustomInputStream0 = __llm_fallback2;"));
                tc.addStatement(new UninterpretedStatement(tc,
                        "ByteArrayOutputStream byteArrayOutputStream0 = new ByteArrayOutputStream();"));
                tc.addStatement(new UninterpretedStatement(tc,
                        "Base64Decoder base64Decoder0 = new Base64Decoder(null, byteArrayOutputStream0);"));
                ParseResult parseResult = new ParseResult(tc, "testDecodeStreamThrowsIOExceptionWhenInputStreamFails");
                parseResult.addDiagnostic(new ParseDiagnostic(
                        ParseDiagnostic.Severity.WARNING,
                        "Cannot resolve class: InputStreamThatThrowsOnRead — Cannot resolve type: "
                                + "InputStreamThatThrowsOnRead LLM_REPAIR_ACTION_REQUIRED: do not invent local/helper "
                                + "types (e.g., Target, Input, Helper) in test code; instantiate only real "
                                + "SUT/JDK/dependency types from context, or pass null/Object when the API accepts it.",
                        1,
                        "new BufferedCustomInputStream(new InputStreamThatThrowsOnRead())"));
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
                    result.reportNewThrownException(0, new RuntimeException(
                            "Compilation failed:\n"
                                    + "Failing statement (index 0):\n"
                                    + "Newzgrabber.BufferedCustomInputStream __llm_fallback2 = null;\n"
                                    + "Parsed test code excerpt:\n```java\n"
                                    + "Newzgrabber.BufferedCustomInputStream __llm_fallback2 = null;\n"
                                    + "BufferedCustomInputStream bufferedCustomInputStream0 = __llm_fallback2;\n"
                                    + "ByteArrayOutputStream byteArrayOutputStream0 = new ByteArrayOutputStream();\n"
                                    + "Base64Decoder base64Decoder0 = new Base64Decoder(null, byteArrayOutputStream0);\n"
                                    + "assertThrows(java.io.IOException.class, base64Decoder0::decodeStream);\n"
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
        assertTrue(userRepairMessage.contains("LLM_REPAIR_ACTION_REQUIRED:"),
                "Expected explicit parser repair action marker: " + userRepairMessage);
        assertTrue(userRepairMessage.contains("do not invent local/helper types"),
                "Expected explicit parser repair action: " + userRepairMessage);
        assertTrue(userRepairMessage.contains("new BufferedCustomInputStream(new InputStreamThatThrowsOnRead())"),
                "Expected original source expression in repair note: " + userRepairMessage);
        assertTrue(userRepairMessage.contains("Replace invented/unknown types with existing SUT or JDK types."),
                "Expected concrete replacement guidance: " + userRepairMessage);
    }

    @Test
    void unresolvedInterfaceInstantiationFallbackAddsInterfaceGuidance() {
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
                        "SessionInfoProvider sessionInfoProvider0 = __llm_fallback0;"));
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                ParseResult parseResult = new ParseResult(tc, "testPasteTableRepair");
                parseResult.addDiagnostic(new ParseDiagnostic(
                        ParseDiagnostic.Severity.WARNING,
                        "No matching constructor: No matching constructor found for "
                                + "net.sourceforge.squirrel_sql.plugins.dbcopy.SessionInfoProvider with args () "
                                + "LLM_REPAIR_ACTION_REQUIRED: interface types cannot be instantiated directly; "
                                + "use a concrete subtype or Mockito mock.",
                        1,
                        "new SessionInfoProvider()"));
                return Collections.singletonList(parseResult);
            }
        };

        String sutSummary = "// SessionInfoProvider [interface]\n"
                + "  concrete subtypes: DBCopyPlugin()\n"
                + "// DBCopyPlugin [class]\n";

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    result.reportNewThrownException(2, new NullPointerException(
                            "Cannot invoke \"net.sourceforge.squirrel_sql.plugins.dbcopy.SessionInfoProvider.getDestSession()\" because \"sessionInfoProvider0\" is null"));
                    return result;
                },
                1,
                null,
                sutSummary,
                TestRepairLoop.RepairOptions.defaults());

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
        assertTrue(userRepairMessage.contains("SessionInfoProvider"));
        assertTrue(userRepairMessage.contains("interface types cannot be instantiated directly")
                        || userRepairMessage.contains("The type 'SessionInfoProvider' is an interface"));
        assertTrue(userRepairMessage.contains("Mockito mock")
                        || userRepairMessage.contains("Mockito.mock(SessionInfoProvider.class)"));
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
    void illegalArgumentNullSourceAddsActionEventPreconditionRepairInstructions() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testIllegalArgumentNullSource"));
            }
        };

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> {
                    ExecutionResult result = new ExecutionResult(testCase);
                    IllegalArgumentException ex = new IllegalArgumentException("null source");
                    ex.setStackTrace(new StackTraceElement[]{
                            new StackTraceElement("java.util.EventObject", "<init>", "EventObject.java", 57)
                    });
                    result.reportNewThrownException(0, ex);
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
        assertTrue(userRepairMessage.contains("null source"));
        assertTrue(userRepairMessage.contains("ActionEvent"));
        assertTrue(userRepairMessage.contains("new ActionEvent(null"));
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
        assertTrue(userRepairMessage.contains("anonymous implementation/subclass of 'net.sourceforge.squirrel_sql.client.IApplication'"));
        assertTrue(userRepairMessage.contains("Do NOT write new IApplication() { ... } just to override one method."));
        assertTrue(userRepairMessage.contains("Mockito.mock(net.sourceforge.squirrel_sql.client.IApplication.class, new ViolatedAssumptionAnswer())"));
        assertTrue(userRepairMessage.contains("verify the method is never invoked instead of calling fail(...) inside an anonymous override"));
    }

    @Test
    void malformedAnonymousAbstractClassSnippetAddsRepairInstructions() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testHandleReadIOExceptionIsCaughtAndDoesNotThrow"));
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
                                    + "- Snippet compilation failed for EvosuiteSnippet_8bd0:\n"
                                    + "/tmp/EvosuiteSnippet_8bd0.java:56: error: illegal start of type\n"
                                    + "        throw new IOException(\"read-failed\");\n"
                                    + "        ^\n"
                                    + "/tmp/EvosuiteSnippet_8bd0.java:59: error: class, interface, enum, or record expected\n"
                                    + "    return ch;\n"
                                    + "    ^\n"
                                    + "/tmp/EvosuiteSnippet_8bd0.java:60: error: class, interface, enum, or record expected\n"
                                    + "  }\n"
                                    + "  ^\n"
                                    + "3 errors\n"
                                    + "Failing statement (index 0, zero-based):\n"
                                    + "java.nio.channels.SocketChannel ch = new java.nio.channels.SocketChannel() {\n\n"
                                    + "    @Override\n"
                                    + "    public int read(ByteBuffer dst) throws IOException {\n"
                                    + "        throw new IOException(\"read-failed\");\n"
                                    + "    }\n"
                                    + "};\n"
                                    + "Parsed test code excerpt:\n```java\n"
                                    + "java.nio.channels.SocketChannel ch = new java.nio.channels.SocketChannel() {\n\n"
                                    + "    @Override\n"
                                    + "    public int read(ByteBuffer dst) throws IOException {\n"
                                    + "        throw new IOException(\"read-failed\");\n"
                                    + "    }\n"
                                    + "};\n"
                                    + "SocketChannel socketChannel0 = ch;\n"
                                    + "Connection connection0 = new Connection(socketChannel0);\n"
                                    + "connection0.handleRead(null);\n"
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
        assertTrue(userRepairMessage.contains("anonymous implementation/subclass of 'java.nio.channels.SocketChannel'"));
        assertTrue(userRepairMessage.contains("This is not a parser/import issue"));
        assertTrue(userRepairMessage.contains("'SocketChannel' is abstract and its constructors are not directly public"));
        assertTrue(userRepairMessage.contains("javac syntax errors"));
        assertTrue(userRepairMessage.contains("Mockito.mock(java.nio.channels.SocketChannel.class, new ViolatedAssumptionAnswer())"));
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
    void shadedRuntimeMockExceptionsAreRecognized() {
        // Synthesize a class name under the shaded mock package via a stub
        // exception class — we cannot easily instantiate one of the real
        // shaded classes from a unit test without pulling in the runtime, so
        // we check the helper directly on the canonical name path.
        assertTrue(TestRepairLoop.errorMentionsShadedRuntimeMockException(
                "Execution error in test 'x': "
                        + "shaded.org.evosuite.runtime.mock.java.lang.MockIllegalStateException "
                        + "- Workspace is closed."));
        assertFalse(TestRepairLoop.errorMentionsShadedRuntimeMockException(
                "Execution error in test 'x': java.lang.NullPointerException - foo"));
        assertFalse(TestRepairLoop.errorMentionsShadedRuntimeMockException(null));
        // isShadedRuntimeMockException walks the superclass chain; a plain
        // RuntimeException is NOT under the shaded prefix.
        assertFalse(TestRepairLoop.isShadedRuntimeMockException(new RuntimeException("not mock")));
        assertFalse(TestRepairLoop.isShadedRuntimeMockException(null));
    }

    @Test
    void identicalErrorEscalationHintNamesActionsAndShadedMockSignal() {
        String hintForShadedMock = TestRepairLoop.buildIdenticalErrorEscalationHint(
                "Execution error in test 'x': "
                        + "shaded.org.evosuite.runtime.mock.java.lang.MockIllegalStateException "
                        + "- Workspace is closed.");
        assertTrue(hintForShadedMock.contains("STOP"));
        assertTrue(hintForShadedMock.contains("Change the assertThrows expectation"));
        assertTrue(hintForShadedMock.contains("Replace the test"));
        assertTrue(hintForShadedMock.contains("Remove the test"));
        assertTrue(hintForShadedMock.contains("shaded.org.evosuite.runtime.mock"));

        String hintForPlainError = TestRepairLoop.buildIdenticalErrorEscalationHint(
                "Execution error in test 'x': java.lang.NullPointerException - foo");
        assertTrue(hintForPlainError.contains("STOP"));
        // No shaded-mock note when no such marker is present in the error.
        assertFalse(hintForPlainError.contains("shaded.org.evosuite.runtime.mock.*"));
    }

    @Test
    void identicalErrorEscalationGivesOneBonusTurnBeforeAborting() {
        LlmService llmService = mock(LlmService.class);
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        AtomicInteger calls = new AtomicInteger();
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                int call = calls.getAndIncrement();
                int line = 10 + call;
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
                testCase -> new ExecutionResult(testCase), 5);

        RepairResult result = loop.attemptParse("```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        assertTrue(result.getDiagnostics().stream().anyMatch(
                d -> d.contains("identical-error escalation hint injected")));
        assertTrue(result.getDiagnostics().stream().anyMatch(
                d -> d.contains("identical error repeated")));
        // Initial repair (1) + escalated bonus turn (1) = 2 queries; should
        // bail before exhausting the 5 attempt budget.
        verify(llmService, atMost(2)).query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());
    }

    @Test
    void escalationHintIsPrependedToRepairUserMessage() {
        LlmService llmService = mock(LlmService.class);
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        AtomicInteger calls = new AtomicInteger();
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                int call = calls.getAndIncrement();
                int line = 10 + call;
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
                testCase -> new ExecutionResult(testCase), 5);

        loop.attemptParse("```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, atLeast(2)).query(conversationCaptor.capture(),
                eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());
        // The second query is the bonus escalation turn; its tail user
        // message should carry the priority directive at the top.
        @SuppressWarnings("unchecked")
        List<LlmMessage> escalationConversation = conversationCaptor.getAllValues().get(1);
        String escalationMessage = escalationConversation.get(escalationConversation.size() - 1).getContent();
        assertTrue(escalationMessage.startsWith("=== PRIORITY DIRECTIVE ==="),
                "expected escalation hint at top, got: " + escalationMessage.substring(0,
                        Math.min(120, escalationMessage.length())));
        assertTrue(escalationMessage.contains("STOP: identical failure repeated"));
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
    void snippetCompilationErrorsIncludeJavacDiagnosticsInRepairPrompt() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testSnippetCompile"));
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
                                    + "- Snippet compilation failed for EvosuiteSnippet_1: error: cannot find symbol\n"
                                    + "  symbol: class MissingThing\n"
                                    + "  location: class GeneratedSnippet\n"
                                    + "Failing statement (index 0):\n"
                                    + "MissingThing value0 = new MissingThing();\n"));
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        ArgumentCaptor<List<LlmMessage>> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        List<LlmMessage> conversation = conversationCaptor.getValue();
        String repairPrompt = conversation.get(conversation.size() - 1).getContent();
        assertTrue(repairPrompt.contains("Snippet compilation note:"),
                "repair prompt should explicitly label snippet compilation failures");
        assertTrue(repairPrompt.contains("javac diagnostics:"),
                "repair prompt should extract compiler diagnostics from the wrapper message");
        assertTrue(repairPrompt.contains("cannot find symbol"),
                "repair prompt should surface the actual compiler error text");
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
    void identicalHeadlessErrorEscalatesOnceBeforeSkipping() {
        // The same AWTError on consecutive attempts would normally be skipped via
        // "identical error repeated". For headless errors we want to give the LLM
        // one more turn with an explicit Mockito.mock(SUT.class) instruction
        // before bailing out, since the repair feedback otherwise leaks NPE
        // diagnostics rooted in the parser's typed-null fallback.
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testHeadless"));
            }
        };

        String savedTarget = org.evosuite.Properties.TARGET_CLASS;
        try {
            org.evosuite.Properties.TARGET_CLASS = "de.example.SomeFrame";

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
                    3);

            RepairResult result = loop.attemptParse(
                    "```java\n@org.junit.Test\npublic void test(){}\n```",
                    Collections.singletonList(LlmMessage.user("seed")),
                    LlmFeature.TEST_REPAIR);

            assertFalse(result.isSuccess());

            // We expect at least two repair calls: the first because the AWT error
            // was new, the second because the headless escalation suppressed the
            // identical-error skip exactly once.
            ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
            verify(llmService, atLeast(2)).query(conversationCaptor.capture(),
                    eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());

            List<List> allCalls = conversationCaptor.getAllValues();
            // The escalation hint must appear in the most recent repair message
            // (i.e. the one sent after the identical-error retry was bypassed).
            @SuppressWarnings("unchecked")
            List<LlmMessage> escalatedConversation = allCalls.get(allCalls.size() - 1);
            String escalatedRepairMessage = escalatedConversation.get(
                    escalatedConversation.size() - 1).getContent();
            assertTrue(escalatedRepairMessage.contains("HEADLESS ESCALATION"),
                    "Expected escalation header in repair message: " + escalatedRepairMessage);
            assertTrue(escalatedRepairMessage.contains("Mockito.mock(SomeFrame.class)"),
                    "Expected explicit Mockito.mock(<simple SUT name>.class) instruction");

            // Diagnostics must record the bypass so the experiment harness can see it.
            assertTrue(result.getDiagnostics().stream()
                            .anyMatch(d -> d.contains("headless escalation hint injected")),
                    "Expected diagnostics to record the escalation: " + result.getDiagnostics());
        } finally {
            org.evosuite.Properties.TARGET_CLASS = savedTarget;
        }
    }

    @Test
    void headlessEscalationFiresOnlyOncePerConversation() {
        // After the escalation has been used once, a subsequent identical AWT
        // failure should fall back to the normal skip path.
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testHeadless"));
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
                5);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        assertEquals(1L, result.getDiagnostics().stream()
                .filter(d -> d.contains("headless escalation hint injected"))
                .count(),
                "Headless escalation hint must be injected at most once per conversation: "
                        + result.getDiagnostics());
        assertTrue(result.getDiagnostics().stream()
                        .anyMatch(d -> d.contains("identical error repeated")),
                "After escalation, a subsequent identical headless error must hit the normal skip path");
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
    void falsePositiveMockClassifierDetectsViolatedAssumptionAnswerFailures() {
        assertTrue(TestRepairLoop.isFalsePositiveMockError(
                "Execution error in test 'x': org.evosuite.runtime.FalsePositiveException - "
                        + "Mock call to prepareStatement which was not presented when the test was generated"));
        assertTrue(TestRepairLoop.isFalsePositiveMockError(
                "Execution error in test 'x': java.lang.RuntimeException - "
                        + "Root cause: org.evosuite.runtime.FalsePositiveException - "
                        + "Mock call to getTransaction which was not presented when the test was generated"));
        assertFalse(TestRepairLoop.isFalsePositiveMockError(
                "Execution error: java.lang.NullPointerException - x is null"));
    }

    @Test
    void falsePositiveMockFailureAddsMissingStubRepairInstructions() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testMissingStub"));
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
                            new FalsePositiveException(
                                    "Mock call to prepareStatement which was not presented when the test was generated"));
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());

        ArgumentCaptor<List<LlmMessage>> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        List<LlmMessage> conversation = conversationCaptor.getValue();
        String repairPrompt = conversation.get(conversation.size() - 1).getContent();
        assertTrue(repairPrompt.contains("Mock-stubbing hint:"));
        assertTrue(repairPrompt.contains("Unstubbed mock call: `prepareStatement(...)`"));
        assertTrue(repairPrompt.contains("minimal stub"));
        assertTrue(repairPrompt.contains("ViolatedAssumptionAnswer"));
    }

    @Test
    void sqlVariableManagerFalsePositiveMockFailureAddsMissingStubRepairInstructions() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "getVariableIdentifier"));
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
                            new FalsePositiveException(
                                    "Mock call to prepareStatement which was not presented when the test was generated"));
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());

        ArgumentCaptor<List<LlmMessage>> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        List<LlmMessage> conversation = conversationCaptor.getValue();
        String repairPrompt = conversation.get(conversation.size() - 1).getContent();
        assertTrue(repairPrompt.contains("Mock-stubbing hint:"));
        assertTrue(repairPrompt.contains("Unstubbed mock call: `prepareStatement(...)`"));
        assertTrue(repairPrompt.contains("Keep the rest of the test unchanged"));
    }

    @Test
    void falsePositiveMockFailureUsesFullSignatureWhenAvailable() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testSignatureHint"));
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
                            new FalsePositiveException(
                                    "Mock call to prepareStatement which was not presented when the test was generated\n"
                                            + "Failure stack excerpt:\n"
                                            + "[Dependency Stack] java.sql.Connection.prepareStatement(Connection.java:123)"));
                    return result;
                },
                1);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());

        ArgumentCaptor<List<LlmMessage>> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());
        List<LlmMessage> conversation = conversationCaptor.getValue();
        String repairPrompt = conversation.get(conversation.size() - 1).getContent();
        assertTrue(repairPrompt.contains("Mock-stubbing hint:"));
        assertTrue(repairPrompt.contains("java.sql.Connection.prepareStatement(...)"));
        assertFalse(repairPrompt.contains("Unstubbed mock call: `prepareStatement(...)`"));
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
    void indexedFixtureExecutionErrorAddsGenericBoundsRepairInstructions() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                DefaultTestCase tc = new DefaultTestCase();
                tc.addStatement(new IntPrimitiveStatement(tc, 1));
                return Collections.singletonList(new ParseResult(tc, "testIndexedFixture"));
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
                            new ArrayIndexOutOfBoundsException("Index 2 out of bounds for length 2"));
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
        assertTrue(userRepairMessage.contains("Indexed fixture/bounds hint"),
                "repair prompt should add a generic bounds/shape hint");
        assertTrue(userRepairMessage.contains("re-check each constructor size/count argument against every later indexed read/write"),
                "repair prompt should guide the model to align size/count arguments with later indexed access");
        assertTrue(userRepairMessage.contains("Do not assume constructor counts map 1:1"),
                "repair prompt should warn that helper/container constructors may derive internal storage differently");
        assertTrue(userRepairMessage.contains("assertThrows(...)"),
                "repair prompt should suggest assertThrows when invalid sizes or indices are intentional");
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
    void droppedAtParseRepairMessageIncludesActionRequiredWarnings() throws Exception {
        TestRepairLoop loop = new TestRepairLoop(
                mock(LlmService.class),
                new TestParser(getClass().getClassLoader()),
                new LlmResponseParser(),
                mock(ClusterExpansionManager.class),
                testCase -> new ExecutionResult(testCase),
                1);

        ParseResult warningOnly = new ParseResult(new DefaultTestCase(), "testDroppedAtParse");
        warningOnly.addDiagnostic(new ParseDiagnostic(
                ParseDiagnostic.Severity.WARNING,
                "Cannot resolve type: InventedType LLM_REPAIR_ACTION_REQUIRED: replace invented type with a real one",
                4,
                "InventedType value = new InventedType();"));

        assertFalse(warningOnly.hasErrors(), "WARNING-only parse result should not change hasErrors()");

        Method buildCombinedRepairMessage = TestRepairLoop.class.getDeclaredMethod(
                "buildCombinedRepairMessage", List.class, List.class, List.class, Map.class);
        buildCombinedRepairMessage.setAccessible(true);
        String repairMessage = (String) buildCombinedRepairMessage.invoke(
                loop,
                Collections.emptyList(),
                Collections.singletonList(warningOnly),
                Collections.emptyList(),
                Collections.emptyMap());

        assertTrue(repairMessage.contains("testDroppedAtParse"),
                "Repair message should mention the caller-classified dropped test");
        assertTrue(repairMessage.contains("LLM_REPAIR_ACTION_REQUIRED: replace invented type with a real one"),
                "Repair message should include actionable warning text");
        assertFalse(repairMessage.contains("(no ERROR diagnostic recorded)"),
                "Action-required warnings should prevent the silent-drop fallback text");
    }

    @Test
    void droppedAtParseRepairMessageIncludesStrandedDeclarationWarning() throws Exception {
        TestRepairLoop loop = new TestRepairLoop(
                mock(LlmService.class),
                new TestParser(getClass().getClassLoader()),
                new LlmResponseParser(),
                mock(ClusterExpansionManager.class),
                testCase -> new ExecutionResult(testCase),
                1);

        TestParser parser = new TestParser(getClass().getClassLoader());
        parser.setMarkParsedFromLlm(true);
        ParseResult warningOnly = parser.parseTestMethodBody(
                "StringBuilder builder;",
                Collections.singletonList("import java.lang.StringBuilder;"));

        assertFalse(warningOnly.hasErrors(), "Stranded declaration should remain warning-only");
        assertTrue(warningOnly.getDiagnostics().stream().anyMatch(d ->
                        d.getSeverity() == ParseDiagnostic.Severity.WARNING
                                && d.getMessage() != null
                                && d.getMessage().contains("Variable declared but never assigned")
                                && d.getMessage().contains("LLM_REPAIR_ACTION_REQUIRED:")),
                "Expected actionable stranded-declaration warning: " + warningOnly.getDiagnostics());

        Method buildCombinedRepairMessage = TestRepairLoop.class.getDeclaredMethod(
                "buildCombinedRepairMessage", List.class, List.class, List.class, Map.class);
        buildCombinedRepairMessage.setAccessible(true);
        String repairMessage = (String) buildCombinedRepairMessage.invoke(
                loop,
                Collections.emptyList(),
                Collections.singletonList(warningOnly),
                Collections.emptyList(),
                Collections.emptyMap());

        assertTrue(repairMessage.contains("Variable declared but never assigned"),
                "Repair message should include stranded declaration warning text");
        assertTrue(repairMessage.contains("LLM_REPAIR_ACTION_REQUIRED: remove the declaration or assign a value before use"),
                "Repair message should include actionable stranded declaration guidance");
        assertFalse(repairMessage.contains("(no ERROR diagnostic recorded)"),
                "Action-required stranded declaration warnings should prevent silent-drop fallback text");
    }

    @Test
    void droppedAtParseRepairMessageIncludesAnonymousAbstractTypedNullFallback() throws Exception {
        // Reproduces the BasePlugin failure mode: the LLM correctly emitted
        // `new SUT() { ... }` (an abstract SUT with one unimplemented abstract
        // method) but the parser had to substitute a typed null because the
        // anonymous body did not implement the abstract method. With T1a+T1b
        // the parser now demotes the test to ERROR severity so it lands in
        // droppedAtParse rather than running to NPE; T1c then verifies the
        // repair message echoes the LLM's actual source (not the synthetic
        // `Type var = null;` excerpt) and surfaces the categorized action
        // text so the LLM can correct the test.
        String previousTargetClass = Properties.TARGET_CLASS;
        try {
            Properties.TARGET_CLASS = "java.io.OutputStream";

            TestParser parser = new TestParser(getClass().getClassLoader());
            parser.setMarkParsedFromLlm(true);
            ParseResult demoted = parser.parseTestMethodBody(
                    "java.io.OutputStream out0 = new java.io.OutputStream() {\n"
                            + "};\n"
                            + "out0.flush();",
                    Collections.emptyList());

            assertTrue(demoted.hasErrors(),
                    "SUT-typed-null fallback should produce ERROR severity, got: " + demoted.getDiagnostics());
            boolean hasFallbackError = demoted.getDiagnostics().stream()
                    .anyMatch(d -> d.getSeverity() == ParseDiagnostic.Severity.ERROR
                            && "ANONYMOUS_ABSTRACT_TYPED_NULL_FALLBACK".equals(d.getKindName()));
            assertTrue(hasFallbackError,
                    "Expected an ERROR with kind ANONYMOUS_ABSTRACT_TYPED_NULL_FALLBACK; got: "
                            + demoted.getDiagnostics());

            TestRepairLoop loop = new TestRepairLoop(
                    mock(LlmService.class),
                    new TestParser(getClass().getClassLoader()),
                    new LlmResponseParser(),
                    mock(ClusterExpansionManager.class),
                    testCase -> new ExecutionResult(testCase),
                    1);

            Method buildCombinedRepairMessage = TestRepairLoop.class.getDeclaredMethod(
                    "buildCombinedRepairMessage", List.class, List.class, List.class, Map.class);
            buildCombinedRepairMessage.setAccessible(true);
            String repairMessage = (String) buildCombinedRepairMessage.invoke(
                    loop,
                    Collections.emptyList(),
                    Collections.singletonList(demoted),
                    Collections.emptyList(),
                    Collections.emptyMap());

            assertTrue(repairMessage.contains("dropped"),
                    "Repair message should explain that the test was dropped at parse time:\n" + repairMessage);
            assertTrue(repairMessage.contains("anonymous implementation for java.io.OutputStream"),
                    "Repair message should name the abstract type whose anonymous body could not be synthesized:\n" + repairMessage);
            assertTrue(repairMessage.contains("new java.io.OutputStream()"),
                    "Repair message should preserve the LLM's original anonymous-creation source as the diagnostic snippet:\n" + repairMessage);
            assertTrue(repairMessage.contains("LLM_REPAIR_ACTION_REQUIRED:"),
                    "Repair message should include the explicit LLM_REPAIR_ACTION_REQUIRED action text:\n" + repairMessage);
            assertTrue(repairMessage.contains("override every abstract method"),
                    "Repair action text should tell the LLM to override every abstract method:\n" + repairMessage);
            assertFalse(repairMessage.contains("OutputStream out0 = null;"),
                    "Repair message must NOT echo the synthetic typed-null excerpt as if the LLM wrote it:\n" + repairMessage);
        } finally {
            Properties.TARGET_CLASS = previousTargetClass;
        }
    }

    @Test
    void noMatchingMethodRepairMessagePreservesRawSourceAndClosestApiHint() throws Exception {
        TestRepairLoop loop = new TestRepairLoop(
                mock(LlmService.class),
                new TestParser(getClass().getClassLoader()),
                new LlmResponseParser(),
                mock(ClusterExpansionManager.class),
                testCase -> new ExecutionResult(testCase),
                1,
                null,
                "ChangeNodeLevelAction [class]\n"
                        + "  setController(MindMapController)\n"
                        + "  getMindMapController()\n");

        ParseResult missingSetter = new ParseResult(new DefaultTestCase(), "invoke_whenNoSelecteds");
        missingSetter.addDiagnostic(new ParseDiagnostic(
                ParseDiagnostic.Severity.ERROR,
                "No method named setMindMapController in ChangeNodeLevelAction",
                12,
                "action.setMindMapController(mmc);"));

        Method buildCombinedRepairMessage = TestRepairLoop.class.getDeclaredMethod(
                "buildCombinedRepairMessage", List.class, List.class, List.class, Map.class);
        buildCombinedRepairMessage.setAccessible(true);
        String repairMessage = (String) buildCombinedRepairMessage.invoke(
                loop,
                Collections.emptyList(),
                Collections.singletonList(missingSetter),
                Collections.emptyList(),
                Collections.emptyMap());

        assertTrue(repairMessage.contains("No method named setMindMapController in ChangeNodeLevelAction"),
                "Repair message should preserve the parser diagnostic:\n" + repairMessage);
        assertTrue(repairMessage.contains("Source expression: action.setMindMapController(mmc);"),
                "Repair message should preserve the raw offending source snippet:\n" + repairMessage);
        assertTrue(repairMessage.contains("Closest available API: setController(MindMapController)"),
                "Repair message should suggest the closest available API from SUT context:\n" + repairMessage);
    }

    @Test
    void unrelatedParseDiagnosticDoesNotAddClosestApiSuggestion() throws Exception {
        TestRepairLoop loop = new TestRepairLoop(
                mock(LlmService.class),
                new TestParser(getClass().getClassLoader()),
                new LlmResponseParser(),
                mock(ClusterExpansionManager.class),
                testCase -> new ExecutionResult(testCase),
                1,
                null,
                "ChangeNodeLevelAction [class]\n"
                        + "  setController(MindMapController)\n"
                        + "  getMindMapController()\n");

        ParseResult unresolvedType = new ParseResult(new DefaultTestCase(), "invoke_whenNoSelecteds");
        unresolvedType.addDiagnostic(new ParseDiagnostic(
                ParseDiagnostic.Severity.ERROR,
                "Cannot resolve type: InventedType",
                12,
                "InventedType value = new InventedType();"));

        Method buildCombinedRepairMessage = TestRepairLoop.class.getDeclaredMethod(
                "buildCombinedRepairMessage", List.class, List.class, List.class, Map.class);
        buildCombinedRepairMessage.setAccessible(true);
        String repairMessage = (String) buildCombinedRepairMessage.invoke(
                loop,
                Collections.emptyList(),
                Collections.singletonList(unresolvedType),
                Collections.emptyList(),
                Collections.emptyMap());

        assertFalse(repairMessage.contains("Closest available API:"),
                "Only missing-method diagnostics should emit closest-API suggestions:\n" + repairMessage);
    }

    @Test
    void sharedExecutionAttritionAddsBatchLevelRepairGuidance() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        AtomicInteger parseCalls = new AtomicInteger();
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                if (parseCalls.getAndIncrement() == 0) {
                    DefaultTestCase executable = new DefaultTestCase();
                    executable.addStatement(new IntPrimitiveStatement(executable, 1));
                    ParseResult keptResult = new ParseResult(executable, "testKept");

                    DefaultTestCase droppedOne = new DefaultTestCase();
                    droppedOne.addStatement(new IntPrimitiveStatement(droppedOne, 2));
                    ParseResult failedA = new ParseResult(droppedOne, "testRootRequestA");

                    DefaultTestCase droppedTwo = new DefaultTestCase();
                    droppedTwo.addStatement(new IntPrimitiveStatement(droppedTwo, 3));
                    ParseResult failedB = new ParseResult(droppedTwo, "testRootRequestB");

                    return Arrays.asList(keptResult, failedA, failedB);
                }

                DefaultTestCase repaired = new DefaultTestCase();
                repaired.addStatement(new IntPrimitiveStatement(repaired, 4));
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
                        if (stmt.getValue() == 2 || stmt.getValue() == 3) {
                            result.reportNewThrownException(0, new NullPointerException(
                                    "Cannot invoke \"net.sourceforge.ext4j.taglib.bo.CurrentURLBO$RootRequest.isWebRequest()\" "
                                            + "because \"this.mRoot\" is null"));
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

        ArgumentCaptor<List<LlmMessage>> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());

        List<LlmMessage> conversation = conversationCaptor.getValue();
        String repairPrompt = conversation.get(conversation.size() - 1).getContent();
        assertTrue(repairPrompt.contains("Batch attrition summary:"),
                "repair prompt should surface the batch-level attrition summary");
        assertTrue(repairPrompt.contains("Started from 3 parsed candidate test(s); only 1 currently parse and execute cleanly."),
                "repair prompt should quantify how many tests were lost");
        assertTrue(repairPrompt.contains("Do not solve this by returning only the surviving test(s)"),
                "repair prompt should forbid silent shrinkage to only surviving tests");
        assertTrue(repairPrompt.contains("Multiple dropped tests share the same execution root cause"),
                "repair prompt should call out the shared execution failure");
        assertTrue(repairPrompt.contains("this.mRoot"),
                "repair prompt should preserve the repeated null receiver name");
        assertTrue(repairPrompt.contains("CurrentURLBO$RootRequest.isWebRequest()"),
                "repair prompt should preserve the repeated SUT dereference site");
    }

    @Test
    void sharedIndexedFixtureAttritionAddsBatchLevelRepairGuidance() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        AtomicInteger parseCalls = new AtomicInteger();
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                if (parseCalls.getAndIncrement() == 0) {
                    DefaultTestCase executable = new DefaultTestCase();
                    executable.addStatement(new IntPrimitiveStatement(executable, 1));
                    ParseResult keptResult = new ParseResult(executable, "testKept");

                    DefaultTestCase droppedOne = new DefaultTestCase();
                    droppedOne.addStatement(new IntPrimitiveStatement(droppedOne, 2));
                    ParseResult failedA = new ParseResult(droppedOne, "testIndexedFixtureA");

                    DefaultTestCase droppedTwo = new DefaultTestCase();
                    droppedTwo.addStatement(new IntPrimitiveStatement(droppedTwo, 3));
                    ParseResult failedB = new ParseResult(droppedTwo, "testIndexedFixtureB");

                    return Arrays.asList(keptResult, failedA, failedB);
                }

                DefaultTestCase repaired = new DefaultTestCase();
                repaired.addStatement(new IntPrimitiveStatement(repaired, 4));
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
                        if (stmt.getValue() == 2 || stmt.getValue() == 3) {
                            result.reportNewThrownException(0,
                                    new ArrayIndexOutOfBoundsException("Index 2 out of bounds for length 2"));
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

        ArgumentCaptor<List<LlmMessage>> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());

        List<LlmMessage> conversation = conversationCaptor.getValue();
        String repairPrompt = conversation.get(conversation.size() - 1).getContent();
        assertTrue(repairPrompt.contains("Batch attrition summary:"),
                "repair prompt should surface the batch-level attrition summary");
        assertTrue(repairPrompt.contains("Multiple dropped tests share the same bounds/shape failure while constructing or populating indexed fixtures"),
                "repair prompt should call out the shared indexed-fixture failure");
        assertTrue(repairPrompt.contains("constructor size/count arguments consistent with every later indexed get/set call"),
                "repair prompt should steer the model toward repairing the shared size/index pattern");
    }

    @Test
    void sharedAnonymousSnippetAttritionAddsBatchLevelRepairGuidance() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        AtomicInteger parseCalls = new AtomicInteger();
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                if (parseCalls.getAndIncrement() == 0) {
                    DefaultTestCase executable = new DefaultTestCase();
                    executable.addStatement(new IntPrimitiveStatement(executable, 1));
                    ParseResult keptResult = new ParseResult(executable, "testKept");

                    DefaultTestCase droppedOne = new DefaultTestCase();
                    droppedOne.addStatement(new IntPrimitiveStatement(droppedOne, 2));
                    ParseResult failedA = new ParseResult(droppedOne, "testAnonymousAdminAppA");

                    DefaultTestCase droppedTwo = new DefaultTestCase();
                    droppedTwo.addStatement(new IntPrimitiveStatement(droppedTwo, 3));
                    ParseResult failedB = new ParseResult(droppedTwo, "testAnonymousAdminAppB");

                    return Arrays.asList(keptResult, failedA, failedB);
                }

                DefaultTestCase repaired = new DefaultTestCase();
                repaired.addStatement(new IntPrimitiveStatement(repaired, 4));
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
                        if (stmt.getValue() == 2 || stmt.getValue() == 3) {
                            result.reportNewThrownException(0, new RuntimeException(
                                    "Snippet compilation failed for EvosuiteSnippet_abcd.java: error: illegal start of type\n"
                                            + "        return null;\n"
                                            + "        ^\n"
                                            + "Failing statement (index 0, zero-based):\n"
                                            + "osa.ora.server.admin.AdminApp adminApp = new osa.ora.server.admin.AdminApp() {\n\n"
                                            + "    @Override\n"
                                            + "    public java.util.Vector<Group> getGroups() {\n"
                                            + "        return null;\n"
                                            + "    }\n"
                                            + "};\n"));
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

        ArgumentCaptor<List<LlmMessage>> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());

        List<LlmMessage> conversation = conversationCaptor.getValue();
        String repairPrompt = conversation.get(conversation.size() - 1).getContent();
        assertTrue(repairPrompt.contains("Batch attrition summary:"),
                "repair prompt should surface the batch-level attrition summary");
        assertTrue(repairPrompt.contains("Multiple dropped tests share the same anonymous-class snippet failure around 'AdminApp'"),
                "repair prompt should call out the repeated anonymous-class failure");
        assertTrue(repairPrompt.contains("This is not a parser/import issue"),
                "repair prompt should explain that the anonymous-class failure is not parsing-related");
        assertTrue(repairPrompt.contains("Do not keep writing `new AdminApp() { ... }`"),
                "repair prompt should explicitly forbid the repeated anonymous AdminApp pattern");
        assertTrue(repairPrompt.contains("Mockito.mock(osa.ora.server.admin.AdminApp.class, new ViolatedAssumptionAnswer())"),
                "repair prompt should steer the model toward a mock-based replacement");
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

    @Test
    void emptyResponseFollowedByContextOnlyDetectsBytecodeFailureAndImprovesDiagnostic() {
        LlmService llmService = mock(LlmService.class);
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        // Seed attempt returns bytecode context instead of test code
        // (simulating LLM hitting token limit or silently failing)
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                // Both seed and repair: return empty (no @Test methods found)
                return Collections.emptyList();
            }
        };

        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenAnswer(invocation -> {
                    // Simulate LLM returning actual test code on second attempt
                    String testResponse = "@org.junit.jupiter.api.Test\n"
                            + "public void test0() throws Throwable {\n"
                            + "  ClientGroup cg = new ClientGroup(null);\n"
                            + "}\n";
                    return Collections.singletonList(LlmMessage.assistant(testResponse));
                });

        // Seed LLM response: bytecode context (looks like Java code due to "public class" and "public void")
        // but is actually disassembly that the parser cannot convert to test methods
        String seedResponse = "BYTECODE_DISASSEMBLED context:\n```\n"
                + "// class version 52.0 (52)\n"
                + "// access flags 0x21\n"
                + "public class ioproject.server.network.ClientGroup implements java.lang.Iterable {\n"
                + "  // access flags 0x2\n"
                + "  private final java.util.Set clients;\n"
                + "  // access flags 0x0\n"
                + "  public void notifyMessageSent(Lioproject/server/network/Client;Ljava/lang/Object;)Z\n"
                + "    ALOAD 0\n"
                + "    GETFIELD\n"
                + "    RETURN\n"
                + "}\n```\n";

        TestRepairLoop loop = new TestRepairLoop(
                llmService,
                parser,
                new LlmResponseParser(),
                expansionManager,
                testCase -> new ExecutionResult(testCase),
                1);

        RepairResult result = loop.attemptParse(
                seedResponse,
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        // Should fail (cannot generate tests from bytecode-only responses)
        assertFalse(result.isSuccess());

        ArgumentCaptor<List<LlmMessage>> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, times(1)).query(conversationCaptor.capture(), eq(LlmFeature.TEST_REPAIR),
                anyInt(), anyBoolean(), anyList());

        List<LlmMessage> conversation = conversationCaptor.getValue();
        String repairPrompt = conversation.get(conversation.size() - 1).getContent();
        
        assertTrue(repairPrompt.contains("Parser produced no test methods"),
                "repair prompt should mention parse failure");
        // The diagnostic should detect that the response was bytecode disassembly or context
        assertTrue(repairPrompt.contains("bytecode") || repairPrompt.contains("BYTECODE") 
                   || repairPrompt.contains("context"),
                "repair prompt should detect bytecode/context-only response. Got: " + repairPrompt);
        assertTrue(repairPrompt.contains("Do not return") || repairPrompt.contains("Generate actual"),
                "repair prompt should guide toward actual test generation. Got: " + repairPrompt);
    }

    @Test
    void clinitFailureInDependencySurfacesPoisonedClassAvoidance() {
        String previousTarget = Properties.TARGET_CLASS;
        boolean previousFlag = Properties.LLM_INCLUDE_DEPENDENCY_CODE_ON_REPAIR;
        Properties.TARGET_CLASS = "com.example.cut.MyClass";
        Properties.LLM_INCLUDE_DEPENDENCY_CODE_ON_REPAIR = true;
        try {
            LlmService llmService = mock(LlmService.class);
            when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                    .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
            ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

            // Parser returns one test on first call (which will fail at execution), then a clean one.
            final int[] parseCount = {0};
            TestParser parser = new TestParser(getClass().getClassLoader()) {
                @Override
                public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                    parseCount[0]++;
                    DefaultTestCase tc = new DefaultTestCase();
                    tc.addStatement(new IntPrimitiveStatement(tc, 1));
                    return Collections.singletonList(new ParseResult(tc, "testFails"));
                }
            };

            // First execution surfaces an ExceptionInInitializerError pointing at a non-CUT
            // class's <clinit>. Subsequent executions succeed.
            final int[] execCount = {0};
            TestRepairLoop.TestExecutor executor = testCase -> {
                execCount[0]++;
                ExecutionResult result = new ExecutionResult(testCase);
                if (execCount[0] == 1) {
                    ExceptionInInitializerError error =
                            new ExceptionInInitializerError(new RuntimeException("blew up"));
                    error.setStackTrace(new StackTraceElement[]{
                            new StackTraceElement("com.dep.Initialized", "<clinit>",
                                    "Initialized.java", 5),
                            new StackTraceElement("com.example.cut.MyClass", "<init>",
                                    "MyClass.java", 10)
                    });
                    result.reportNewThrownException(0, error);
                }
                return result;
            };

            TestRepairLoop loop = new TestRepairLoop(
                    llmService,
                    parser,
                    new LlmResponseParser(),
                    expansionManager,
                    executor,
                    1);

            loop.attemptParse(
                    "```java\n@org.junit.Test\npublic void test(){}\n```",
                    Collections.singletonList(LlmMessage.user("seed")),
                    LlmFeature.TEST_REPAIR);

            ArgumentCaptor<List<LlmMessage>> captor = ArgumentCaptor.forClass(List.class);
            verify(llmService, atLeastOnce()).query(captor.capture(), eq(LlmFeature.TEST_REPAIR),
                    anyInt(), anyBoolean(), anyList());
            List<LlmMessage> conversation = captor.getValue();
            String prompt = conversation.get(conversation.size() - 1).getContent();
            assertTrue(prompt.contains("Poisoned-class avoidance:"),
                    "expected poisoned-class avoidance section. Prompt:\n" + prompt);
            assertTrue(prompt.contains("com.dep.Initialized"),
                    "expected the failing class to be named in the avoidance section. Prompt:\n" + prompt);
            assertTrue(prompt.contains("permanently unusable"),
                    "expected the prompt to flag the class as unusable. Prompt:\n" + prompt);
        } finally {
            Properties.TARGET_CLASS = previousTarget;
            Properties.LLM_INCLUDE_DEPENDENCY_CODE_ON_REPAIR = previousFlag;
        }
    }

    @Test
    void dependencyCodeContextFlagDisabledSkipsAvoidanceSection() {
        String previousTarget = Properties.TARGET_CLASS;
        boolean previousFlag = Properties.LLM_INCLUDE_DEPENDENCY_CODE_ON_REPAIR;
        Properties.TARGET_CLASS = "com.example.cut.MyClass";
        Properties.LLM_INCLUDE_DEPENDENCY_CODE_ON_REPAIR = false;
        try {
            LlmService llmService = mock(LlmService.class);
            when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                    .thenReturn("```java\n@org.junit.Test\npublic void repaired(){}\n```");
            ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

            TestParser parser = new TestParser(getClass().getClassLoader()) {
                @Override
                public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                    DefaultTestCase tc = new DefaultTestCase();
                    tc.addStatement(new IntPrimitiveStatement(tc, 1));
                    return Collections.singletonList(new ParseResult(tc, "testFails"));
                }
            };

            final int[] execCount = {0};
            TestRepairLoop.TestExecutor executor = testCase -> {
                execCount[0]++;
                ExecutionResult result = new ExecutionResult(testCase);
                if (execCount[0] == 1) {
                    ExceptionInInitializerError error = new ExceptionInInitializerError(new RuntimeException());
                    error.setStackTrace(new StackTraceElement[]{
                            new StackTraceElement("com.dep.Initialized", "<clinit>",
                                    "Initialized.java", 5)
                    });
                    result.reportNewThrownException(0, error);
                }
                return result;
            };

            TestRepairLoop loop = new TestRepairLoop(
                    llmService,
                    parser,
                    new LlmResponseParser(),
                    expansionManager,
                    executor,
                    1);

            loop.attemptParse(
                    "```java\n@org.junit.Test\npublic void test(){}\n```",
                    Collections.singletonList(LlmMessage.user("seed")),
                    LlmFeature.TEST_REPAIR);

            ArgumentCaptor<List<LlmMessage>> captor = ArgumentCaptor.forClass(List.class);
            verify(llmService, atLeastOnce()).query(captor.capture(), eq(LlmFeature.TEST_REPAIR),
                    anyInt(), anyBoolean(), anyList());
            List<LlmMessage> conversation = captor.getValue();
            String prompt = conversation.get(conversation.size() - 1).getContent();
            assertFalse(prompt.contains("Poisoned-class avoidance:"),
                    "avoidance section must be suppressed when flag is off. Prompt:\n" + prompt);
        } finally {
            Properties.TARGET_CLASS = previousTarget;
            Properties.LLM_INCLUDE_DEPENDENCY_CODE_ON_REPAIR = previousFlag;
        }
    }

    /**
     * Regression test modeled after the ChangeNodeLevelAction failure pattern.
     * When the LLM retries the same inaccessible members (getMap, select,
     * obtainFocusForSelected) across two consecutive attempts, the repair loop
     * should detect recurrent access violations and terminate early instead
     * of exhausting the retry budget.
     */
    @Test
    void recurrentAccessViolationsTerminateRepairLoop() {
        LlmService llmService = mock(LlmService.class);
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        // Each attempt produces slightly different error text (different line
        // numbers and variable names) but the same inaccessible members.
        // This prevents the "identical error repeated" check from firing first
        // and exercises the access-violation recurrence logic.
        AtomicInteger parseCalls = new AtomicInteger();
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                int call = parseCalls.getAndIncrement();
                int lineBase = 10 + call * 5;
                String varName = "action" + call;
                String accessDiagnostic =
                        "File.java:" + lineBase + ": error: getMap() has protected access in NodeHookAdapter\n"
                        + "        " + varName + ".getMap();\n"
                        + "File.java:" + (lineBase + 3) + ": error: select(String,List) has private access in ChangeNodeLevelAction\n"
                        + "        " + varName + ".select(\"key\", list0);\n"
                        + "File.java:" + (lineBase + 6) + ": error: obtainFocusForSelected() has protected access in NodeHookAdapter\n"
                        + "        " + varName + ".obtainFocusForSelected();";
                ParseResult error = new ParseResult(new DefaultTestCase(), "testChangeNodeLevel");
                error.addDiagnostic(new ParseDiagnostic(
                        ParseDiagnostic.Severity.ERROR, accessDiagnostic, 1, ""));
                return Collections.singletonList(error);
            }
        };

        // LLM returns something that will parse the same way each time
        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void testChangeNodeLevel(){}\n```");

        TestRepairLoop loop = new TestRepairLoop(
                llmService, parser, new LlmResponseParser(), expansionManager,
                testCase -> new ExecutionResult(testCase), 5); // budget of 5

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void testChangeNodeLevel(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        assertFalse(result.isSuccess());
        // Should have stopped early due to recurrent access violations, not exhausted all 5 attempts
        assertTrue(result.getAttemptsUsed() <= 3,
                "Expected early termination but used " + result.getAttemptsUsed() + " attempts");
        assertTrue(result.getDiagnostics().stream()
                .anyMatch(d -> d.contains("recurrent access violations")),
                "Expected diagnostic about recurrent access violations: " + result.getDiagnostics());
    }

    /**
     * Verifies that when a compile error contains access violation diagnostics,
     * the repair message sent to the LLM includes the specific inaccessible
     * member names and their declaring classes.
     */
    @Test
    void accessViolationRepairMessageIncludesSpecificMemberNames() {
        LlmService llmService = mock(LlmService.class);
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        String accessDiagnostic =
                "getMap() has protected access in NodeHookAdapter";

        AtomicInteger calls = new AtomicInteger();
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                if (calls.getAndIncrement() == 0) {
                    ParseResult error = new ParseResult(new DefaultTestCase(), "testAccess");
                    error.addDiagnostic(new ParseDiagnostic(
                            ParseDiagnostic.Severity.ERROR, accessDiagnostic, 1, ""));
                    return Collections.singletonList(error);
                }
                return Collections.singletonList(new ParseResult(new DefaultTestCase(), "testAccess"));
            }
        };

        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void testAccess(){}\n```");

        TestRepairLoop loop = new TestRepairLoop(
                llmService, parser, new LlmResponseParser(), expansionManager,
                testCase -> new ExecutionResult(testCase), 2);

        loop.attemptParse(
                "```java\n@org.junit.Test\npublic void testAccess(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        ArgumentCaptor<List> conversationCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmService, atLeastOnce()).query(conversationCaptor.capture(),
                eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList());
        @SuppressWarnings("unchecked")
        List<LlmMessage> conversation = conversationCaptor.getValue();
        String repairPrompt = conversation.get(conversation.size() - 1).getContent();

        assertTrue(repairPrompt.contains("getMap"),
                "Repair message should mention specific member 'getMap': " + repairPrompt);
        assertTrue(repairPrompt.contains("NodeHookAdapter"),
                "Repair message should mention declaring class 'NodeHookAdapter': " + repairPrompt);
        assertTrue(repairPrompt.contains("protected"),
                "Repair message should mention access level 'protected': " + repairPrompt);
    }

    /**
     * Verifies that non-access compile errors (e.g., cannot find symbol) do not
     * trigger access violation tracking or early termination.
     */
    @Test
    void nonAccessErrorsDoNotTriggerAccessViolationTracking() {
        LlmService llmService = mock(LlmService.class);
        ClusterExpansionManager expansionManager = mock(ClusterExpansionManager.class);

        // Both attempts produce the same non-access error (symbol not found)
        AtomicInteger calls = new AtomicInteger();
        TestParser parser = new TestParser(getClass().getClassLoader()) {
            @Override
            public java.util.List<ParseResult> parseTestClass(String sourceCode) {
                ParseResult error = new ParseResult(new DefaultTestCase(), "test");
                error.addDiagnostic(new ParseDiagnostic(
                        ParseDiagnostic.Severity.ERROR,
                        "cannot find symbol: variable missingVar", 1, ""));
                return Collections.singletonList(error);
            }
        };

        when(llmService.query(anyList(), eq(LlmFeature.TEST_REPAIR), anyInt(), anyBoolean(), anyList()))
                .thenReturn("```java\n@org.junit.Test\npublic void test(){}\n```");

        TestRepairLoop loop = new TestRepairLoop(
                llmService, parser, new LlmResponseParser(), expansionManager,
                testCase -> new ExecutionResult(testCase), 3);

        RepairResult result = loop.attemptParse(
                "```java\n@org.junit.Test\npublic void test(){}\n```",
                Collections.singletonList(LlmMessage.user("seed")),
                LlmFeature.TEST_REPAIR);

        // Should NOT contain "recurrent access violations" diagnostic
        assertFalse(result.getDiagnostics().stream()
                .anyMatch(d -> d.contains("recurrent access violations")),
                "Non-access errors should not trigger access violation tracking: " + result.getDiagnostics());
    }

    private interface CheckedStubTarget {
        void noThrows(String value);

        void declaresChecked(String value) throws java.io.IOException;
    }

}
