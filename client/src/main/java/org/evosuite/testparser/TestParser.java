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
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
        List<MethodDeclaration> nonTestMethods = markParsedFromLlm
                ? methodParser.findNonTestMethods(cu)
                : Collections.emptyList();
        List<com.github.javaparser.ast.stmt.Statement> llmFieldInitializers = markParsedFromLlm
                ? methodParser.extractFieldInitializerStatements(cu)
                : Collections.emptyList();
        Map<String, MethodDeclaration> inlineHelperMethods = markParsedFromLlm
                ? extractInlineableHelperMethods(nonTestMethods)
                : Collections.emptyMap();

        List<ParseResult> results = new ArrayList<>();
        for (MethodDeclaration method : testMethods) {
            ParseResult result = parseMethod(method, imports, inlineHelperMethods, llmFieldInitializers);
            if (markParsedFromLlm && !nonTestMethods.isEmpty()) {
                addLlmHelperMethodDiagnostic(result, nonTestMethods, ParseDiagnostic.Severity.WARNING);
            }
            results.add(result);
        }
        if (results.isEmpty() && markParsedFromLlm && !nonTestMethods.isEmpty()) {
            ParseResult result = new ParseResult(new DefaultTestCase(), "__class__");
            addLlmHelperMethodDiagnostic(result, nonTestMethods, ParseDiagnostic.Severity.WARNING);
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
        List<MethodDeclaration> nonTestMethods = markParsedFromLlm
                ? methodParser.findNonTestMethods(cu)
                : Collections.emptyList();
        List<com.github.javaparser.ast.stmt.Statement> llmFieldInitializers = markParsedFromLlm
                ? methodParser.extractFieldInitializerStatements(cu)
                : Collections.emptyList();
        Map<String, MethodDeclaration> inlineHelperMethods = markParsedFromLlm
                ? extractInlineableHelperMethods(nonTestMethods)
                : Collections.emptyMap();

        Optional<MethodDeclaration> method = methodParser.findTestMethod(cu, methodName);
        if (!method.isPresent()) {
            ParseResult result = new ParseResult(new DefaultTestCase(), methodName);
            result.addDiagnostic(new ParseDiagnostic(
                    ParseDiagnostic.Severity.ERROR,
                    "Test method not found: " + methodName,
                    0, null));
            return result;
        }

        ParseResult parsed = parseMethod(method.get(), imports, inlineHelperMethods, llmFieldInitializers);
        if (markParsedFromLlm && !nonTestMethods.isEmpty()) {
            addLlmHelperMethodDiagnostic(parsed, nonTestMethods, ParseDiagnostic.Severity.WARNING);
        }
        return parsed;
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
        return parseMethod(method, imports, Collections.emptyMap(), Collections.emptyList());
    }

    private ParseResult parseMethod(MethodDeclaration method,
                                    List<String> imports,
                                    Map<String, MethodDeclaration> inlineHelperMethods,
                                    List<com.github.javaparser.ast.stmt.Statement> llmFieldInitializers) {
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
        stmtParser.setInlineHelperMethods(inlineHelperMethods);

        if (this.markParsedFromLlm && llmFieldInitializers != null && !llmFieldInitializers.isEmpty()) {
            for (com.github.javaparser.ast.stmt.Statement fieldStmt : llmFieldInitializers) {
                try {
                    stmtParser.parseStatement(fieldStmt);
                } catch (Exception e) {
                    logger.debug("Failed to parse lifted LLM field initializer: {}", fieldStmt, e);
                }
            }
        }

        int statementsBeforeParse = testCase.size();
        List<com.github.javaparser.ast.stmt.Statement> astStatements = methodParser.extractBody(method);
        for (int i = 0; i < astStatements.size(); ) {
            com.github.javaparser.ast.stmt.Statement astStmt = astStatements.get(i);
            try {
                int consumed = stmtParser.parseStatement(astStmt, astStatements, i);
                i += consumed;
            } catch (Throwable t) {
                logger.warn("Failed to parse statement: {}", astStmt, t);
                int line = astStmt.getBegin().map(p -> p.line).orElse(0);
                result.addDiagnostic(new ParseDiagnostic(
                        ParseDiagnostic.Severity.ERROR,
                        "Failed to parse statement: " + formatThrowable(t),
                        line,
                        astStmt.toString()));
                // Preserve as UninterpretedStatement so the source is not lost. If that
                // also fails validation, skip this statement and continue with the rest.
                try {
                    testCase.addStatement(stmtParser.createUninterpretedStatementFromAst(astStmt));
                } catch (Throwable fallbackFailure) {
                    logger.warn("Failed to preserve unparsable statement as UninterpretedStatement: {}",
                            astStmt, fallbackFailure);
                    result.addDiagnostic(new ParseDiagnostic(
                            ParseDiagnostic.Severity.ERROR,
                            "Failed to preserve unparsable statement: " + formatThrowable(fallbackFailure),
                            line,
                            astStmt.toString()));
                }
                i++;
            }
        }

        // Mark all statements created during this parse pass as LLM-originated
        if (this.markParsedFromLlm) {
            for (int i = statementsBeforeParse; i < testCase.size(); i++) {
                testCase.getStatement(i).setParsedFromLlm(true);
            }
        }

        // Final validation: check that all VariableReferences in every statement
        // point to valid statement indices within this test case.
        validateVariableReferences(testCase, result);

        return result;
    }

    /**
     * Validate that every VariableReference used in the test case points to a valid
     * statement index. Orphaned references (pos &lt; 0 or pos &gt;= testCase.size())
     * indicate a parse error and the test should be rejected by the caller.
     *
     * <p>All orphans are reported — the loop does not short-circuit on the first
     * one — so the diagnostic list faithfully reflects the full extent of the
     * breakage. Callers are expected to check {@link ParseResult#hasErrors()}
     * before using the returned {@code TestCase}; this method does not mutate
     * the test case itself.
     */
    private void validateVariableReferences(DefaultTestCase testCase, ParseResult result) {
        int size = testCase.size();
        int orphanCount = 0;
        for (int i = 0; i < size; i++) {
            Statement stmt = testCase.getStatement(i);
            Set<VariableReference> refs = stmt.getVariableReferences();
            for (VariableReference ref : refs) {
                int pos = ref.getStPosition();
                if (pos < 0 || pos >= size) {
                    orphanCount++;
                    result.addDiagnostic(new ParseDiagnostic(
                            ParseDiagnostic.Severity.ERROR,
                            "Orphaned variable reference at statement " + i
                                    + ": refers to statement " + pos
                                    + " but test case has " + size + " statements",
                            0, stmt.getCode()));
                }
            }
        }
        if (orphanCount > 0) {
            logger.warn("Parsed test case has {} orphaned variable reference(s) across {} statements",
                    orphanCount, size);
        }
    }

    private void addLlmHelperMethodDiagnostic(ParseResult result,
                                              List<MethodDeclaration> nonTestMethods,
                                              ParseDiagnostic.Severity severity) {
        MethodDeclaration first = nonTestMethods.get(0);
        int line = first.getBegin().map(p -> p.line).orElse(0);
        String helperNames = nonTestMethods.stream()
                .map(MethodDeclaration::getNameAsString)
                .distinct()
                .collect(Collectors.joining(", "));
        String msg = "LLM pre-check: helper/lifecycle methods are not allowed. "
                + "Found non-@Test method(s): " + helperNames
                + ". Continuing parse in best-effort mode; inline these helpers to improve reliability.";
        result.addDiagnostic(new ParseDiagnostic(
                severity,
                msg,
                line,
                first.getDeclarationAsString(false, false, false)));
    }

    private static String formatThrowable(Throwable t) {
        if (t == null) {
            return "Unknown error";
        }
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return t.getClass().getSimpleName() + ": " + message;
    }

    private static String inlineHelperKey(String methodName, int arity) {
        return methodName + "#" + arity;
    }

    /**
     * Extract helper methods that can be safely inlined by simple substitution:
     * arity 0 or 1, body of exactly one statement, and that statement is either
     * {@code return <expr>;} or a single expression statement
     * (for side-effect/void helpers). Overloaded duplicates for the same
     * name/arity are considered ambiguous and skipped.
     */
    private Map<String, MethodDeclaration> extractInlineableHelperMethods(List<MethodDeclaration> nonTestMethods) {
        Map<String, MethodDeclaration> inlineable = new LinkedHashMap<>();
        Set<String> ambiguousKeys = new HashSet<>();
        for (MethodDeclaration method : nonTestMethods) {
            int arity = method.getParameters().size();
            if (arity > 1 || !method.getBody().isPresent()) {
                continue;
            }
            List<com.github.javaparser.ast.stmt.Statement> statements = method.getBody().get().getStatements();
            if (statements.size() != 1) {
                continue;
            }
            com.github.javaparser.ast.stmt.Statement only = statements.get(0);
            if (only instanceof ReturnStmt) {
                ReturnStmt returnStmt = (ReturnStmt) only;
                if (!returnStmt.getExpression().isPresent()) {
                    continue;
                }
            } else if (!(only instanceof ExpressionStmt)) {
                continue;
            }
            String key = inlineHelperKey(method.getNameAsString(), arity);
            if (ambiguousKeys.contains(key)) {
                continue;
            }
            if (inlineable.containsKey(key)) {
                inlineable.remove(key);
                ambiguousKeys.add(key);
                continue;
            }
            inlineable.put(key, method.clone());
        }
        return inlineable;
    }
}
