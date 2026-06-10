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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
