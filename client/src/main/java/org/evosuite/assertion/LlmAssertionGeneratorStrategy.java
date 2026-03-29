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
package org.evosuite.assertion;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.NameExpr;
import org.evosuite.Properties;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.postprocess.LlmAssertionGenerator;
import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestCodeVisitor;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testparser.ParseResult;
import org.evosuite.testparser.StatementParser;
import org.evosuite.testparser.TypeResolver;
import org.evosuite.testparser.VariableScope;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM-based assertion generation strategy. Queries the LLM for assertions
 * per test case and parses them into real {@link Assertion} objects using
 * the {@link StatementParser} infrastructure. Assertions that cannot be
 * parsed into typed objects are wrapped as {@link CodeAssertion}.
 *
 * <p>Falls back to {@link CompleteAssertionGenerator} per test when the LLM
 * is unavailable, returns no assertions, or encounters errors.
 */
public class LlmAssertionGeneratorStrategy extends AssertionGenerator {

    private static final List<String> JUNIT_IMPORTS = Arrays.asList(
            "import static org.junit.Assert.*"
    );

    private final CompleteAssertionGenerator fallback = new CompleteAssertionGenerator();
    private final AtomicInteger assertionsAdded = new AtomicInteger();
    private final AtomicInteger fallbackCount = new AtomicInteger();

    @Override
    public void addAssertions(TestCase test) {
        if (!canUseLlm()) {
            fallbackForTest(test);
            return;
        }

        try {
            addLlmAssertions(test);
        } catch (Exception e) {
            logger.warn("LLM assertion generation failed for test, falling back", e);
            fallbackForTest(test);
        }
    }

    @Override
    public void addAssertions(org.evosuite.testsuite.TestSuiteChromosome suite) {
        super.addAssertions(suite);
        // Publish metrics after processing all tests
        ClientServices.track(RuntimeVariable.LLM_Assertions_Added, assertionsAdded.get());
        ClientServices.track(RuntimeVariable.LLM_Assertion_Fallbacks, fallbackCount.get());
    }

    private void addLlmAssertions(TestCase test) {
        // 1. Execute test to collect traces (for context in the prompt)
        ExecutionResult result = runTest(test);

        // 2. Render test to code, capturing variable name mappings
        TestCodeVisitor visitor = new TestCodeVisitor();
        test.accept(visitor);
        String testCode = visitor.getCode();

        // 3. Build variable name → VariableReference mapping
        Map<String, VariableReference> varNameMap = buildVariableNameMap(test, visitor);

        // 4. Query LLM
        List<String> assertionStrings = queryLlm(testCode, result);
        if (assertionStrings.isEmpty()) {
            fallbackForTest(test);
            return;
        }

        // 5. Parse assertions using StatementParser + CodeAssertion fallback
        int added = parseAndAttachAssertions(test, assertionStrings, varNameMap);
        if (added == 0) {
            fallbackForTest(test);
            return;
        }
        assertionsAdded.addAndGet(added);

        // 6. Apply redundancy filters
        filterRedundantNonnullAssertions(test);
        filterRedundantChainedInspectorAssertions(test);
        filterRedundantIsEmptySizeAssertions(test);
        for (int i = 0; i < test.size(); i++) {
            filterInspectorPrimitiveDuplication(test.getStatement(i));
        }
    }

    /**
     * Build a map from rendered variable names to their VariableReferences.
     */
    private Map<String, VariableReference> buildVariableNameMap(TestCase test, TestCodeVisitor visitor) {
        Map<String, VariableReference> map = new LinkedHashMap<>();
        for (int i = 0; i < test.size(); i++) {
            VariableReference varRef = test.getStatement(i).getReturnValue();
            if (varRef != null) {
                String name = visitor.getVariableName(varRef);
                // Only register simple variable names (no dots, brackets, casts)
                if (name != null && !name.isEmpty() && name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                    map.put(name, varRef);
                }
            }
        }
        return map;
    }

