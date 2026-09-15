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

import org.evosuite.ga.Chromosome;
import org.evosuite.llm.search.RepeatedInjectionMemory;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testsuite.TestSuiteChromosome;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Seed-factory attempts whose test has been handed to the GA but not yet
 * evaluated. The factory must not execute the test itself (that would run it
 * outside the GA's evaluation lifecycle, with its archive and budget
 * accounting), so the attempt stays open until the GA evaluates the individual
 * and reports it here; the outcome is then read from the cached fitness values.
 *
 * <p>A test the GA discards before evaluating (orphaned, or never inserted)
 * would otherwise keep its target in flight forever, so entries older than
 * {@link #MAX_PENDING_GENERATIONS} are cleared without judgement.
 */
final class SeedAttemptLedger {

    static final long MAX_PENDING_GENERATIONS = 2L;

    private static final class Pending {
        final String attemptId;
        final TestFitnessFunction goal;
        final long generation;

        Pending(String attemptId, TestFitnessFunction goal, long generation) {
            this.attemptId = attemptId;
            this.goal = goal;
            this.generation = generation;
        }
    }

    private final RepeatedInjectionMemory memory;
    private final Map<TestChromosome, Pending> pending = new IdentityHashMap<>();

    SeedAttemptLedger(RepeatedInjectionMemory memory) {
        this.memory = memory;
    }

    /** Keeps {@code attemptId} open until {@code test} has been evaluated. */
    synchronized void open(TestChromosome test, String attemptId, TestFitnessFunction goal, long generation) {
        expire(generation);
        pending.put(test, new Pending(attemptId, goal, generation));
    }

    /**
     * Closes the attempt behind {@code individual} — a test, or a suite whose
     * tests are checked one by one — judging it by whether the target goal is
     * now covered. Individuals with no open attempt are ignored.
     */
    synchronized void evaluated(Chromosome<?> individual, long generation) {
        if (pending.isEmpty()) {
            return;
        }
        if (individual instanceof TestChromosome) {
            close((TestChromosome) individual);
        } else if (individual instanceof TestSuiteChromosome) {
            for (TestChromosome test : new ArrayList<>(((TestSuiteChromosome) individual).getTestChromosomes())) {
                close(test);
            }
        }
        expire(generation);
    }

    synchronized int openAttempts() {
        return pending.size();
    }

    private void close(TestChromosome test) {
        Pending entry = pending.remove(test);
        if (entry == null) {
            return;
        }
        Double distance = test.getFitnessValues().get(entry.goal);
        boolean covered = (distance != null && distance == 0.0)
                || test.getTestCase().getCoveredGoals().contains(entry.goal);
        memory.recordAttemptOutcome(entry.attemptId, covered ? 1 : 0);
    }

    private void expire(long generation) {
        Iterator<Map.Entry<TestChromosome, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Pending entry = it.next().getValue();
            if (generation - entry.generation > MAX_PENDING_GENERATIONS) {
                memory.clearAttempt(entry.attemptId);
                it.remove();
            }
        }
    }
}
