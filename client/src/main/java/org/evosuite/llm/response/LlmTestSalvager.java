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
import org.evosuite.assertion.Assertion;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestReferenceValidator;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testparser.ParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.evosuite.llm.response.RepairText.safeMethodName;

/**
 * Keeps what can be kept of LLM tests that the repair loop could not fix, and
 * collects the executable tests of a repair conversation without duplicates.
 * Salvage runs every candidate through the repair loop's test executor.
 */
final class LlmTestSalvager {

    // Salvage was part of the repair loop; keep its log category.
    private static final Logger logger = LoggerFactory.getLogger(TestRepairLoop.class);

    private final TestRepairLoop.TestExecutor testExecutor;
    private final boolean keepAssertions;
    private final ParsedTestExecutionCheck executionCheck;

    LlmTestSalvager(TestRepairLoop.TestExecutor testExecutor,
                    boolean keepAssertions,
                    ParsedTestExecutionCheck executionCheck) {
        this.testExecutor = testExecutor;
        this.keepAssertions = keepAssertions;
        this.executionCheck = executionCheck;
    }

    /**
     * Final-resort salvage once no further repair turn is possible. Under the
     * assertion-dropping policy every failed candidate is stripped of its
     * assertions and truncated at the failure site. Under the
     * assertion-preserving policy only candidates that failed at execution are
     * considered (they passed the compile gate), and only the assertions that
     * do not hold are removed; see {@link #performAssertionPreservingSalvage}.
     * Candidates whose method already survived as an executable test in a
     * later attempt are skipped, since the LLM repaired them.
     */
    void salvageFailedCandidates(List<ParseResult> droppedAtParse,
                                         Collection<ParseResult> droppedAtExecution,
                                         List<String> diagnostics,
                                         Map<String, ParseResult> salvagedExecutableTests,
                                         Consumer<List<ParseResult>> salvagedTestsConsumer,
                                         long repairDeadlineNanos) {
        List<ParseResult> candidates = new ArrayList<>();
        if (!keepAssertions && droppedAtParse != null) {
            candidates.addAll(droppedAtParse);
        }
        Set<String> executableMethodNames = new HashSet<>();
        for (ParseResult kept : salvagedExecutableTests.values()) {
            executableMethodNames.add(safeMethodName(kept));
        }
        for (ParseResult pr : droppedAtExecution) {
            if (!executableMethodNames.contains(safeMethodName(pr))) {
                candidates.add(pr);
            }
        }
        int salvagedCount = 0;
        for (ParseResult pr : candidates) {
            if (isSalvageDeadlineReached(repairDeadlineNanos)) {
                diagnostics.add("Stopped salvage early: repair deadline reached.");
                break;
            }
            Optional<TestCase> salvaged = keepAssertions
                    ? performAssertionPreservingSalvage(pr)
                    : performBruteForceSalvage(pr.getTestCase());
            if (!salvaged.isPresent()) {
                continue;
            }
            ParseResult salvagedResult = new ParseResult(salvaged.get(),
                                                         pr.getOriginalMethodName(),
                                                         pr.getDiagnostics());
            List<ParseResult> accepted = mergeSalvagedTests(salvagedExecutableTests,
                    Collections.singletonList(salvagedResult));
            salvagedCount += accepted.size();
            publishSalvagedTests(accepted, salvagedTestsConsumer);
        }
        if (!candidates.isEmpty()) {
            diagnostics.add("Salvaged " + salvagedCount + " of " + candidates.size()
                    + " failing candidate test(s)"
                    + (keepAssertions ? " by removing only failing assertions or truncating at the failure."
                                      : " by removing assertions or truncating at the failure."));
        }
    }

    List<ParseResult> mergeSalvagedTests(Map<String, ParseResult> salvaged, List<ParseResult> newResults) {
        List<ParseResult> accepted = new ArrayList<>();
        if (newResults == null || newResults.isEmpty()) {
            return accepted;
        }
        for (ParseResult parseResult : newResults) {
            if (parseResult == null) {
                continue;
            }
            if (hasOrphanedVariableReferences(parseResult.getTestCase(),
                    "LLM-parsed test '" + parseResult.getOriginalMethodName() + "'")) {
                continue;
            }
            String key = buildSalvageDedupKey(parseResult);
            if (!salvaged.containsKey(key)) {
                salvaged.put(key, parseResult);
                accepted.add(parseResult);
            }
        }
        return accepted;
    }

