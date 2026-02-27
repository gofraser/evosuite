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
package org.evosuite.testparser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.evosuite.testcase.DefaultTestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses JUnit test source code into EvoSuite TestCase objects.
 *
 * <p>This is the main entry point for the test parser. It coordinates
 * {@link TestMethodParser} (AST extraction) and {@link StatementParser}
 * (AST → EvoSuite statement conversion).
 *
 * <p>Usage:
 * <pre>
 *   TestParser parser = new TestParser(classLoader);
 *   ParseResult result = parser.parseTestMethod(sourceCode, "testFoo");
 *   TestCase tc = result.getTestCase();
 * </pre>
 */
public class TestParser {

    private static final Logger logger = LoggerFactory.getLogger(TestParser.class);

    private final ClassLoader classLoader;
    private final TestMethodParser methodParser;

    /** When true, all statements produced by this parser are marked as LLM-parsed. */
    private boolean markParsedFromLlm = false;

    public TestParser(ClassLoader classLoader) {
        this.classLoader = classLoader;
        this.methodParser = new TestMethodParser();
    }

    /**
     * Sets whether test cases produced by this parser should have their
     * statements marked as originating from LLM-generated code.
     */
    public void setMarkParsedFromLlm(boolean mark) {
        this.markParsedFromLlm = mark;
    }

    /**
     * Returns whether this parser marks statements with LLM provenance.
     */
    public boolean isMarkParsedFromLlm() {
        return this.markParsedFromLlm;
    }

    /**
     * Create a TestParser that uses EvoSuite's instrumented classloader for the SUT.
     * This should be used during test generation when running inside EvoSuite.
     *
     * @return a TestParser configured with the SUT classloader
     */
    public static TestParser forSUT() {
        return new TestParser(
                org.evosuite.TestGenerationContext.getInstance().getClassLoaderForSUT());
    }

    /**
     * Create a TestParser for LLM-generated code. Statements created by this parser
     * will be marked with LLM provenance ({@code parsedFromLlm = true}).
     *
     * @return a TestParser configured with the SUT classloader and LLM provenance marking
     */
    public static TestParser forSUTWithLlmProvenance() {
        TestParser parser = forSUT();
        parser.setMarkParsedFromLlm(true);
        return parser;
    }

    /**
     * Parse a complete test class source file. Returns one ParseResult per @Test method.
     *
     * @param sourceCode full Java source of a test class
     * @return list of ParseResults, one per @Test method found
     */
    public List<ParseResult> parseTestClass(String sourceCode) {
        CompilationUnit cu = methodParser.parseSource(sourceCode);
        List<String> imports = methodParser.extractImports(cu);
        List<MethodDeclaration> testMethods = methodParser.findTestMethods(cu);

        List<ParseResult> results = new ArrayList<>();
        for (MethodDeclaration method : testMethods) {
            ParseResult result = parseMethod(method, imports);
            results.add(result);
        }
        return results;
    }

    /**
     * Parse a single test method from a full class source.
     *
     * @param sourceCode full Java source of test class
     * @param methodName name of the @Test method to parse
     * @return ParseResult, or a result with an empty TestCase and an ERROR diagnostic if not found
     */
    public ParseResult parseTestMethod(String sourceCode, String methodName) {
        CompilationUnit cu = methodParser.parseSource(sourceCode);
        List<String> imports = methodParser.extractImports(cu);

        Optional<MethodDeclaration> method = methodParser.findTestMethod(cu, methodName);
        if (!method.isPresent()) {
            ParseResult result = new ParseResult(new DefaultTestCase(), methodName);
            result.addDiagnostic(new ParseDiagnostic(
                    ParseDiagnostic.Severity.ERROR,
                    "Test method not found: " + methodName,
                    0, null));
            return result;
        }

        return parseMethod(method.get(), imports);
    }

    /**
     * Parse a single test method body (statements only, no method signature).
     * Import context must be provided separately.
     *
     * @param methodBody just the statements (no method declaration wrapper)
     * @param imports    import statements for type resolution
     * @return ParseResult
     */
    public ParseResult parseTestMethodBody(String methodBody, List<String> imports) {
        return parseTestMethodBody(methodBody, imports, null);
    }

    /**
     * Parse a single test method body with a package context.
     * The package enables same-package type resolution (e.g. resolving SUT
     * classes by simple name when the test is in the same package).
     *
     * @param methodBody  just the statements (no method declaration wrapper)
     * @param imports     import statements for type resolution
     * @param packageName package for same-package resolution, or null to omit
     * @return ParseResult
     */
    public ParseResult parseTestMethodBody(String methodBody, List<String> imports, String packageName) {
        String wrapped = methodParser.wrapMethodBody(methodBody, imports, packageName);
        return parseTestMethod(wrapped, "__testMethod__");
    }

    /**
     * Parse a single MethodDeclaration into a ParseResult.
     */
    private ParseResult parseMethod(MethodDeclaration method, List<String> imports) {
        String methodName = method.getNameAsString();
        DefaultTestCase testCase = new DefaultTestCase();
        ParseResult result = new ParseResult(testCase, methodName);

        // Extract JUnit 4 @Test(expected=...) if present
        String expectedException = methodParser.extractExpectedException(method);
        if (expectedException != null) {
            result.setExpectedExceptionClass(expectedException);
        }

        TypeResolver typeResolver = new TypeResolver(classLoader, imports);
        VariableScope scope = new VariableScope();
        StatementParser stmtParser = new StatementParser(testCase, typeResolver, scope, result);
        stmtParser.setMarkParsedFromLlm(this.markParsedFromLlm);

        int statementsBeforeParse = testCase.size();
        List<com.github.javaparser.ast.stmt.Statement> astStatements = methodParser.extractBody(method);
        for (int i = 0; i < astStatements.size(); ) {
            com.github.javaparser.ast.stmt.Statement astStmt = astStatements.get(i);
            try {
                int consumed = stmtParser.parseStatement(astStmt, astStatements, i);
                i += consumed;
            } catch (Exception e) {
                logger.warn("Failed to parse statement: {}", astStmt, e);
                int line = astStmt.getBegin().map(p -> p.line).orElse(0);
                result.addDiagnostic(new ParseDiagnostic(
                        ParseDiagnostic.Severity.ERROR,
                        "Failed to parse statement: " + e.getMessage(),
                        line,
                        astStmt.toString()));
                // Preserve as UninterpretedStatement so the source is not lost
                testCase.addStatement(stmtParser.createUninterpretedStatementFromAst(astStmt));
                i++;
            }
        }

        // Mark all statements created during this parse pass as LLM-originated
        if (this.markParsedFromLlm) {
            for (int i = statementsBeforeParse; i < testCase.size(); i++) {
                testCase.getStatement(i).setParsedFromLlm(true);
            }
        }

        return result;
    }
}
