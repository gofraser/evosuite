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

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.FunctionalMockForAbstractClassStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MockitoPatternParserTest {

    private static final List<String> MOCKITO_IMPORTS = List.of(
            "import java.util.*;",
            "import static org.mockito.Mockito.*;",
            "import org.evosuite.runtime.ViolatedAssumptionAnswer;"
    );

    private static final List<String> QUALIFIED_MOCKITO_IMPORTS = List.of(
            "import java.util.*;",
            "import org.mockito.Mockito;",
            "import org.evosuite.runtime.ViolatedAssumptionAnswer;"
    );

    private TestMethodParser methodParser;

    @BeforeEach
    void setUp() {
        methodParser = new TestMethodParser();
    }

    @Test
    void parseMockCreation() {
        Fixture fixture = fixture("List mockList = mock(List.class);", MOCKITO_IMPORTS, false);

        int consumed = fixture.mockitoPatternParser.handleVariableDeclarationWithLookahead(
                variableDeclaration(fixture, 0), fixture.astStatements, 0);

        assertEquals(1, consumed);
        assertFalse(fixture.result.hasErrors(), "Errors: " + fixture.result.getDiagnostics());
        assertTrue(fixture.testCase.size() >= 1, "Should have at least 1 statement, got " + fixture.testCase.size());
    }

    @Test
    void parseEvoSuiteMockPattern() {
        Fixture fixture = fixture(
                "List mockList = mock(List.class, new ViolatedAssumptionAnswer());\n"
                        + "doReturn(42).when(mockList).size();",
                MOCKITO_IMPORTS,
                false);

        int consumed = fixture.mockitoPatternParser.handleVariableDeclarationWithLookahead(
                variableDeclaration(fixture, 0), fixture.astStatements, 0);

        assertEquals(2, consumed);
        assertFalse(fixture.result.hasErrors(), "Errors: " + fixture.result.getDiagnostics());
        FunctionalMockStatement mockStmt = findFunctionalMockStatement(fixture.testCase);
        assertNotNull(mockStmt, "Should produce FunctionalMockStatement:\n" + fixture.testCase.toCode());
        assertEquals(List.class, mockStmt.getTargetClass());
        assertEquals(1, mockStmt.getMockedMethods().size(), "Should have 1 mocked method");
        assertEquals("size", mockStmt.getMockedMethods().get(0).getMethodName());
    }

    @Test
    void parseEvoSuiteMockForAbstractClass() {
        Fixture fixture = fixture(
                "List mockList = mock(List.class, CALLS_REAL_METHODS);\n"
                        + "doReturn(42).when(mockList).size();",
                MOCKITO_IMPORTS,
                false);

        int consumed = fixture.mockitoPatternParser.handleVariableDeclarationWithLookahead(
                variableDeclaration(fixture, 0), fixture.astStatements, 0);

        assertEquals(2, consumed);
        assertTrue(fixture.testCase.getStatement(1) instanceof FunctionalMockForAbstractClassStatement
                        || fixture.testCase.getStatement(0) instanceof FunctionalMockForAbstractClassStatement,
                "Should produce FunctionalMockForAbstractClassStatement:\n" + fixture.testCase.toCode());
    }

    @Test
    void parseMultipleStubbings() {
        Fixture fixture = fixture(
                "List mockList = mock(List.class, new ViolatedAssumptionAnswer());\n"
                        + "doReturn(42).when(mockList).size();\n"
                        + "doReturn(true).when(mockList).isEmpty();",
                MOCKITO_IMPORTS,
                false);

        int consumed = fixture.mockitoPatternParser.handleVariableDeclarationWithLookahead(
                variableDeclaration(fixture, 0), fixture.astStatements, 0);

        assertEquals(3, consumed);
        assertFalse(fixture.result.hasErrors(), "Errors: " + fixture.result.getDiagnostics());
        FunctionalMockStatement mockStmt = findFunctionalMockStatement(fixture.testCase);
        assertNotNull(mockStmt, "Should produce FunctionalMockStatement:\n" + fixture.testCase.toCode());
        assertEquals(2, mockStmt.getMockedMethods().size(), "Should have 2 mocked methods");
    }

    @Test
    void parseMockWithNoStubbings() {
        Fixture fixture = fixture(
                "List mockList = mock(List.class, new ViolatedAssumptionAnswer());",
                MOCKITO_IMPORTS,
                false);

        int consumed = fixture.mockitoPatternParser.handleVariableDeclarationWithLookahead(
                variableDeclaration(fixture, 0), fixture.astStatements, 0);

        assertEquals(1, consumed);
        assertFalse(fixture.result.hasErrors(), "Errors: " + fixture.result.getDiagnostics());
        assertNotNull(findFunctionalMockStatement(fixture.testCase),
                "Should produce FunctionalMockStatement with no stubbings:\n" + fixture.testCase.toCode());
    }

    @Test
    void parseStandardWhenThenReturn() {
        Fixture fixture = fixture(
                "List mockList = mock(List.class, new ViolatedAssumptionAnswer());\n"
                        + "when(mockList.size()).thenReturn(42);",
                MOCKITO_IMPORTS,
                false);

        int consumed = fixture.mockitoPatternParser.handleVariableDeclarationWithLookahead(
                variableDeclaration(fixture, 0), fixture.astStatements, 0);

        assertEquals(2, consumed);
        assertFalse(fixture.result.hasErrors(), "Errors: " + fixture.result.getDiagnostics());
        FunctionalMockStatement mockStmt = findFunctionalMockStatement(fixture.testCase);
        assertNotNull(mockStmt,
                "Should produce FunctionalMockStatement with when/thenReturn:\n" + fixture.testCase.toCode());
        assertEquals(1, mockStmt.getMockedMethods().size());
        assertEquals("size", mockStmt.getMockedMethods().get(0).getMethodName());
    }

    @Test
    void parseVerify() {
        Fixture fixture = fixture(
                "List mockList = mock(List.class);\n"
                        + "mockList.size();\n"
                        + "verify(mockList).size();",
                MOCKITO_IMPORTS,
                false);

        fixture.mockitoPatternParser.handleVariableDeclarationWithLookahead(
                variableDeclaration(fixture, 0), fixture.astStatements, 0);
        fixture.statementParser.parseStatement(fixture.astStatements.get(1), fixture.astStatements, 1);
        fixture.statementParser.parseStatement(fixture.astStatements.get(2), fixture.astStatements, 2);

        assertTrue(fixture.testCase.size() >= 2, "Should have at least 2 statements");
    }

    @Test
    void stubbingStopsAtNonStubbingStatement() {
        Fixture fixture = fixture(
                "List mockList = mock(List.class, new ViolatedAssumptionAnswer());\n"
                        + "doReturn(42).when(mockList).size();\n"
                        + "int x = mockList.size();",
                MOCKITO_IMPORTS,
                false);

        int consumed = fixture.mockitoPatternParser.handleVariableDeclarationWithLookahead(
                variableDeclaration(fixture, 0), fixture.astStatements, 0);
        fixture.statementParser.parseStatement(fixture.astStatements.get(2), fixture.astStatements, 2);

        assertEquals(2, consumed);
        FunctionalMockStatement mockStmt = findFunctionalMockStatement(fixture.testCase);
        assertNotNull(mockStmt, "Should produce FunctionalMockStatement:\n" + fixture.testCase.toCode());
        assertEquals(1, mockStmt.getMockedMethods().size());
        assertTrue(fixture.testCase.size() > 1, "Should have additional statements after the mock");
    }

    @Test
    void parseLlmStubbingWithUnresolvedReturnValueUsesTypedFallback() {
        Fixture fixture = fixture(
                "List mockList = mock(List.class, new ViolatedAssumptionAnswer());\n"
                        + "doReturn(missingValue).when(mockList).size();",
                MOCKITO_IMPORTS,
                true);

        int consumed = fixture.mockitoPatternParser.handleVariableDeclarationWithLookahead(
                variableDeclaration(fixture, 0), fixture.astStatements, 0);

        assertEquals(2, consumed);
        String code = fixture.testCase.toCode();
        assertFalse(code.contains("missingValue"), "Unresolved stubbing value leaked into emitted code:\n" + code);
        assertTrue(code.contains("int int0 = 0;")
                        && code.contains("doReturn(int0).when(")
                        && code.contains(").size();"),
                "Expected typed fallback stubbing for int return type:\n" + code);
    }

    @Test
    void standaloneStubbingHoistsReturnValueBeforeFunctionalMockEmission() {
        Fixture fixture = fixture(
                "List mockList = mock(List.class, new ViolatedAssumptionAnswer());\n"
                        + "Object expected = new Object();\n"
                        + "when(mockList.get(0)).thenReturn(expected);",
                MOCKITO_IMPORTS,
                true);

        int consumed = fixture.mockitoPatternParser.handleVariableDeclarationWithLookahead(
                variableDeclaration(fixture, 0), fixture.astStatements, 0);
        fixture.statementParser.parseStatement(fixture.astStatements.get(1), fixture.astStatements, 1);

        boolean handled = fixture.mockitoPatternParser.tryHandleStandaloneStubbingCall(methodCall(fixture, 2));

        assertEquals(1, consumed);
        assertTrue(handled, "Expected standalone stubbing call to be handled");
        String code = fixture.testCase.toCode();
        int expectedDecl = code.indexOf("Object object");
        int stubbing = code.indexOf("doReturn(");
        assertTrue(expectedDecl >= 0, "Expected hoisted object declaration:\n" + code);
        assertTrue(stubbing >= 0, "Expected generated stubbing:\n" + code);
        assertTrue(expectedDecl < stubbing,
                "Return value declaration must be emitted before stubbing:\n" + code);
    }

    @Test
    void llmCapturedOngoingStubbingThenReturnIsCollapsedBackToMockStubbing() throws Exception {
        Fixture fixture = fixture(
                "List mockList = mock(List.class, new ViolatedAssumptionAnswer());\n"
                        + "org.mockito.stubbing.OngoingStubbing<Boolean> ongoingStubbing1 = Mockito.when(mockList.isEmpty());\n"
                        + "boolean expected = true;\n"
                        + "ongoingStubbing1.thenReturn(expected);",
                QUALIFIED_MOCKITO_IMPORTS,
                true);

        fixture.mockitoPatternParser.handleVariableDeclarationWithLookahead(
                variableDeclaration(fixture, 0), fixture.astStatements, 0);
        int aliasConsumed = fixture.mockitoPatternParser.handleVariableDeclarationWithLookahead(
                variableDeclaration(fixture, 1), fixture.astStatements, 1);
        fixture.statementParser.parseStatement(fixture.astStatements.get(2), fixture.astStatements, 2);
        boolean handled = fixture.mockitoPatternParser.tryHandleCapturedWhenStubbingTerminalCall(methodCall(fixture, 3));

        assertEquals(1, aliasConsumed);
        assertTrue(handled, "Expected captured terminal call to be handled");
        assertFalse(fixture.result.hasErrors(), "Errors: " + fixture.result.getDiagnostics());
        String code = fixture.testCase.toCode();
        assertTrue(code.contains("doReturn(") && code.contains(").when(") && code.contains(".isEmpty();"),
                "Expected captured when/thenReturn pair to become FunctionalMock stubbing:\n" + code);
        assertFalse(code.contains("Mockito.when("),
                "Flattened Mockito.when(...) should not leak into emitted code:\n" + code);
        assertFalse(code.contains("OngoingStubbing"),
                "Temporary OngoingStubbing declarations should be eliminated:\n" + code);
        assertFalse(code.contains("thenReturn("),
                "Captured thenReturn call should be lowered into doReturn(...).when(...):\n" + code);
        executeAllStatements(fixture.testCase);
    }

    @Test
    void llmCapturedOngoingStubbingWithoutConsumableTerminalReportsDiagnostic() {
        Fixture fixture = fixture(
                "List mockList = mock(List.class, new ViolatedAssumptionAnswer());\n"
                        + "org.mockito.stubbing.OngoingStubbing<?> ongoingStubbing1 = Mockito.when(mockList.missing());\n"
                        + "ongoingStubbing1.thenReturn(42);",
                QUALIFIED_MOCKITO_IMPORTS,
                true);

        fixture.mockitoPatternParser.handleVariableDeclarationWithLookahead(
                variableDeclaration(fixture, 0), fixture.astStatements, 0);
        int aliasConsumed = fixture.mockitoPatternParser.handleVariableDeclarationWithLookahead(
                variableDeclaration(fixture, 1), fixture.astStatements, 1);
        boolean handled = fixture.mockitoPatternParser.tryHandleCapturedWhenStubbingTerminalCall(methodCall(fixture, 2));
        fixture.mockitoPatternParser.flushCapturedWhenStubbingDiagnostics();

        assertEquals(1, aliasConsumed);
        assertFalse(handled, "Invalid terminal call should remain unresolved");
        assertFalse(fixture.result.hasErrors(),
                "Stranded captured when alias should surface as warning: " + fixture.result.getDiagnostics());
        ParseDiagnostic diagnostic = fixture.result.getDiagnostics().stream()
                .filter(d -> d.getSeverity() == ParseDiagnostic.Severity.WARNING)
                .filter(d -> d.getMessage() != null
                        && d.getMessage().contains("captured `when(...)` alias `ongoingStubbing1` had no matching terminal"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing captured when alias diagnostic: " + fixture.result.getDiagnostics()));
        assertTrue(diagnostic.getMessage().contains("LLM_REPAIR_ACTION_REQUIRED:"),
                "Captured-when warning should include actionable repair guidance: " + diagnostic);
        assertEquals("Mockito.when(mockList.missing())", diagnostic.getSourceSnippet());
    }

    @Test
    void llmQualifiedMockitoMockOnClassVariableBecomesFunctionalMock() throws Exception {
        Fixture fixture = fixture(
                "Class<?> class0 = List.class;\n"
                        + "Mockito.mock((Class) class0);",
                QUALIFIED_MOCKITO_IMPORTS,
                true);

        fixture.statementParser.parseStatement(fixture.astStatements.get(0), fixture.astStatements, 0);
        MethodCallExpr methodCall = methodCall(fixture, 1);
        Class<?> targetClass = fixture.statementParser.resolveClassFromExpression(methodCall.getScope().orElseThrow());
        List<VariableReference> argRefs = fixture.statementParser.resolveArguments(methodCall.getArguments(), null, null);

        VariableReference mockRef = fixture.mockitoPatternParser.tryHandleLlmMockitoMockCall(
                methodCall, methodCall.getNameAsString(), targetClass, true, argRefs);

        assertNotNull(mockRef);
        assertFalse(fixture.result.hasErrors(), "Errors: " + fixture.result.getDiagnostics());
        assertTrue(fixture.testCase.getStatement(1) instanceof FunctionalMockStatement,
                "Expected standalone Mockito.mock(ClassVar) to normalize to FunctionalMockStatement:\n"
                        + fixture.testCase.toCode());
        FunctionalMockStatement mockStmt = (FunctionalMockStatement) fixture.testCase.getStatement(1);
        assertEquals(List.class, mockStmt.getTargetClass());
        assertFalse(fixture.testCase.toCode().contains("Mockito.mock((Class) class0)"),
                "Raw Mockito.mock(ClassVar) should not leak into emitted code:\n" + fixture.testCase.toCode());
        executeAllStatements(fixture.testCase);
    }

    @Test
    void llmWhenThenThrowStubbingOnVoidMethodIsRewrittenToDoThrow() throws Exception {
        Fixture fixture = fixture(
                "Runnable runnable0 = mock(Runnable.class, new ViolatedAssumptionAnswer());\n"
                        + "when(runnable0.run()).thenThrow(new RuntimeException());",
                MOCKITO_IMPORTS,
                true);

        fixture.mockitoPatternParser.handleVariableDeclarationWithLookahead(
                variableDeclaration(fixture, 0), fixture.astStatements, 0);
        fixture.statementParser.parseStatement(fixture.astStatements.get(1), fixture.astStatements, 1);

        String code = fixture.testCase.toCode();
        assertTrue(code.contains("doThrow(new RuntimeException()).when(runnable0).run();"),
                "Expected void Mockito throw-stubbing to be rewritten:\n" + code);
        assertFalse(code.contains("OngoingStubbing<?>"),
                "Throw-stubbing should not be flattened into OngoingStubbing temporaries:\n" + code);
        executeAllStatements(fixture.testCase);
    }

    @Test
    void parseLlmDoReturnWithOnlyNullReturnsUsesExplicitObjectCasts() {
        Fixture fixture = fixture(
                "List mockList = mock(List.class, new ViolatedAssumptionAnswer());\n"
                        + "doReturn(null, null).when(mockList).get(0);",
                MOCKITO_IMPORTS,
                true);

        int consumed = fixture.mockitoPatternParser.handleVariableDeclarationWithLookahead(
                variableDeclaration(fixture, 0), fixture.astStatements, 0);

        assertEquals(2, consumed);
        assertFalse(fixture.result.hasErrors(), "Errors: " + fixture.result.getDiagnostics());
        String code = fixture.testCase.toCode();
        assertFalse(code.contains("doReturn(null, null)"),
                "All-null Mockito doReturn should not render ambiguous varargs syntax:\n" + code);
        assertTrue(code.contains("doReturn((Object) null, (Object) null).when(list0).get(anyInt());"),
                "Expected explicit Object casts for all-null Mockito doReturn stubbing:\n" + code);
    }

    @Test
    void parseLlmAnonymousInterfaceDeclarationBecomesFunctionalMock() throws Exception {
        Fixture fixture = fixture(
                "java.util.List list0 = new java.util.List() {\n"
                        + "    @Override\n"
                        + "    public int size() {\n"
                        + "        return 7;\n"
                        + "    }\n"
                        + "};\n"
                        + "int size0 = list0.size();",
                List.of(),
                true);

        VariableDeclarator declarator = variableDeclaration(fixture, 0).getVariables().get(0);
        ObjectCreationExpr creation = declarator.getInitializer().filter(ObjectCreationExpr.class::isInstance)
                .map(ObjectCreationExpr.class::cast)
                .orElseThrow();
        VariableReference listRef = fixture.mockitoPatternParser.tryNormalizeAnonymousInterfaceCreationToMock(
                creation, fixture.typeResolver.resolveType(declarator.getType()));

        assertNotNull(listRef);
        fixture.scope.register("list0", listRef, GenericClassFactory.get(List.class));
        fixture.statementParser.parseStatement(fixture.astStatements.get(1), fixture.astStatements, 1);

        assertFalse(fixture.result.hasErrors(), "Errors: " + fixture.result.getDiagnostics());
        assertTrue(fixture.testCase.getStatement(0) instanceof FunctionalMockStatement,
                "Anonymous interface declarations should normalize to FunctionalMockStatement:\n"
                        + fixture.testCase.toCode());
        String code = fixture.testCase.toCode();
        assertFalse(code.contains("new java.util.List()"),
                "Partial anonymous interface implementation should not leak into emitted code:\n" + code);
        assertFalse(code.contains("__llm_fallback"),
                "Anonymous interface declarations should not degrade to fallback null:\n" + code);
        executeAllStatements(fixture.testCase);
    }

    @Test
    void parseLlmAnonymousInterfaceWithComplexBodyStillBecomesFunctionalMock() throws Exception {
        Fixture fixture = fixture(
                "java.util.function.Supplier supplier0 = new java.util.function.Supplier() {\n"
                        + "    @Override\n"
                        + "    public Object get() {\n"
                        + "        Object dbo = new Object();\n"
                        + "        return new Object[] { dbo };\n"
                        + "    }\n"
                        + "};\n"
                        + "Object object0 = supplier0.get();",
                List.of("import java.util.function.Supplier;"),
                true);

        VariableDeclarator declarator = variableDeclaration(fixture, 0).getVariables().get(0);
        ObjectCreationExpr creation = declarator.getInitializer().filter(ObjectCreationExpr.class::isInstance)
                .map(ObjectCreationExpr.class::cast)
                .orElseThrow();
        VariableReference supplierRef = fixture.mockitoPatternParser.tryNormalizeAnonymousInterfaceCreationToMock(
                creation, fixture.typeResolver.resolveType(declarator.getType()));

        assertNotNull(supplierRef);
        fixture.scope.register("supplier0", supplierRef, GenericClassFactory.get(java.util.function.Supplier.class));
        fixture.statementParser.parseStatement(fixture.astStatements.get(1), fixture.astStatements, 1);

        assertFalse(fixture.result.hasErrors(), "Errors: " + fixture.result.getDiagnostics());
        assertTrue(fixture.testCase.getStatement(0) instanceof FunctionalMockStatement,
                "Anonymous interface with complex body should normalize to FunctionalMockStatement:\n"
                        + fixture.testCase.toCode());
        String code = fixture.testCase.toCode();
        assertFalse(code.contains("new java.util.function.Supplier()"),
                "Complex anonymous interface implementation should not leak into emitted code:\n" + code);
        assertFalse(code.contains("return new Object[]"),
                "Anonymous interface body should be discarded after mock normalization:\n" + code);
        assertFalse(code.contains("__llm_fallback"),
                "Anonymous interface declarations should not degrade to fallback null:\n" + code);
        executeAllStatements(fixture.testCase);
    }

    private Fixture fixture(String body, List<String> imports, boolean markParsedFromLlm) {
        String wrapped = methodParser.wrapMethodBody(body, imports);
        CompilationUnit cu = methodParser.parseSource(wrapped);
        MethodDeclaration method = methodParser.findTestMethod(cu, "__testMethod__")
                .orElseThrow(() -> new AssertionError("Missing synthetic test method"));

        DefaultTestCase testCase = new DefaultTestCase();
        ParseResult result = new ParseResult(testCase, "__testMethod__");
        TypeResolver typeResolver = new TypeResolver(getClass().getClassLoader(), methodParser.extractImports(cu));
        VariableScope scope = new VariableScope();
        StatementParser statementParser = new StatementParser(testCase, typeResolver, scope, result);
        statementParser.setMarkParsedFromLlm(markParsedFromLlm);

        return new Fixture(
                testCase,
                result,
                typeResolver,
                scope,
                statementParser,
                statementParser.getMockitoPatternParser(),
                methodParser.extractBody(method));
    }

    private static VariableDeclarationExpr variableDeclaration(Fixture fixture, int index) {
        return (VariableDeclarationExpr) ((ExpressionStmt) fixture.astStatements.get(index)).getExpression();
    }

    private static MethodCallExpr methodCall(Fixture fixture, int index) {
        return (MethodCallExpr) ((ExpressionStmt) fixture.astStatements.get(index)).getExpression();
    }

    private static FunctionalMockStatement findFunctionalMockStatement(TestCase testCase) {
        for (int i = 0; i < testCase.size(); i++) {
            if (testCase.getStatement(i) instanceof FunctionalMockStatement) {
                return (FunctionalMockStatement) testCase.getStatement(i);
            }
        }
        return null;
    }

    private void executeAllStatements(TestCase testCase) throws Exception {
        Scope scope = new Scope();
        for (int i = 0; i < testCase.size(); i++) {
            Throwable thrown = testCase.getStatement(i).execute(scope, System.out);
            assertNull(thrown, "Statement " + i + " failed: " + testCase.getStatement(i).getCode());
        }
    }

    private static final class Fixture {
        private final DefaultTestCase testCase;
        private final ParseResult result;
        private final TypeResolver typeResolver;
        private final VariableScope scope;
        private final StatementParser statementParser;
        private final MockitoPatternParser mockitoPatternParser;
        private final List<com.github.javaparser.ast.stmt.Statement> astStatements;

        private Fixture(DefaultTestCase testCase,
                        ParseResult result,
                        TypeResolver typeResolver,
                        VariableScope scope,
                        StatementParser statementParser,
                        MockitoPatternParser mockitoPatternParser,
                        List<com.github.javaparser.ast.stmt.Statement> astStatements) {
            this.testCase = testCase;
            this.result = result;
            this.typeResolver = typeResolver;
            this.scope = scope;
            this.statementParser = statementParser;
            this.mockitoPatternParser = mockitoPatternParser;
            this.astStatements = astStatements;
        }
    }
}
