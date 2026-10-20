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
package org.evosuite;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Assertion-free clones of a replayed suite's tests, taken before any oracle phase runs, and the
 * means to put them back afterwards with only the retained assertions transferred.
 */
final class ReplayStructureSnapshot {
    private static final Logger logger = LoggerFactory.getLogger(ReplayStructureSnapshot.class);

    private final List<TestCase> structuralTests;
    private final List<TestCase> assertionSources;
    private final List<List<Statement>> statementSources;
    private final List<TestCase> fallbackAssertionSources;

    private ReplayStructureSnapshot(List<TestCase> structuralTests,
                                    List<TestCase> assertionSources,
                                    List<List<Statement>> statementSources,
                                    List<TestCase> fallbackAssertionSources) {
        this.structuralTests = structuralTests;
        this.assertionSources = assertionSources;
        this.statementSources = statementSources;
        this.fallbackAssertionSources = fallbackAssertionSources;
    }

    static ReplayStructureSnapshot capture(TestSuiteChromosome suite) {
        return capture(suite, false);
    }

    static ReplayStructureSnapshot captureWithFallbackAssertions(TestSuiteChromosome suite) {
        return capture(suite, true);
    }

    private static ReplayStructureSnapshot capture(TestSuiteChromosome suite,
                                                   boolean retainFallbackAssertions) {
        List<TestCase> assertionSources = suite.getTests();
        List<TestCase> structuralTests = assertionSources.stream()
                .map(TestCase::clone)
                .collect(java.util.stream.Collectors.toList());
        List<TestCase> fallbackAssertionSources = retainFallbackAssertions
                ? assertionSources.stream()
                .map(TestCase::clone)
                .collect(java.util.stream.Collectors.toList())
                : null;
        List<List<Statement>> statementSources = assertionSources.stream()
                .map(test -> {
                    List<Statement> statements = new ArrayList<>(test.size());
                    test.forEach(statements::add);
                    return statements;
                })
                .collect(java.util.stream.Collectors.toList());
        structuralTests.forEach(TestCase::removeAssertions);
        return new ReplayStructureSnapshot(
                structuralTests, assertionSources, statementSources, fallbackAssertionSources);
    }

    boolean hasSameExecutableStructure(TestSuiteChromosome suite) {
        List<TestCase> currentTests = suite.getTests();
        if (currentTests.size() != assertionSources.size()) {
            return false;
        }
        for (int testIndex = 0; testIndex < currentTests.size(); testIndex++) {
            TestCase current = currentTests.get(testIndex);
            if (current != assertionSources.get(testIndex)
                    || current.size() != statementSources.get(testIndex).size()) {
                return false;
            }
            for (int statementIndex = 0; statementIndex < current.size(); statementIndex++) {
                if (current.getStatement(statementIndex)
                        != statementSources.get(testIndex).get(statementIndex)) {
                    return false;
                }
            }
        }
        return true;
    }

    int totalStructuralStatements() {
        return structuralTests.stream().mapToInt(TestCase::size).sum();
    }

    int structuralTestCount() {
        return structuralTests.size();
    }

    /** Replaces the suite's tests with the structural clones plus the assertions that survived. */
    void restore(TestSuiteChromosome suite) {
        Set<TestCase> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        retained.addAll(suite.getTests());
        List<TestCase> restored = new ArrayList<>(structuralTests.size());
        for (int index = 0; index < structuralTests.size(); index++) {
            TestCase structural = structuralTests.get(index);
            TestCase assertionSource = assertionSources.get(index);
            TestCase fallbackSource = fallbackAssertionSources == null
                    ? null : fallbackAssertionSources.get(index);
            boolean useProcessedAssertions = retained.contains(assertionSource)
                    && assertionSource.size() == structural.size();
            TestCase retainedAssertionSource = useProcessedAssertions
                    ? assertionSource : fallbackSource;
            rebindRestoredReplayTest(structural,
                    retainedAssertionSource == null ? assertionSource : retainedAssertionSource);
            if (useProcessedAssertions) {
                structural.addAssertions(assertionSource);
                structural.setUnstable(structural.isUnstable() || assertionSource.isUnstable());
            } else if (fallbackSource != null && fallbackSource.size() == structural.size()) {
                structural.addAssertions(fallbackSource);
                structural.setUnstable(structural.isUnstable() || fallbackSource.isUnstable());
                logger.info("Restoring replay test {} with its baseline mutation assertions after "
                        + "hybrid oracle validation rejected the LLM-processed oracle", index);
            } else if (retained.contains(assertionSource)) {
                logger.warn("Dropping replay assertions for test {} because oracle processing "
                                + "changed its statement count from {} to {}",
                        index, structural.size(), assertionSource.size());
            } else {
                logger.info("Restoring replay test {} without assertions after JUnit validation "
                        + "rejected its oracle", index);
            }
            restored.add(structural);
        }
        suite.clearTests();
        restored.forEach(suite::addTest);
    }

    /**
     * Assertion generation may replace the SUT classloader used by every
     * reflective statement in the live suite. The structural snapshots are
     * deliberately captured before that happens, so restoring them without
     * rebinding leaves a mixture of old-loader statements and new-loader
     * assertions. The JUnit writer then observes {@link
     * org.evosuite.testcase.execution.CodeUnderTestException}s and chops the
     * affected tests while rendering them.
     */
    private static void rebindRestoredReplayTest(TestCase structural,
                                                 TestCase assertionSource) {
        if (!(structural instanceof DefaultTestCase)
                || !(assertionSource instanceof DefaultTestCase)) {
            return;
        }
        ClassLoader assertionLoader =
                ((DefaultTestCase) assertionSource).getChangedClassLoader();
        if (assertionLoader != null) {
            ((DefaultTestCase) structural).changeClassLoader(assertionLoader);
        }
    }
}
