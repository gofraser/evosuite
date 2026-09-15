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
package org.evosuite.llm.factory;

import org.evosuite.llm.search.RepeatedInjectionMemory;
import org.evosuite.llm.search.RepeatedInjectionTarget;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class SeedAttemptLedgerTest {

    private static TestChromosome test() {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        return tc;
    }

    private static String register(RepeatedInjectionMemory memory) {
        return memory.registerAttempt(Collections.singletonList(
                new RepeatedInjectionTarget("operator|g", "operator|g|parent=x")), true).getAttemptId();
    }

    @Test
    void coveredGoalCountsAsGainWhenTheTestIsEvaluated() {
        RepeatedInjectionMemory memory = spy(new RepeatedInjectionMemory());
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        SeedAttemptLedger ledger = new SeedAttemptLedger(memory);
        TestChromosome seeded = test();
        String id = register(memory);

        ledger.open(seeded, id, goal, 3L);
        seeded.setFitness(goal, 0.0);
        ledger.evaluated(seeded, 3L);

        verify(memory).recordAttemptOutcome(id, 1);
        assertEquals(0, ledger.openAttempts());
    }

    @Test
    void uncoveredGoalCountsAsNoGain() {
        RepeatedInjectionMemory memory = spy(new RepeatedInjectionMemory());
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        SeedAttemptLedger ledger = new SeedAttemptLedger(memory);
        TestChromosome seeded = test();
        String id = register(memory);

        ledger.open(seeded, id, goal, 3L);
        seeded.setFitness(goal, 0.5);
        ledger.evaluated(seeded, 3L);

        verify(memory).recordAttemptOutcome(id, 0);
    }

    @Test
    void suiteEvaluationClosesTheAttemptsOfItsTests() {
        RepeatedInjectionMemory memory = spy(new RepeatedInjectionMemory());
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        SeedAttemptLedger ledger = new SeedAttemptLedger(memory);
        TestChromosome seeded = test();
        String id = register(memory);
        ledger.open(seeded, id, goal, 1L);
        TestSuiteChromosome suite = new TestSuiteChromosome();
        suite.addTest(seeded);

        ledger.evaluated(suite, 1L);

        verify(memory).recordAttemptOutcome(eq(id), anyInt());
    }

    @Test
    void attemptsOfTestsNeverEvaluatedAreClearedWithoutJudgement() {
        RepeatedInjectionMemory memory = spy(new RepeatedInjectionMemory());
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        SeedAttemptLedger ledger = new SeedAttemptLedger(memory);
        String stale = register(memory);
        ledger.open(test(), stale, goal, 1L);

        ledger.evaluated(test(), 1L + SeedAttemptLedger.MAX_PENDING_GENERATIONS + 1L);

        verify(memory).clearAttempt(stale);
        verify(memory, never()).recordAttemptOutcome(any(String.class), anyInt());
        assertEquals(0, ledger.openAttempts());
    }
}