    void publishSalvagedTests(List<ParseResult> newlySalvaged,
                                      Consumer<List<ParseResult>> salvagedTestsConsumer) {
        if (salvagedTestsConsumer == null || newlySalvaged == null || newlySalvaged.isEmpty()) {
            return;
        }
        try {
            salvagedTestsConsumer.accept(Collections.unmodifiableList(new ArrayList<>(newlySalvaged)));
        } catch (RuntimeException e) {
            logger.debug("LLM salvage publication failed; continuing repair loop", e);
        }
    }

    String buildSalvageDedupKey(ParseResult parseResult) {
        String method = parseResult.getOriginalMethodName();
        String methodPart = (method == null || method.trim().isEmpty())
                ? "unknown"
                : method.trim();
        String codeFingerprint = fingerprintTestCode(parseResult.getTestCase());
        return methodPart + "|" + codeFingerprint;
    }

    private String fingerprintTestCode(TestCase testCase) {
        if (testCase == null) {
            return "null";
        }
        try {
            String normalized = normalizeForDedup(testCase.toCode());
            if (!normalized.isEmpty()) {
                return Integer.toHexString(normalized.hashCode());
            }
        } catch (Throwable ignored) {
            // Fall through to identity-based key if rendering fails.
        }
        return "id_" + System.identityHashCode(testCase);
    }

