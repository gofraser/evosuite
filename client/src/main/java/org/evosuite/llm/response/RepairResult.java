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

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testparser.ParseResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of parse/repair/execute pipeline.
 */
public class RepairResult {

    private final boolean success;
    private final List<ParseResult> parseResults;
    private final List<String> diagnostics;
    private final int attemptsUsed;
    private final List<String> expandedClasses;
    private final boolean needsClusterExpansion;

    private RepairResult(boolean success,
                         List<ParseResult> parseResults,
                         List<String> diagnostics,
                         int attemptsUsed,
                         List<String> expandedClasses,
                         boolean needsClusterExpansion) {
        this.success = success;
        this.parseResults = parseResults;
        this.diagnostics = diagnostics;
        this.attemptsUsed = attemptsUsed;
        this.expandedClasses = expandedClasses;
        this.needsClusterExpansion = needsClusterExpansion;
    }

    /** Creates a successful repair result with the given parse results, diagnostics, and expanded classes. */
    public static RepairResult success(List<ParseResult> parseResults,
                                       List<String> diagnostics,
                                       int attemptsUsed,
                                       List<String> expandedClasses) {
        return new RepairResult(true,
                Collections.unmodifiableList(new ArrayList<>(parseResults)),
                Collections.unmodifiableList(new ArrayList<>(diagnostics)),
                attemptsUsed,
                Collections.unmodifiableList(new ArrayList<>(expandedClasses)),
                false);
    }

    /** Creates a failure result with diagnostics, attempt count, and any expanded classes. */
    public static RepairResult failure(List<String> diagnostics,
                                       int attemptsUsed,
                                       List<String> expandedClasses) {
        return new RepairResult(false,
                Collections.<ParseResult>emptyList(),
                Collections.unmodifiableList(new ArrayList<>(diagnostics)),
                attemptsUsed,
                Collections.unmodifiableList(new ArrayList<>(expandedClasses)),
                false);
    }

    /**
     * Creates a failure result for a loop that stopped because parsing hit
     * unresolved symbols but its {@link TestRepairLoop.RepairOptions} forbid
     * expanding the test cluster itself (a background worker thread, which
     * must not mutate state the search thread reads concurrently). The
     * caller is expected to expand the cluster and re-parse the same
     * response on the search thread instead.
     */
    public static RepairResult needsClusterExpansion(List<String> diagnostics,
                                                      int attemptsUsed,
                                                      List<String> expandedClasses) {
        return new RepairResult(false,
                Collections.<ParseResult>emptyList(),
                Collections.unmodifiableList(new ArrayList<>(diagnostics)),
                attemptsUsed,
                Collections.unmodifiableList(new ArrayList<>(expandedClasses)),
                true);
    }

    public boolean isSuccess() {
        return success;
    }

    /**
     * Whether this (failed) result stopped short because expanding the test
     * cluster could resolve it, but the loop that produced it was configured
     * not to perform expansion itself.
     */
    public boolean needsClusterExpansion() {
        return needsClusterExpansion;
    }

    public List<ParseResult> getParseResults() {
        return parseResults;
    }

    public List<String> getDiagnostics() {
        return diagnostics;
    }

    public int getAttemptsUsed() {
        return attemptsUsed;
    }

    public List<String> getExpandedClasses() {
        return expandedClasses;
    }

    /** Returns all successfully parsed test cases contained in this result.
     *  Skips any parse result whose test case is {@code null}; such entries
     *  would otherwise propagate as TestChromosomes with a null {@code test}
     *  field and crash later code paths (e.g. statistics export) with NPEs. */
    public List<TestCase> getTestCases() {
        List<TestCase> tests = new ArrayList<>();
        for (ParseResult parseResult : parseResults) {
            TestCase testCase = parseResult.getTestCase();
            if (testCase == null) {
                continue;
            }
            tests.add(testCase);
        }
        return tests;
    }

    /**
     * Converts parsed test cases into {@link TestChromosome} instances.
     *
     * @return list of chromosomes wrapping the test cases
     */
    public List<TestChromosome> toChromosomes() {
        List<TestChromosome> chromosomes = new ArrayList<>();
        for (TestCase testCase : getTestCases()) {
            TestChromosome chromosome = new TestChromosome();
            chromosome.setTestCase(testCase);
            chromosomes.add(chromosome);
        }
        return chromosomes;
    }

    /**
     * Converts up to {@code maxCount} parsed test cases into {@link TestChromosome} instances.
     *
     * @param maxCount maximum number of chromosomes to return
     * @return list of chromosomes (size ≤ maxCount)
     */
    public List<TestChromosome> toChromosomes(int maxCount) {
        List<TestCase> testCases = getTestCases();
        int limit = Math.max(0, Math.min(maxCount, testCases.size()));
        List<TestChromosome> chromosomes = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            TestChromosome chromosome = new TestChromosome();
            chromosome.setTestCase(testCases.get(i));
            chromosomes.add(chromosome);
        }
        return chromosomes;
    }
}
