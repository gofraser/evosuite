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
package org.evosuite.statistics;

import org.evosuite.Properties;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTraceImpl;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatisticsSenderTest {

    private boolean originalNewStatistics;
    private int originalMinFreeMem;

    @BeforeEach
    void saveProperties() {
        originalNewStatistics = Properties.NEW_STATISTICS;
        originalMinFreeMem = Properties.MIN_FREE_MEM;
    }

    @AfterEach
    void restoreProperties() {
        Properties.NEW_STATISTICS = originalNewStatistics;
        Properties.MIN_FREE_MEM = originalMinFreeMem;
    }

    // --- helpers ---

    private static TestChromosome makeChromosomeWithResult() {
        TestChromosome chromosome = new TestChromosome();
        TestCase tc = new DefaultTestCase();
        PrimitiveStatement<?> stmt = PrimitiveStatement.getPrimitiveStatement(tc, int.class);
        tc.addStatement(stmt);
        chromosome.setTestCase(tc);
        ExecutionResult result = new ExecutionResult(tc);
        result.setTrace(new ExecutionTraceImpl());
        chromosome.setLastExecutionResult(result);
        return chromosome;
    }

    private static TestChromosome makeChromosomeWithoutResult() {
        TestChromosome chromosome = new TestChromosome();
        TestCase tc = new DefaultTestCase();
        PrimitiveStatement<?> stmt = PrimitiveStatement.getPrimitiveStatement(tc, int.class);
        tc.addStatement(stmt);
        chromosome.setTestCase(tc);
        // intentionally no setLastExecutionResult
        return chromosome;
    }

    // --- collectExecutionResults: drops null exec results ---

    @Test
    void collectExecutionResultsKeepsOnlyNonNull() {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(makeChromosomeWithResult());
        suite.addTest(makeChromosomeWithoutResult());
        suite.addTest(makeChromosomeWithResult());

        List<ExecutionResult> results = StatisticsSender.collectExecutionResults(suite);

        assertEquals(2, results.size(), "should drop the single null exec result");
        for (ExecutionResult r : results) {
            assertFalse(r == null, "null exec results must not appear");
        }
    }

    @Test
    void collectExecutionResultsEmptySuiteYieldsEmptyList() {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        assertTrue(StatisticsSender.collectExecutionResults(suite).isEmpty());
    }

    @Test
    void collectExecutionResultsAllNullYieldsEmptyList() {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(makeChromosomeWithoutResult());
        suite.addTest(makeChromosomeWithoutResult());
        assertTrue(StatisticsSender.collectExecutionResults(suite).isEmpty());
    }

    // --- executedAndThenSendIndividualToMaster ---

    @Test
    void executedAndThenSendIndividualToMasterIsNoopWhenNewStatisticsOff() {
        Properties.NEW_STATISTICS = false;
        TestSuiteChromosome suite = new TestSuiteChromosome();
        // Include a test with no cached result — would normally trigger re-execution,
        // which would fail in this unit-test environment. The NEW_STATISTICS guard
        // must short-circuit before we reach that path.
        suite.addTest(makeChromosomeWithoutResult());

        assertDoesNotThrow(() -> StatisticsSender.executedAndThenSendIndividualToMaster(suite));
    }

    // --- isMemoryTooLowForReExecution ---

    @Test
    void isMemoryBelowThresholdReturnsTrueWhenThresholdExceedsMaxHeap() {
        long threshold = Runtime.getRuntime().maxMemory() + 1L;
        assertTrue(StatisticsSender.isMemoryBelowThreshold(threshold),
                "Threshold above maxMemory must always classify memory as too low");
    }

    @Test
    void isMemoryBelowThresholdReturnsFalseAtZero() {
        assertFalse(StatisticsSender.isMemoryBelowThreshold(0L),
                "Zero threshold must never trip the guard");
    }

    @Test
    void isMemoryTooLowForReExecutionRespectsMinFreeMem() {
        Properties.MIN_FREE_MEM = 0;
        assertFalse(StatisticsSender.isMemoryTooLowForReExecution(),
                "MIN_FREE_MEM=0 must never trip the guard");
    }

    // --- lightweightCopy: what the master receives ---

    private static class GoalFitness extends FitnessFunction<TestChromosome> {
        private final int id;

        GoalFitness(int id) {
            this.id = id;
        }

        @Override
        public double getFitness(TestChromosome individual) {
            return 0.0;
        }

        @Override
        public boolean isMaximizationFunction() {
            return false;
        }
    }

    private static class SuiteFitness extends FitnessFunction<TestSuiteChromosome> {
        @Override
        public double getFitness(TestSuiteChromosome individual) {
            return 0.0;
        }

        @Override
        public boolean isMaximizationFunction() {
            return false;
        }
    }

    private static TestSuiteChromosome suiteWithPerGoalFitness(SuiteFitness suiteFitness, int tests, int goals) {
        List<GoalFitness> goalFunctions = new java.util.ArrayList<>();
        for (int g = 0; g < goals; g++) {
            goalFunctions.add(new GoalFitness(g));
        }
        TestSuiteChromosome suite = new TestSuiteChromosome();
        for (int t = 0; t < tests; t++) {
            TestChromosome test = makeChromosomeWithoutResult();
            for (GoalFitness goal : goalFunctions) {
                test.addFitness(goal, 1.0, 0.0);
            }
            suite.addTest(test);
        }
        suite.addFitness(suiteFitness, 3.0, 0.5);
        return suite;
    }

    private static int serializedSize(Object o) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(o);
        }
        return bytes.size();
    }

    @Test
    void lightweightCopyKeepsWhatTheMasterReadsAndDropsPerTestFitness() throws Exception {
        SuiteFitness suiteFitness = new SuiteFitness();
        TestSuiteChromosome suite = suiteWithPerGoalFitness(suiteFitness, 20, 200);

        TestSuiteChromosome copy = StatisticsSender.lightweightCopy(suite);

        assertNotSame(suite, copy);
        assertEquals(suite.size(), copy.size());
        assertEquals(suite.totalLengthOfTestCases(), copy.totalLengthOfTestCases());
        assertEquals(suite.getFitness(), copy.getFitness());
        assertEquals(suite.getCoverage(), copy.getCoverage());
        assertEquals(suite.getFitness(suiteFitness), copy.getFitness(suiteFitness));
        for (TestChromosome test : copy.getTestChromosomes()) {
            assertTrue(test.getFitnessValues().isEmpty(), "per-goal fitness values must not be sent");
        }
        assertFalse(suite.getTestChromosomes().get(0).getFitnessValues().isEmpty(),
                "the live individual must keep its fitness values");
        assertNotSame(suite.getTestChromosomes().get(0).getTestCase(),
                copy.getTestChromosomes().get(0).getTestCase());
        assertTrue(serializedSize(copy) * 10 < serializedSize(suite),
                "the copy should be far smaller than the individual it stands for");
    }

    @Test
    void lightweightCopyReturnsOtherChromosomesAsTheyAre() {
        TestChromosome test = makeChromosomeWithoutResult();
        assertSame(test, StatisticsSender.lightweightCopy(test));
    }
}
