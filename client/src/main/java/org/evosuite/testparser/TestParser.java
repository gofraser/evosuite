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
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.ConstantValue;
import org.evosuite.testcase.variable.FieldReference;
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
     * Returns the class loader used to resolve SUT types while parsing.
     */
    public ClassLoader getClassLoader() {
        return classLoader;
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

        stmtParser.finalizeParse();

        // Final validation: check that all VariableReferences in every statement
        // point to valid statement indices within this test case.
        validateVariableReferences(testCase, result);

        return result;
    }

    /**
     * Validate that every VariableReference used in the test case is the return
     * value of some statement in the same test case. References that no longer
     * resolve to a defining statement indicate a parse-time corruption and the
     * test should be rejected by the caller.
     *
     * <p>All orphans are reported — the loop does not short-circuit on the first
     * one — so the diagnostic list faithfully reflects the full extent of the
     * breakage. Callers are expected to check {@link ParseResult#hasErrors()}
     * before using the returned {@code TestCase}; this method does not mutate
     * the test case itself.
     */
    private void validateVariableReferences(DefaultTestCase testCase, ParseResult result) {
        List<String> orphans = findOrphanedVariableReferences(testCase);
        if (orphans.isEmpty()) {
            return;
        }
        for (String orphan : orphans) {
            result.addDiagnostic(new ParseDiagnostic(
                    ParseDiagnostic.Severity.ERROR,
                    "Orphaned variable reference: " + orphan,
                    0, ""));
        }
        logger.warn("Parsed test case has {} orphaned variable reference(s) across {} statements",
                orphans.size(), testCase.size());
    }

    /**
     * Returns descriptions of any orphaned {@link VariableReference}s in
     * {@code testCase}. A reference is "orphaned" when no statement in the
     * test case can resolve it — either as a return value (for ordinary refs)
     * or as a referenced parameter (for {@link ConstantValue}). Returns an
     * empty list when every reference resolves.
     *
     * <p>This check is intentionally <em>cache-independent</em>: it does not
     * call {@link VariableReference#getStPosition()}. The cached
     * {@code stPosition} can return a stale-but-in-range value if the
     * change listener was already consumed since the test case mutated, and
     * that stale value would mask the very orphan we are trying to catch
     * (it shows up later inside {@code copy()}/{@code clone()} where the
     * cache is invalidated and recompute throws). Instead, we mirror each
     * subclass's resolution rule against the current test case directly.
     */
    public static List<String> findOrphanedVariableReferences(TestCase testCase) {
        if (testCase == null) {
            return Collections.emptyList();
        }
        int size = testCase.size();
        List<String> orphans = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Statement stmt;
            try {
                stmt = testCase.getStatement(i);
            } catch (Throwable t) {
                orphans.add("statement " + i + ": cannot fetch statement (" + describeThrowable(t) + ")");
                continue;
            }
            Set<VariableReference> refs;
            try {
                refs = stmt.getVariableReferences();
            } catch (Throwable t) {
                orphans.add("statement " + i + " (" + stmt.getClass().getSimpleName()
                        + "): cannot inspect variable references (" + describeThrowable(t) + ")");
                continue;
            }
            if (refs == null) {
                continue;
            }
            for (VariableReference ref : refs) {
                if (ref == null) {
                    continue;
                }
                if (!isResolvable(ref, testCase, 0)) {
                    orphans.add(describeOrphan(i, stmt, ref, size,
                            "no matching defining statement in test case"));
                }
            }
        }
        return orphans;
    }

    /**
     * Mirrors the resolution rules used by each {@code VariableReference}
     * subclass's {@code getStPosition} but works directly against the current
     * test case state, without consulting any cached position. Recursion
     * handles ArrayIndex/FieldReference delegation to their source ref;
     * depth is bounded to defeat any pathological reference cycle.
     */
    private static boolean isResolvable(VariableReference ref, TestCase testCase, int depth) {
        if (ref == null || depth > 8) {
            return false;
        }
        int size = testCase.size();
        if (ref instanceof ConstantValue) {
            // ConstantValue resolves when referenced as a parameter by some statement.
            for (int i = 0; i < size; i++) {
                try {
                    if (testCase.getStatement(i).references(ref)) {
                        return true;
                    }
                } catch (Throwable ignored) {
                    // Fall through; treat as not-here.
                }
            }
            return false;
        }
        // Ordinary refs (incl. VariableReferenceImpl, NullReference, ArrayReference)
        // resolve when they equal some statement's return value.
        for (int i = 0; i < size; i++) {
            VariableReference rv;
            try {
                rv = testCase.getStatement(i).getReturnValue();
            } catch (Throwable ignored) {
                continue;
            }
            if (rv != null && rv.equals(ref)) {
                return true;
            }
        }
        if (ref instanceof ArrayIndex) {
            return isResolvable(((ArrayIndex) ref).getArray(), testCase, depth + 1);
        }
        if (ref instanceof FieldReference) {
            return isResolvable(((FieldReference) ref).getSource(), testCase, depth + 1);
        }
        return false;
    }

    /**
     * Returns descriptions of any statements whose used VariableReferences
     * resolve only via the statement itself (or a later statement), and would
     * therefore crash inside {@code Statement.copy(newTestCase, offset)} when
     * the test case is replayed positionally — most commonly during
     * ObjectPool sequence insertion or crossover-driven appends. Returns an
     * empty list when every used reference has an earlier defining statement.
     *
     * <p>Background: {@link AssignmentStatement#copy(TestCase, int)} resolves
     * each used reference via {@code getStatement(getStPosition() + offset)}
     * on the destination test case. That destination only holds positions
     * {@code [0, P + offset)} at the moment statement {@code P} is being
     * copied, so the lookup is safe only when the reference's first defining
     * statement sits strictly before {@code P}. For plain
     * {@link org.evosuite.testcase.variable.VariableReferenceImpl} retvals
     * (the LLM parser's {@code x = y} variable-assignment path), salvage
     * pipelines that {@code chop} or remove the original declaration leave
     * the AssignmentStatement as the only match — the lookup then lands at
     * {@code P + offset == destination.size()} and throws
     * {@code IllegalArgumentException} ("wrong position N, total N").
     */
    public static List<String> findUnsafelyCopyableStatements(TestCase testCase) {
        if (testCase == null) {
            return Collections.emptyList();
        }
        int size = testCase.size();
        List<String> issues = new ArrayList<>();
        for (int p = 0; p < size; p++) {
            Statement stmt;
            try {
                stmt = testCase.getStatement(p);
            } catch (Throwable t) {
                issues.add("statement " + p + ": cannot fetch statement (" + describeThrowable(t) + ")");
                continue;
            }
            if (!(stmt instanceof org.evosuite.testcase.statements.AssignmentStatement)) {
                // Other statement copies derive their retval freshly and only
                // consult parameter/callee positions, which are checked by
                // findOrphanedVariableReferences. The strict-ordering hazard
                // is unique to AssignmentStatement's reuse of an existing
                // VariableReference as its retval.
                continue;
            }
            org.evosuite.testcase.statements.AssignmentStatement as =
                    (org.evosuite.testcase.statements.AssignmentStatement) stmt;
            VariableReference param = as.getValue();
            if (param != null && !hasDefiningStatementBefore(param, testCase, p)) {
                issues.add(describeUnsafeRef(p, stmt, param, "parameter"));
            }
            VariableReference retval = stmt.getReturnValue();
            VariableReference resolvable = retvalLookupReference(retval);
            if (resolvable != null && !hasDefiningStatementBefore(resolvable, testCase, p)) {
                String label = (resolvable == retval) ? "retval" :
                        (retval instanceof ArrayIndex ? "array source of retval"
                                : "source of retval field");
                issues.add(describeUnsafeRef(p, stmt, resolvable, label));
            }
        }
        return issues;
    }

    /**
     * Returns the reference whose {@code getStPosition()} drives the
     * destination lookup inside {@code retval.copy()}. ArrayIndex and
     * FieldReference delegate to their underlying array/source position; a
     * plain VariableReference is consulted directly.
     */
    private static VariableReference retvalLookupReference(VariableReference retval) {
        if (retval == null) {
            return null;
        }
        if (retval instanceof ArrayIndex) {
            return ((ArrayIndex) retval).getArray();
        }
        if (retval instanceof FieldReference) {
            return ((FieldReference) retval).getSource();
        }
        return retval;
    }

    private static boolean hasDefiningStatementBefore(VariableReference ref,
                                                      TestCase testCase, int boundary) {
        if (ref == null || ref instanceof ConstantValue) {
            return true;
        }
        for (int i = 0; i < boundary; i++) {
            VariableReference rv;
            try {
                rv = testCase.getStatement(i).getReturnValue();
            } catch (Throwable ignored) {
                continue;
            }
            if (rv != null && rv.equals(ref)) {
                return true;
            }
        }
        return false;
    }

    private static String describeUnsafeRef(int stmtIdx, Statement stmt, VariableReference ref,
                                            String role) {
        String typeName;
        try {
            typeName = ref.getType() == null ? "<unknown>" : ref.getType().getTypeName();
        } catch (Throwable t) {
            typeName = "<unknown>";
        }
        return "statement " + stmtIdx + " (" + stmt.getClass().getSimpleName()
                + ") " + role + " of type " + typeName
                + " [" + ref.getClass().getSimpleName()
                + "] has no defining statement before position " + stmtIdx
                + " (Statement.copy would fail at this position)";
    }

    private static String describeOrphan(int stmtIdx, Statement stmt, VariableReference ref,
                                         int size, String reason) {
        String typeName;
        try {
            typeName = ref.getType() == null ? "<unknown>" : ref.getType().getTypeName();
        } catch (Throwable t) {
            typeName = "<unknown>";
        }
        return "statement " + stmtIdx + " (" + stmt.getClass().getSimpleName()
                + ") references orphan variable of type " + typeName
                + " [" + ref.getClass().getSimpleName() + "]: " + reason
                + " (test case size " + size + ")";
    }

    private static String describeThrowable(Throwable t) {
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
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
     * Extract helper methods that can be safely inlined.
     *
     * <p>Eligibility:
     * <ul>
     *   <li>Arity 0 or 1.</li>
     *   <li>Body has at least one statement.</li>
     *   <li>The terminal statement is either a {@code return <expr>;} or a plain
     *   expression statement (for void/side-effect helpers).</li>
     *   <li>Every preceding statement is a plain expression statement
     *   (typically a local-variable declaration or a simple side-effect call).</li>
     * </ul>
     * Anything else (control flow, throws, try/catch, blocks, etc.) is rejected.
     * Overloaded duplicates for the same name/arity are considered ambiguous and
     * skipped.
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
            if (!isInlineableBody(statements)) {
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

    private static boolean isInlineableBody(List<com.github.javaparser.ast.stmt.Statement> statements) {
        int n = statements.size();
        if (n == 0) {
            return false;
        }
        com.github.javaparser.ast.stmt.Statement terminal = statements.get(n - 1);
        if (terminal instanceof ReturnStmt) {
            if (!((ReturnStmt) terminal).getExpression().isPresent()) {
                return false;
            }
        } else if (!(terminal instanceof ExpressionStmt)) {
            return false;
        }
        for (int i = 0; i < n - 1; i++) {
            if (!(statements.get(i) instanceof ExpressionStmt)) {
                return false;
            }
        }
        return true;
    }
}