    /**
     * Query the LLM for assertion strings for a single test.
     */
    private List<String> queryLlm(String testCode, ExecutionResult result) {
        LlmService llmService = LlmService.getInstance();
        if (!llmService.isAvailable() || !llmService.hasBudget()) {
            return Collections.emptyList();
        }

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(
                "You are a test assertion assistant. Return only JUnit assertion statements, "
                + "one per line. Use standard JUnit assertions (assertEquals, assertTrue, "
                + "assertNotNull, etc). Prefer using variable names directly in assertions "
                + "rather than method calls on those variables."));

        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate meaningful assertions for this test of class ")
                .append(Properties.TARGET_CLASS).append(".\n\n");
        prompt.append("Test code:\n```java\n").append(testCode).append("\n```\n\n");

        if (result != null && !result.getAllThrownExceptions().isEmpty()) {
            Throwable firstException = result.getAllThrownExceptions().iterator().next();
            prompt.append("The test threw: ").append(firstException.getClass().getName()).append("\n");
        }

        prompt.append("\nReturn only assertion statements, one per line. "
                + "Use the exact variable names from the test code.");
        messages.add(LlmMessage.user(prompt.toString()));

        try {
            String response = llmService.query(messages, LlmFeature.ASSERTION_GENERATION);
            return LlmAssertionGenerator.parseAssertions(response);
        } catch (Exception e) {
            logger.debug("LLM query for assertions failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Parse assertion strings into Assertion objects and attach to the test.
     * Uses StatementParser for typed assertions, CodeAssertion for the rest.
     *
     * @return number of assertions successfully attached
     */
    int parseAndAttachAssertions(TestCase test, List<String> assertionStrings,
                                 Map<String, VariableReference> varNameMap) {
        if (!(test instanceof DefaultTestCase)) {
            return 0;
        }
        DefaultTestCase dtc = (DefaultTestCase) test;

        // Build VariableScope from the name map
        VariableScope scope = new VariableScope();
        for (Map.Entry<String, VariableReference> entry : varNameMap.entrySet()) {
            scope.register(entry.getKey(), entry.getValue());
        }

        TypeResolver typeResolver = new TypeResolver(
                getClass().getClassLoader(), JUNIT_IMPORTS);
        ParseResult parseResult = new ParseResult(dtc, "llm-assertions");
        StatementParser parser = new StatementParser(dtc, typeResolver, scope, parseResult);

        int attached = 0;
        int testSizeBefore = test.size();

        for (String assertionStr : assertionStrings) {
            if (isAssertionAlreadyPresent(test, assertionStr)) {
                continue;
            }
            int assertionsBefore = countAssertions(test);
            try {
                com.github.javaparser.ast.stmt.Statement ast =
                        StaticJavaParser.parseStatement(assertionStr);
                parser.parseStatement(ast);

                // Guard: rollback any statements added as a side effect
                while (test.size() > testSizeBefore) {
                    test.remove(test.size() - 1);
                }

                int assertionsAfter = countAssertions(test);
                if (assertionsAfter > assertionsBefore) {
                    attached += (assertionsAfter - assertionsBefore);
                    continue; // Successfully parsed into typed assertion
                }
            } catch (Exception e) {
                // Rollback any partial side effects
                while (test.size() > testSizeBefore) {
                    test.remove(test.size() - 1);
                }
                logger.debug("StatementParser could not parse assertion: {}", assertionStr);
            }

            // Fallback: wrap as CodeAssertion if it's a valid-looking assertion
            if (LlmAssertionGenerator.isSafeAssertion(assertionStr)) {
                attached += attachCodeAssertion(test, assertionStr, varNameMap);
            }
        }
        return attached;
    }

    private boolean isAssertionAlreadyPresent(TestCase test, String assertionStr) {
        String normalizedNew = assertionStr.trim();
        if (normalizedNew.endsWith(";")) {
            normalizedNew = normalizedNew.substring(0, normalizedNew.length() - 1);
        }

        for (Assertion existing : test.getAssertions()) {
            String existingCode = existing.getCode();
            if (existingCode == null) {
                continue;
            }
            String normalizedExisting = existingCode.trim();
            if (normalizedExisting.endsWith(";")) {
                normalizedExisting = normalizedExisting.substring(0, normalizedExisting.length() - 1);
            }
            if (normalizedNew.equals(normalizedExisting)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Attach a CodeAssertion to the most relevant statement.
     * Only attaches if at least one referenced identifier resolves to a known
     * test variable. Assertions with no resolvable variable references are
     * rejected to prevent invalid code in the output.
     */
    private int attachCodeAssertion(TestCase test, String assertionStr,
                                    Map<String, VariableReference> varNameMap) {
        if (test.size() == 0) {
            return 0;
        }

        // Find all referenced identifiers and ensure variable-like names resolve.
        // This prevents unresolved symbols from leaking into emitted test code.
        Set<String> referencedIdentifiers = extractReferencedIdentifiers(assertionStr);
        VariableReference bestRef = null;
        boolean hasAnyKnownVar = false;
        for (String identifier : referencedIdentifiers) {
            VariableReference ref = varNameMap.get(identifier);
            if (ref != null) {
                hasAnyKnownVar = true;
                if (bestRef == null || ref.getStPosition() > bestRef.getStPosition()) {
                    bestRef = ref;
                }
                continue;
            }

            // Allow unresolved type-like identifiers (e.g. Math, TimeUnit), but reject
            // unresolved variable-like identifiers that would fail compilation.
            if (!looksLikeTypeOrClassIdentifier(identifier)) {
                logger.debug("Rejecting CodeAssertion with unresolved identifier '{}': {}",
                        identifier, assertionStr);
                return 0;
            }
        }

        // Reject CodeAssertions that reference no known test variables
        if (!hasAnyKnownVar) {
            logger.debug("Rejecting CodeAssertion with no resolvable variables: {}", assertionStr);
            return 0;
        }

        int targetPos = bestRef.getStPosition();
        if (targetPos < 0 || targetPos >= test.size()) {
            targetPos = test.size() - 1;
        }

        Statement targetStmt = test.getStatement(targetPos);
        VariableReference sourceRef = targetStmt.getReturnValue();
        if (sourceRef == null) {
            return 0;
        }

        CodeAssertion codeAssertion = new CodeAssertion(assertionStr);
        codeAssertion.setSource(sourceRef);
        targetStmt.addAssertion(codeAssertion);
        return 1;
    }

    private Set<String> extractReferencedIdentifiers(String assertionStr) {
        try {
            com.github.javaparser.ast.stmt.Statement ast =
                    StaticJavaParser.parseStatement(assertionStr);
            Set<String> names = new LinkedHashSet<>();
            for (NameExpr nameExpr : ast.findAll(NameExpr.class)) {
                names.add(nameExpr.getNameAsString());
            }
            return names;
        } catch (Exception e) {
            logger.debug("Could not parse assertion to inspect identifiers: {}", assertionStr);
            return Collections.emptySet();
        }
    }

    private boolean looksLikeTypeOrClassIdentifier(String identifier) {
        return !identifier.isEmpty() && Character.isUpperCase(identifier.charAt(0));
    }

    private int countAssertions(TestCase test) {
        int count = 0;
        for (int i = 0; i < test.size(); i++) {
            count += test.getStatement(i).getAssertions().size();
        }
        return count;
    }

    private boolean canUseLlm() {
        LlmService llmService = LlmService.getInstance();
        return llmService.isAvailable() && llmService.hasBudget();
    }

    private void fallbackForTest(TestCase test) {
        fallbackCount.incrementAndGet();
        fallback.addAssertions(test);
    }

    /** Number of assertions added by LLM (typed + code assertions). */
    public int getAssertionsAdded() {
        return assertionsAdded.get();
    }

    /** Number of tests where LLM fell back to trace-based generation. */
    public int getFallbackCount() {
        return fallbackCount.get();
    }
}
