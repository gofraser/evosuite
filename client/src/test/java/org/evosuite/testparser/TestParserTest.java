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
package org.evosuite.testparser;

import org.evosuite.Properties;
import org.evosuite.runtime.mock.MockList;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.UninterpretedStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestParserTest {

    private TestParser parser;

    @BeforeEach
    void setUp() {
        parser = new TestParser(getClass().getClassLoader());
    }

    @Test
    void parseTestClassFindsAllTestMethods() {
        String source = "import java.util.ArrayList;\n"
                + "public class MyTest {\n"
                + "    @org.junit.Test\n"
                + "    public void test1() {\n"
                + "        ArrayList list = new ArrayList();\n"
                + "    }\n"
                + "    @org.junit.Test\n"
                + "    public void test2() {\n"
                + "        int x = 42;\n"
                + "    }\n"
                + "    public void helperNotATest() {}\n"
                + "}\n";

        List<ParseResult> results = parser.parseTestClass(source);
        assertEquals(2, results.size());
        assertEquals("test1", results.get(0).getOriginalMethodName());
        assertEquals("test2", results.get(1).getOriginalMethodName());
    }

    @Test
    void parseTestMethodByName() {
        String source = "import java.util.ArrayList;\n"
                + "public class MyTest {\n"
                + "    @org.junit.Test\n"
                + "    public void testFoo() {\n"
                + "        ArrayList list = new ArrayList();\n"
                + "    }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testFoo");
        assertNotNull(result);
        assertEquals("testFoo", result.getOriginalMethodName());
        assertFalse(result.hasErrors());
    }

    @Test
    void parseTestMethodNotFoundReturnsError() {
        String source = "public class MyTest {\n"
                + "    @org.junit.Test\n"
                + "    public void testFoo() {}\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "nonExistent");
        assertTrue(result.hasErrors());
        assertTrue(result.getDiagnostics().stream()
                .anyMatch(d -> d.getMessage().contains("nonExistent")));
    }

    @Test
    void parseTestMethodBody() {
        String body = "int x = 42;\n";
        List<String> imports = List.of("import java.util.ArrayList;");

        ParseResult result = parser.parseTestMethodBody(body, imports);
        assertNotNull(result);
        assertFalse(result.hasErrors());
    }

    @Test
    void parseTestMethodBodyWithPackage() {
        // LinkedList is in java.util — without an explicit import, only
        // the package wildcard (from the package declaration) can resolve it.
        String body = "LinkedList<String> list = new LinkedList<>();\n";
        List<String> imports = List.of();

        ParseResult result = parser.parseTestMethodBody(body, imports, "java.util");
        assertNotNull(result);
        assertFalse(result.hasErrors(),
                "Expected no errors but got: " + result.getDiagnostics());
        assertEquals(1, result.getTestCase().size());
    }

    @Test
    void parseJUnit5TestAnnotation() {
        String source = "public class MyTest {\n"
                + "    @org.junit.jupiter.api.Test\n"
                + "    public void testBar() {\n"
                + "        int x = 1;\n"
                + "    }\n"
                + "}\n";

        List<ParseResult> results = parser.parseTestClass(source);
        assertEquals(1, results.size());
        assertEquals("testBar", results.get(0).getOriginalMethodName());
    }

    @Test
    void parseShortTestAnnotation() {
        String source = "import org.junit.Test;\n"
                + "public class MyTest {\n"
                + "    @Test\n"
                + "    public void testBaz() {\n"
                + "        int x = 1;\n"
                + "    }\n"
                + "}\n";

        List<ParseResult> results = parser.parseTestClass(source);
        assertEquals(1, results.size());
    }

    @Test
    void parseJUnit4ExpectedExceptionAnnotation() {
        String source = "import org.junit.Test;\n"
                + "public class MyTest {\n"
                + "    @Test(expected = IllegalArgumentException.class)\n"
                + "    public void testThrows() {\n"
                + "        int x = 1;\n"
                + "    }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testThrows");
        assertNotNull(result);
        assertFalse(result.hasErrors());
        assertEquals("IllegalArgumentException", result.getExpectedExceptionClass());
    }

    @Test
    void parseJUnit4FullyQualifiedExpectedException() {
        String source = "public class MyTest {\n"
                + "    @org.junit.Test(expected = java.io.IOException.class)\n"
                + "    public void testIO() {\n"
                + "        int x = 1;\n"
                + "    }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testIO");
        assertEquals("java.io.IOException", result.getExpectedExceptionClass());
    }

    @Test
    void parseJUnit5NoExpectedException() {
        String source = "public class MyTest {\n"
                + "    @org.junit.jupiter.api.Test\n"
                + "    public void testNormal() {\n"
                + "        int x = 1;\n"
                + "    }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testNormal");
        assertNull(result.getExpectedExceptionClass());
    }

    @Test
    void parseJUnit4TestWithoutExpected() {
        String source = "import org.junit.Test;\n"
                + "public class MyTest {\n"
                + "    @Test\n"
                + "    public void testPlain() {\n"
                + "        int x = 1;\n"
                + "    }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testPlain");
        assertNull(result.getExpectedExceptionClass());
    }

    @Test
    void constructorErrorListsAvailableConstructors() {
        // java.io.File has no no-arg constructor — calling new File() should fail
        // and the error message should list the available constructors
        String source = "import java.io.File;\n"
                + "public class MyTest {\n"
                + "    @org.junit.Test\n"
                + "    public void testBadConstructor() {\n"
                + "        File f = new File();\n"
                + "    }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testBadConstructor");
        assertTrue(result.hasErrors());
        String errorText = result.getDiagnostics().stream()
                .filter(d -> d.getSeverity() == ParseDiagnostic.Severity.ERROR)
                .map(ParseDiagnostic::getMessage)
                .filter(m -> m.contains("No matching constructor"))
                .findFirst()
                .orElse("");
        assertTrue(errorText.contains("Available constructors"),
                "Error should list available constructors but was: " + errorText);
        assertTrue(errorText.contains("File("),
                "Error should show File constructor signatures but was: " + errorText);
    }

    @Test
    void llmPrecheckWarnsButParsesHelperMethods() {
        parser.setMarkParsedFromLlm(true);

        String source = "import org.junit.jupiter.api.Test;\n"
                + "public class MyTest {\n"
                + "  private Object createInstance() { return new Object(); }\n"
                + "  @Test\n"
                + "  public void testUsesHelper() {\n"
                + "    Object x = createInstance();\n"
                + "  }\n"
                + "}\n";

        List<ParseResult> results = parser.parseTestClass(source);
        assertEquals(1, results.size());
        ParseResult result = results.get(0);
        assertFalse(result.hasErrors(), "Helper-method pre-check should not hard-fail parsing");
        String warning = result.getDiagnostics().stream()
                .filter(d -> d.getSeverity() == ParseDiagnostic.Severity.WARNING)
                .map(ParseDiagnostic::getMessage)
                .findFirst()
                .orElse("");
        assertTrue(warning.contains("LLM pre-check"));
        assertTrue(warning.contains("createInstance"));
        assertTrue(result.getTestCase().size() >= 1, "Test should be parsed in best-effort mode");
    }

    @Test
    void llmBestEffortInlinesSimpleNoArgHelperReturnExpression() {
        parser.setMarkParsedFromLlm(true);

        String source = "import org.junit.jupiter.api.Test;\n"
                + "public class MyTest {\n"
                + "  private Object createInstance() { return new Object(); }\n"
                + "  @Test\n"
                + "  public void testUsesHelper() {\n"
                + "    Object x = createInstance();\n"
                + "  }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testUsesHelper");
        assertFalse(result.hasErrors(), "Simple no-arg helper should be inlineable");
        assertEquals(1, result.getTestCase().size());
        assertInstanceOf(ConstructorStatement.class, result.getTestCase().getStatement(0));
    }

    @Test
    void llmBestEffortInlinesSimpleOneArgHelperReturnExpression() {
        parser.setMarkParsedFromLlm(true);

        String source = "import org.junit.jupiter.api.Test;\n"
                + "public class MyTest {\n"
                + "  private Integer createInt(int n) { return new Integer(n); }\n"
                + "  @Test\n"
                + "  public void testUsesHelper() {\n"
                + "    Integer x = createInt(7);\n"
                + "  }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testUsesHelper");
        assertFalse(result.hasErrors(), "Simple one-arg helper should be inlineable");
        assertEquals(2, result.getTestCase().size());
        assertInstanceOf(ConstructorStatement.class, result.getTestCase().getStatement(1));
    }

    @Test
    void llmBestEffortInlinedHelperDiagnosticUsesOriginalCallSiteLine() {
        parser.setMarkParsedFromLlm(true);

        String source = "import org.junit.jupiter.api.Test;\n"
                + "public class MyTest {\n"
                + "  private Object createInstance() { return missingValue; }\n"
                + "  @Test\n"
                + "  public void testUsesHelper() {\n"
                + "    Object x = createInstance();\n"
                + "  }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testUsesHelper");
        assertTrue(result.hasErrors(), "Expected unresolved-variable diagnostic from inlined helper");

        ParseDiagnostic diagnostic = result.getDiagnostics().stream()
                .filter(d -> d.getMessage() != null && d.getMessage().contains("Unresolved variable: missingValue"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing unresolved-variable diagnostic: "
                        + result.getDiagnostics()));

        assertEquals(6, diagnostic.getLineNumber(),
                "Inlined helper diagnostics should point at the original call site");
    }

    @Test
    void llmBestEffortInlinesVoidOneArgHelperInsideUnsupportedForLoop() {
        parser.setMarkParsedFromLlm(true);

        String source = "import org.junit.jupiter.api.Test;\n"
                + "import java.util.concurrent.atomic.AtomicInteger;\n"
                + "public class MyTest {\n"
                + "  private void forceIncrement(AtomicInteger value) { value.incrementAndGet(); }\n"
                + "  @Test\n"
                + "  public void testUsesVoidHelperInLoop() {\n"
                + "    AtomicInteger value = new AtomicInteger(0);\n"
                + "    for (int i = 0; i < 2; i++) {\n"
                + "      forceIncrement(value);\n"
                + "    }\n"
                + "  }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testUsesVoidHelperInLoop");
        assertFalse(result.hasErrors(), "Void helper in unsupported for-loop should be inlined");
        assertTrue(result.getTestCase().size() >= 2);

        UninterpretedStatement loop = null;
        for (int i = 0; i < result.getTestCase().size(); i++) {
            if (result.getTestCase().getStatement(i) instanceof UninterpretedStatement) {
                loop = (UninterpretedStatement) result.getTestCase().getStatement(i);
                break;
            }
        }
        assertNotNull(loop, "Expected for-loop to be preserved as uninterpreted");
        assertFalse(loop.getSourceCode().contains("forceIncrement("),
                "Helper call should be inlined inside preserved loop:\n" + loop.getSourceCode());
        assertTrue(loop.getSourceCode().contains("value.incrementAndGet()"),
                "Expected inlined helper body in preserved loop:\n" + loop.getSourceCode());
    }

    @Test
    void llmBestEffortInlinesMultiStatementNoArgFactoryHelper() {
        parser.setMarkParsedFromLlm(true);

        String source = "import org.junit.jupiter.api.Test;\n"
                + "import java.util.ArrayList;\n"
                + "public class MyTest {\n"
                + "  private ArrayList createList() {\n"
                + "    Integer seed = Integer.valueOf(7);\n"
                + "    ArrayList list = new ArrayList();\n"
                + "    return list;\n"
                + "  }\n"
                + "  @Test\n"
                + "  public void testUsesFactory() {\n"
                + "    ArrayList list = createList();\n"
                + "  }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testUsesFactory");
        assertFalse(result.hasErrors(),
                "Multi-statement no-arg factory helper should be inlineable: "
                        + result.getDiagnostics());
        // Two preamble statements (Integer seed, ArrayList list) + the assignment of the
        // helper's return value to the test variable.
        assertTrue(result.getTestCase().size() >= 3,
                "Expected preamble statements + factory call to be materialized; got "
                        + result.getTestCase().size());
    }

    @Test
    void llmBestEffortInlinesMultiStatementOneArgHelperWithParamSubstitution() {
        parser.setMarkParsedFromLlm(true);

        String source = "import org.junit.jupiter.api.Test;\n"
                + "import java.util.ArrayList;\n"
                + "public class MyTest {\n"
                + "  private ArrayList createListOfSize(int n) {\n"
                + "    ArrayList list = new ArrayList(n);\n"
                + "    return list;\n"
                + "  }\n"
                + "  @Test\n"
                + "  public void testUsesFactoryWithArg() {\n"
                + "    ArrayList list = createListOfSize(3);\n"
                + "  }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testUsesFactoryWithArg");
        assertFalse(result.hasErrors(),
                "One-arg multi-statement factory helper should inline with param substitution: "
                        + result.getDiagnostics());
    }

    @Test
    void llmBestEffortInlinedHelperLocalsDoNotCollideAcrossInvocations() {
        parser.setMarkParsedFromLlm(true);

        // Two test methods both call the same helper. The helper declares a local
        // named "list"; inlining must rename it on each call site to avoid collisions.
        String source = "import org.junit.jupiter.api.Test;\n"
                + "import java.util.ArrayList;\n"
                + "public class MyTest {\n"
                + "  private ArrayList createList() {\n"
                + "    ArrayList list = new ArrayList();\n"
                + "    return list;\n"
                + "  }\n"
                + "  @Test\n"
                + "  public void testFirst() {\n"
                + "    ArrayList list = createList();\n"
                + "    ArrayList second = createList();\n"
                + "  }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testFirst");
        assertFalse(result.hasErrors(),
                "Repeated invocations of multi-statement helper must not collide on local names: "
                        + result.getDiagnostics());
    }

    @Test
    void llmBestEffortDiagnosesSutHelperElisionDistinctlyFromGenericUnscopedHelper() {
        parser.setMarkParsedFromLlm(true);

        String savedTarget = Properties.TARGET_CLASS;
        try {
            Properties.TARGET_CLASS = "java.util.ArrayList";

            // Helper is referenced but NOT declared on the test class. The parser cannot
            // inline it, so the SUT-typed assignment becomes a typed-null fallback.
            String source = "import org.junit.jupiter.api.Test;\n"
                    + "import java.util.ArrayList;\n"
                    + "public class MyTest {\n"
                    + "  @Test\n"
                    + "  public void testElidedSutCtor() {\n"
                    + "    ArrayList list = createSut();\n"
                    + "  }\n"
                    + "}\n";

            ParseResult result = parser.parseTestMethod(source, "testElidedSutCtor");
            assertTrue(result.getDiagnostics().stream()
                            .anyMatch(d -> d.getMessage() != null
                                    && d.getMessage().contains("SUT construction elided")
                                    && d.getMessage().contains("Mockito.mock")),
                    "Expected SUT_HELPER_CALL_ELIDED diagnostic with explicit Mockito.mock guidance; got: "
                            + result.getDiagnostics());
        } finally {
            Properties.TARGET_CLASS = savedTarget;
        }
    }

    @Test
    void llmBestEffortRejectsHelperWithControlFlow() {
        parser.setMarkParsedFromLlm(true);

        // Control flow (if/for/try) in the helper body must NOT be inlined,
        // because inlining is a flat substitution that can't preserve flow.
        String source = "import org.junit.jupiter.api.Test;\n"
                + "import java.util.ArrayList;\n"
                + "public class MyTest {\n"
                + "  private ArrayList createList() {\n"
                + "    if (System.currentTimeMillis() > 0) {\n"
                + "      return new ArrayList();\n"
                + "    }\n"
                + "    return null;\n"
                + "  }\n"
                + "  @Test\n"
                + "  public void testUsesControlFlowHelper() {\n"
                + "    ArrayList list = createList();\n"
                + "  }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testUsesControlFlowHelper");
        // Helper is rejected from the inlineable map; the call site falls back through
        // the standard NO_UNSCOPED_METHOD path. We just want the parser to remain
        // robust (no crash) and produce a fallback. The exact diagnostic text is
        // covered elsewhere; here we just assert non-crash and a fallback statement.
        assertEquals(1, result.getTestCase().size(),
                "Helper with control flow should not be inlined; expect a single fallback statement");
    }

    @Test
    void llmBestEffortFallsBackForPrivateConstructorAccess() {
        parser.setMarkParsedFromLlm(true);

        String source = "import org.junit.jupiter.api.Test;\n"
                + "public class MyTest {\n"
                + "  @Test\n"
                + "  public void testPrivateCtor() {\n"
                + "    Runtime r = new Runtime();\n"
                + "  }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testPrivateCtor");
        assertFalse(result.hasErrors(), "Private constructor access should be salvaged in LLM mode");
        assertEquals(1, result.getTestCase().size());
        assertInstanceOf(NullStatement.class, result.getTestCase().getStatement(0));
    }

    @Test
    void llmBestEffortFallsBackForPrivateFieldAccess() {
        parser.setMarkParsedFromLlm(true);

        String source = "import org.junit.jupiter.api.Test;\n"
                + "public class MyTest {\n"
                + "  @Test\n"
                + "  public void testPrivateField() {\n"
                + "    int h = \"x\".hash;\n"
                + "  }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testPrivateField");
        assertFalse(result.hasErrors(), "Private field access should be salvaged in LLM mode");
        assertTrue(result.getTestCase().size() >= 1);
        assertInstanceOf(IntPrimitiveStatement.class, result.getTestCase().getStatement(result.getTestCase().size() - 1));
    }

    @Test
    void llmBestEffortFallsBackForUnresolvedScopedMethodCall() {
        parser.setMarkParsedFromLlm(true);

        String source = "import org.junit.jupiter.api.Test;\n"
                + "public class MyTest {\n"
                + "  @Test\n"
                + "  public void testUnresolvedScope() {\n"
                + "    Object o = org.mockito.Mockito.mock(Object.class);\n"
                + "  }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testUnresolvedScope");
        assertFalse(result.hasErrors(), "Unresolved scoped call should be salvaged in LLM mode");
        assertTrue(result.getTestCase().size() >= 1);
    }

    @Test
    void llmBestEffortFallsBackForNoMatchingConstructorSignature() {
        parser.setMarkParsedFromLlm(true);

        String source = "import org.junit.jupiter.api.Test;\n"
                + "import java.io.File;\n"
                + "public class MyTest {\n"
                + "  @Test\n"
                + "  public void testCtorMismatch() {\n"
                + "    File f = new File();\n"
                + "  }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testCtorMismatch");
        assertFalse(result.hasErrors(), "Constructor mismatch should be salvaged in LLM mode");
        assertEquals(1, result.getTestCase().size());
        assertInstanceOf(UninterpretedStatement.class, result.getTestCase().getStatement(0));
        assertTrue(result.getDiagnostics().stream()
                        .anyMatch(d -> d.getSeverity() == ParseDiagnostic.Severity.WARNING
                                && d.getMessage().contains("No matching constructor")),
                "Expected warning about no matching constructor");
    }

    @Test
    void llmBestEffortFallsBackForNoMatchingMethodSignature() {
        parser.setMarkParsedFromLlm(true);

        String source = "import org.junit.jupiter.api.Test;\n"
                + "import java.util.ArrayList;\n"
                + "public class MyTest {\n"
                + "  @Test\n"
                + "  public void testMethodMismatch() {\n"
                + "    ArrayList list = new ArrayList();\n"
                + "    int x = list.get(\"bad\");\n"
                + "  }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testMethodMismatch");
        assertFalse(result.hasErrors(), "Method mismatch should be salvaged in LLM mode");
        assertEquals(1, result.getTestCase().size(),
                "Unresolvable declaration value should be skipped (no synthetic typed fallback)");
        assertTrue(result.getDiagnostics().stream()
                        .anyMatch(d -> d.getSeverity() == ParseDiagnostic.Severity.WARNING
                                && d.getMessage().contains("No matching method")),
                "Expected warning about no matching method");
    }

    @Test
    void llmBestEffortLiftsFieldInitializersIntoTestMethodScope() {
        parser.setMarkParsedFromLlm(true);

        String source = "import org.junit.jupiter.api.Test;\n"
                + "public class MyTest {\n"
                + "  private int[] arr = new int[1];\n"
                + "  @Test\n"
                + "  public void testUsesFieldArray() {\n"
                + "    arr[0] = 7;\n"
                + "    int x = arr[0];\n"
                + "  }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testUsesFieldArray");
        assertFalse(result.hasErrors(), "LLM mode should lift field initializers for parsing");
        assertTrue(result.getTestCase().size() >= 3, "Expected lifted array + assignment statements");
        assertFalse(result.getDiagnostics().stream()
                        .anyMatch(d -> d.getMessage().contains("Unknown array variable")),
                "Field array should be resolved after lifting initializers");
    }

    @Test
    void llmBestEffortResolvesThisQualifiedMultiDimFieldArrayAccess() {
        parser.setMarkParsedFromLlm(true);

        String source = "import org.junit.jupiter.api.Test;\n"
                + "public class MyTest {\n"
                + "  private double[][] data = new double[1][1];\n"
                + "  @Test\n"
                + "  public void testUsesThisQualifiedFieldArray() {\n"
                + "    this.data[0][0] = 1.0;\n"
                + "    double x = this.data[0][0];\n"
                + "  }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testUsesThisQualifiedFieldArray");
        assertFalse(result.hasErrors(),
                "LLM mode should resolve this-qualified array field writes/reads");
        assertTrue(result.getTestCase().size() >= 3,
                "Expected lifted array + assignment statements");
        assertFalse(result.getDiagnostics().stream()
                        .anyMatch(d -> d.getMessage().contains("Unknown array variable")),
                "this-qualified field array accesses should resolve in LLM mode");
    }

    @Test
    void llmBestEffortFallbackDoesNotReemitUnresolvedExpressionInGeneratedCode() {
        parser.setMarkParsedFromLlm(true);

        String source = "import org.junit.jupiter.api.Test;\n"
                + "import java.awt.Color;\n"
                + "public class MyTest {\n"
                + "  @Test\n"
                + "  public void testFallbackRewrite() {\n"
                + "    Color color0 = ((java.awt.Label) error).getForeground();\n"
                + "  }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testFallbackRewrite");
        assertTrue(result.getTestCase().size() >= 1,
                "LLM mode should preserve unresolved expressions via fallback statements");

        String generated = result.getTestCase().toCode();
        assertTrue(generated.contains("__llm_fallback"),
                "Expected synthetic fallback variable in generated code:\n" + generated);
        assertFalse(generated.contains(") error).getForeground()"),
                "Generated code should not re-emit unresolved symbol 'error':\n" + generated);
    }

    @Test
    void llmBestEffortSkipsAssertionsForUnresolvedDeclarationValue() {
        parser.setMarkParsedFromLlm(true);

        String source = "import org.junit.jupiter.api.Test;\n"
                + "import java.util.ArrayList;\n"
                + "import static org.junit.jupiter.api.Assertions.*;\n"
                + "public class MyTest {\n"
                + "  @Test\n"
                + "  public void testNoBogusAssertion() {\n"
                + "    ArrayList list = new ArrayList();\n"
                + "    String s = list.get(1, 2);\n"
                + "    assertNotNull(s);\n"
                + "  }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testNoBogusAssertion");
        assertFalse(result.hasErrors(), "LLM mode should salvage parse without hard errors");
        String generated = result.getTestCase().toCode();
        assertFalse(generated.contains("assertNotNull("),
                "Assertion for unresolved declaration value should be dropped:\n" + generated);
        assertFalse(generated.contains("= null;"),
                "No synthetic null placeholder should be generated for unresolved declaration value:\n" + generated);
    }

    @Test
    void llmBestEffortResolvesMockPrefixedDeclaredType() {
        parser.setMarkParsedFromLlm(true);

        String source = "import org.junit.jupiter.api.Test;\n"
                + "import java.util.ArrayList;\n"
                + "public class MyTest {\n"
                + "  @Test\n"
                + "  public void testMockTypeFallback() {\n"
                + "    MockArrayList list = new MockArrayList();\n"
                + "    int size = list.size();\n"
                + "  }\n"
                + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testMockTypeFallback");
        assertFalse(result.hasErrors(), "Mock-prefixed declaration should be salvaged in LLM mode");
        assertTrue(result.getTestCase().size() >= 2);
    }

    @Test
    void llmBestEffortRewritesFileConstructionToOverrideMockWhenAvailable() {
        boolean oldUseVfs = RuntimeSettings.useVFS;
        try {
            RuntimeSettings.useVFS = true;
            parser.setMarkParsedFromLlm(true);

            String source = "import org.junit.jupiter.api.Test;\n"
                    + "import java.io.File;\n"
                    + "public class MyTest {\n"
                    + "  @Test\n"
                    + "  public void testFileCtor() {\n"
                    + "    File input = new File(\"in.mp4\");\n"
                    + "  }\n"
                    + "}\n";

        ParseResult result = parser.parseTestMethod(source, "testFileCtor");
        assertFalse(result.hasErrors(), "File constructor should remain parsable in LLM mode");
        assertEquals(2, result.getTestCase().size());
        assertInstanceOf(ConstructorStatement.class, result.getTestCase().getStatement(1));
        ConstructorStatement ctor = (ConstructorStatement) result.getTestCase().getStatement(1);
        assertEquals("org.evosuite.runtime.mock.java.io.MockFile",
                ctor.getConstructor().getConstructor().getDeclaringClass().getName(),
                "Expected File construction to be rewritten to OverrideMock MockFile");
        } finally {
            RuntimeSettings.useVFS = oldUseVfs;
        }
    }

    @Test
    void llmBestEffortRewritesStaticFileFactoryToOverrideMockWhenAvailable() {
        boolean oldUseVfs = RuntimeSettings.useVFS;
        try {
            RuntimeSettings.useVFS = true;
            parser.setMarkParsedFromLlm(true);

            String source = "import org.junit.jupiter.api.Test;\n"
                    + "import java.io.File;\n"
                    + "public class MyTest {\n"
                    + "  @Test\n"
                    + "  public void testTempFileFactory() throws Exception {\n"
                    + "    File tmp = File.createTempFile(\"aa\", \".tmp\");\n"
                    + "  }\n"
                    + "}\n";

            ParseResult result = parser.parseTestMethod(source, "testTempFileFactory");
            assertFalse(result.hasErrors(), "File static factory should remain parsable in LLM mode");

            MethodStatement staticCall = null;
            for (int i = 0; i < result.getTestCase().size(); i++) {
                if (result.getTestCase().getStatement(i) instanceof MethodStatement) {
                    staticCall = (MethodStatement) result.getTestCase().getStatement(i);
                }
            }
            assertNotNull(staticCall, "Expected a parsed method call statement");
            assertEquals("org.evosuite.runtime.mock.java.io.MockFile",
                    staticCall.getMethod().getMethod().getDeclaringClass().getName(),
                    "Expected File static factory to be rewritten to OverrideMock MockFile");
        } finally {
            RuntimeSettings.useVFS = oldUseVfs;
        }
    }

    @Test
    void llmBestEffortRewritesStaticNioFilesCallToStaticReplacementMockWhenAvailable() {
        boolean oldUseVfs = RuntimeSettings.useVFS;
        try {
            RuntimeSettings.useVFS = true;
            parser.setMarkParsedFromLlm(true);

            String source = "import org.junit.jupiter.api.Test;\n"
                    + "import java.nio.file.Files;\n"
                    + "import java.nio.file.Path;\n"
                    + "public class MyTest {\n"
                    + "  @Test\n"
                    + "  public void testCreateTempDirectory() throws Exception {\n"
                    + "    Path p = Files.createTempDirectory(\"llm-vfs\");\n"
                    + "  }\n"
                    + "}\n";

            ParseResult result = parser.parseTestMethod(source, "testCreateTempDirectory");
            assertFalse(result.hasErrors(), "Files static call should remain parsable in LLM mode");

            MethodStatement staticCall = null;
            for (int i = 0; i < result.getTestCase().size(); i++) {
                if (result.getTestCase().getStatement(i) instanceof MethodStatement) {
                    staticCall = (MethodStatement) result.getTestCase().getStatement(i);
                }
            }
            assertNotNull(staticCall, "Expected a parsed method call statement");

            Class<?> filesMock = MockList.getMockClass("java.nio.file.Files");
            String actualOwner = staticCall.getMethod().getMethod().getDeclaringClass().getName();
            if (filesMock != null) {
                assertEquals(filesMock.getName(), actualOwner,
                        "Expected Files static call to be rewritten to StaticReplacementMock target");
            } else {
                assertEquals("java.nio.file.Files", actualOwner,
                        "If MockFiles is unavailable on this runtime, parser should keep original owner");
            }
        } finally {
            RuntimeSettings.useVFS = oldUseVfs;
        }
    }
}