    private String normalizeForDedup(String source) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        return source.replaceAll("\\s+", " ").trim();
    }

    private static boolean isSalvageDeadlineReached(long repairDeadlineNanos) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        if (repairDeadlineNanos == TestRepairLoop.NO_REPAIR_DEADLINE || repairDeadlineNanos <= 0L) {
            return false;
        }
        return System.nanoTime() >= repairDeadlineNanos - TestRepairLoop.REPAIR_DEADLINE_RETURN_MARGIN_NANOS;
    }

    /**
     * Salvage that keeps the LLM's oracles wherever they hold. The candidate
     * is re-executed repeatedly:
     * <ul>
     *   <li>an assertion-only snippet that throws is removed;</li>
     *   <li>an undeclared exception at a direct SUT call keeps the test as a
     *       covering throw: everything after the call is removed, together
     *       with the call's own assertions;</li>
     *   <li>any other undeclared exception truncates the test before the
     *       failing statement;</li>
     *   <li>once nothing throws, only the attached assertions that do not
     *       hold are removed.</li>
     * </ul>
     */
    private Optional<TestCase> performAssertionPreservingSalvage(ParseResult failed) {
        TestCase original = failed == null ? null : failed.getTestCase();
        if (original == null || hasOrphanedVariableReferences(original,
                "salvage candidate test " + original.getID())) {
            return Optional.empty();
        }
        TestCase salvaged = original.clone();
        int maxRounds = salvaged.size() + 1;
        for (int round = 0; round < maxRounds && salvaged.size() > 0; round++) {
            ExecutionResult result;
            try {
                result = testExecutor.execute(salvaged);
            } catch (Throwable executionFailure) {
                logger.debug("Salvage execution failed for test {}", original.getID(), executionFailure);
                return Optional.empty();
            }
            if (result == null) {
                return Optional.empty();
            }
            Integer failIndex = firstUndeclaredExceptionPosition(failed, salvaged, result);
            if (failIndex == null) {
                // Assertions of statements that never completed cannot hold.
                int executedCount = ParsedTestExecutionCheck.executedStatementCount(salvaged, result);
                int removed = removeFailingAssertions(salvaged, result, executedCount);
                for (int i = executedCount; i < salvaged.size(); i++) {
                    removed += salvaged.getStatement(i).getAssertions().size();
                    salvaged.getStatement(i).removeAssertions();
                }
                return acceptAssertionPreservingSalvage(salvaged, original,
                        removed == 0 ? "unchanged" : "removing " + removed + " failing assertion(s)");
            }
            if (failIndex < 0 || failIndex >= salvaged.size()) {
                return Optional.empty();
            }
            org.evosuite.testcase.statements.Statement failStmt = salvaged.getStatement(failIndex);
            if (failStmt instanceof org.evosuite.testcase.statements.UninterpretedStatement
                    && LlmAssertionSanitizer.isAssertionOnlySnippet(
                            ((org.evosuite.testcase.statements.UninterpretedStatement) failStmt)
                                    .getSourceCode())) {
                salvaged.remove(failIndex);
                continue;
            }
            if (isSutCallStatement(failStmt)) {
                if (salvaged.size() > failIndex + 1) {
                    salvaged.chop(failIndex + 1);
                }
                failStmt.removeAssertions();
                removeFailingAssertions(salvaged, result, failIndex);
                return acceptAssertionPreservingSalvage(salvaged, original,
                        "keeping a covering throw at SUT call (index " + failIndex + ")");
            }
            if (failIndex == 0) {
                break;
            }
            salvaged.chop(failIndex);
        }
        logger.warn("Failed to salvage test case: {}", original.getID());
        return Optional.empty();
    }

    private Optional<TestCase> acceptAssertionPreservingSalvage(TestCase salvaged,
                                                                TestCase original,
                                                                String how) {
        if (!isMeaningful(salvaged)) {
            logger.warn("Discarded salvaged test {} as it is not meaningful.", original.getID());
            return Optional.empty();
        }
        if (hasUnsafelyCopyableStatements(salvaged, "salvaged test " + original.getID())) {
            return Optional.empty();
        }
        logger.warn("Salvaged test {} with assertions preserved ({}).", original.getID(), how);
        return Optional.of(salvaged);
    }

    /**
     * Position of the first exception that {@link ParsedTestExecutionCheck#checkExecution} would treat
     * as a failure, or null when the execution is clean.
     */
    private Integer firstUndeclaredExceptionPosition(ParseResult origin,
                                                     TestCase testCase,
                                                     ExecutionResult result) {
        List<Integer> positions = new ArrayList<>(result.getPositionsWhereExceptionsWereThrown());
        Collections.sort(positions);
        for (Integer position : positions) {
            Throwable thrown = result.getExceptionThrownAtPosition(position);
            if (executionCheck.countsAsExecutionFailure(testCase, origin.getExpectedExceptionClass(),
                    position, thrown)) {
                return position;
            }
        }
        return null;
    }

    /**
     * Removes the attached assertions of statements before {@code limit} that
     * do not hold (or cannot be evaluated) on the final scope of
     * {@code result}. Returns the number of removed assertions.
     */
    private int removeFailingAssertions(TestCase testCase, ExecutionResult result, int limit) {
        Scope finalScope = result.getFinalScope();
        if (finalScope == null) {
            return 0;
        }
        int removed = 0;
        for (int i = 0; i < Math.min(limit, testCase.size()); i++) {
            org.evosuite.testcase.statements.Statement stmt = testCase.getStatement(i);
            if (stmt == null || stmt.getAssertions() == null || stmt.getAssertions().isEmpty()) {
                continue;
            }
            for (Assertion assertion : new ArrayList<>(stmt.getAssertions())) {
                boolean holds;
                try {
                    holds = assertion != null && assertion.evaluate(finalScope);
                } catch (Throwable evaluationFailure) {
                    holds = false;
                }
                if (!holds) {
                    stmt.removeAssertion(assertion);
                    removed++;
                }
            }
        }
        return removed;
    }

    Optional<TestCase> performBruteForceSalvage(TestCase failedTestCase) {
        logger.warn("Attempting brute-force salvage for test case: {}", failedTestCase.getID());
        if (hasOrphanedVariableReferences(failedTestCase,
                "salvage candidate test " + failedTestCase.getID())) {
            return Optional.empty();
        }
        TestCase salvaged = failedTestCase.clone();

        // 1. Initial attempt: remove assertions and assertion-only raw snippets.
        LlmAssertionSanitizer.sanitize(salvaged);

        // The injected executor holds the execution lock, applies the mock and GUI
        // settings and resets static fields when configured, as the earlier checks did.
        ExecutionResult result = executeForSalvage(salvaged, failedTestCase);
        if (result == null) {
            return Optional.empty();
        }

        if (result.noThrownExceptions()) {
            if (isMeaningful(salvaged)) {
                if (hasUnsafelyCopyableStatements(salvaged,
                        "salvaged-no-assertions test " + failedTestCase.getID())) {
                    return Optional.empty();
                }
                logger.warn("Successfully salvaged test {} by removing assertions.", failedTestCase.getID());
                return Optional.of(salvaged);
            }
            logger.warn("Discarded salvaged test {} as it is not meaningful.", failedTestCase.getID());
            return Optional.empty();
        }

        // 1b. If the throwing statement IS the SUT's meaningful entry call,
        // chopping it would remove the only line worth keeping. Instead, drop
        // everything AFTER the failing call and keep the test as a "covering
        // throw" — the chromosome will exercise the SUT up to the throw point,
        // and the test writer wraps it in a try/fail block at render time.
        Optional<TestCase> coveringThrow = trySalvageAsCoveringSutThrow(salvaged, result, failedTestCase);
        if (coveringThrow.isPresent()) {
            return coveringThrow;
        }

        // 2. Iterative truncation at failure site
        while (!result.noThrownExceptions()) {
            Map<Integer, Throwable> exceptionMapping = result.getCopyOfExceptionMapping();
            Integer failIndex = exceptionMapping.keySet().stream().min(Integer::compareTo).orElse(-1);
            if (failIndex == null || failIndex <= 0) {
                break;
            }

            salvaged.chop(failIndex);
            LlmAssertionSanitizer.sanitize(salvaged);

            result = executeForSalvage(salvaged, failedTestCase);
            if (result == null) {
                return Optional.empty();
            }

            if (result.noThrownExceptions()) {
                if (isMeaningful(salvaged)) {
                    if (hasUnsafelyCopyableStatements(salvaged,
                            "salvaged-truncated test " + failedTestCase.getID()
                                    + " (chop@" + failIndex + ")")) {
                        return Optional.empty();
                    }
                    logger.warn("Successfully salvaged test {} by truncation at index {}.",
                            failedTestCase.getID(), failIndex);
                    return Optional.of(salvaged);
                }
                logger.warn("Discarded salvaged test {} after truncation as it is not meaningful.",
                        failedTestCase.getID());
                return Optional.empty();
            }
        }

        logger.warn("Failed to salvage test case: {}", failedTestCase.getID());
        return Optional.empty();
    }

    private ExecutionResult executeForSalvage(TestCase candidate, TestCase failedTestCase) {
        try {
            return testExecutor.execute(candidate);
        } catch (Throwable executionFailure) {
            logger.debug("Salvage execution failed for test {}", failedTestCase.getID(), executionFailure);
            return null;
        }
    }

    /**
     * If the failing index of <code>salvaged</code> points at a statement that
     * invokes the SUT directly (a method call on the target class, or a
     * constructor of the target class), keep the test as a covering-throw
     * chromosome. The throwing statement stays; everything after it is
     * removed. The test still throws when re-run, but it now genuinely
     * exercises the SUT and contributes coverage up to the throw point.
     */
    private Optional<TestCase> trySalvageAsCoveringSutThrow(TestCase salvaged,
                                                            ExecutionResult result,
                                                            TestCase failedTestCase) {
        if (salvaged == null || result == null || result.noThrownExceptions()) {
            return Optional.empty();
        }
        Map<Integer, Throwable> exceptionMapping = result.getCopyOfExceptionMapping();
        Integer failIndex = exceptionMapping.keySet().stream().min(Integer::compareTo).orElse(-1);
        if (failIndex == null || failIndex < 0 || failIndex >= salvaged.size()) {
            return Optional.empty();
        }
        org.evosuite.testcase.statements.Statement failStmt = salvaged.getStatement(failIndex);
        if (!isSutCallStatement(failStmt)) {
            return Optional.empty();
        }
        // Trim everything after the failing statement (keep [0..failIndex]).
        if (salvaged.size() > failIndex + 1) {
            salvaged.chop(failIndex + 1);
        }
        // Strip any assertions that may now be dangling.
        LlmAssertionSanitizer.sanitize(salvaged);
        if (!isMeaningful(salvaged)) {
            return Optional.empty();
        }
        if (hasUnsafelyCopyableStatements(salvaged,
                "covering-throw salvage test " + failedTestCase.getID()
                        + " (chop@" + (failIndex + 1) + ")")) {
            return Optional.empty();
        }
        Throwable thrown = exceptionMapping.get(failIndex);
        String thrownName = thrown == null ? "<unknown>" : thrown.getClass().getName();
        logger.warn("Salvaged test {} as covering-throw at SUT call (index {}, exception {}).",
                failedTestCase.getID(), failIndex, thrownName);
        return Optional.of(salvaged);
    }

    /**
     * True when the statement is a direct invocation of the SUT (a method
     * declared by the target class, or a constructor of the target class).
     * Used by the salvage path to recognize the only line worth keeping when
     * the LLM-generated test throws at the SUT entry point.
     */
    private boolean isSutCallStatement(org.evosuite.testcase.statements.Statement statement) {
        if (statement == null || Properties.TARGET_CLASS == null) {
            return false;
        }
        if (statement instanceof org.evosuite.testcase.statements.MethodStatement) {
            org.evosuite.testcase.statements.MethodStatement ms =
                    (org.evosuite.testcase.statements.MethodStatement) statement;
            Class<?> declaring = ms.getMethod() == null ? null
                    : ms.getMethod().getDeclaringClass();
            return declaring != null
                    && Properties.TARGET_CLASS.equals(declaring.getName());
        }
        if (statement instanceof org.evosuite.testcase.statements.ConstructorStatement) {
            org.evosuite.testcase.statements.ConstructorStatement cs =
                    (org.evosuite.testcase.statements.ConstructorStatement) statement;
            if (cs.getReturnValue() == null
                    || cs.getReturnValue().getVariableClass() == null) {
                return false;
            }
            return Properties.TARGET_CLASS.equals(
                    cs.getReturnValue().getVariableClass().getName());
        }
        return false;
    }

    private boolean isMeaningful(TestCase testCase) {
        return TestInteractionChecker.invokesTarget(testCase, Properties.TARGET_CLASS);
    }

    /**
     * Reject test cases whose clone() would throw because some
     * {@link org.evosuite.testcase.variable.VariableReference} no longer resolves
     * to a defining statement. Logs at WARN so the next occurrence is
     * traceable to its parse path, then returns true to signal the caller to
     * drop the test. Returns false (silently) on a well-formed test case.
     */
    private boolean hasOrphanedVariableReferences(TestCase testCase, String context) {
        if (testCase == null) {
            return false;
        }
        List<String> orphans;
        try {
            orphans = TestReferenceValidator.findOrphanedVariableReferences(testCase);
        } catch (Throwable t) {
            logger.warn("Orphan-reference validation crashed for {}; dropping test ({})",
                    context, t.toString());
            return true;
        }
        if (orphans.isEmpty()) {
            return false;
        }
        logger.warn("Dropping {} due to {} orphaned variable reference(s); details: {}",
                context, orphans.size(), orphans);
        return true;
    }

    /**
     * Reject test cases that contain {@code AssignmentStatement}s whose
     * positional copy would crash with "wrong position N, total N" — the
     * symptom is invisible to {@link #hasOrphanedVariableReferences} because
     * the AssignmentStatement is itself the only matching definition for its
     * retval. ObjectPool sequence insertion and certain crossover-driven
     * paths trigger {@code Statement.copy(newTestCase, offset)} and would
     * kill the master process; dropping the salvaged test here keeps the
     * search alive.
     */
    private boolean hasUnsafelyCopyableStatements(TestCase testCase, String context) {
        if (testCase == null) {
            return false;
        }
        List<String> issues;
        try {
            issues = TestReferenceValidator.findUnsafelyCopyableStatements(testCase);
        } catch (Throwable t) {
            logger.warn("Copy-safety validation crashed for {}; dropping test ({})",
                    context, t.toString());
            return true;
        }
        if (issues.isEmpty()) {
            return false;
        }
        logger.warn("Dropping {} due to {} unsafe-to-copy statement(s); details: {}",
                context, issues.size(), issues);
        return true;
    }
}
