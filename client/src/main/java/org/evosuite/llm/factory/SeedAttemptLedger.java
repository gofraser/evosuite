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
 * and reports it here; the outcome is then read from the evidence the GA left
 * behind on the test.
 *
 * <p>Two evaluation paths feed {@link #evaluated}, and they leave different
 * evidence:
 * <ul>
 * <li>Test-level GAs (MOSA/DynaMOSA) compute every current goal's fitness on
 * the test in one pass and notify listeners once afterwards, so the target
 * goal's fitness value is on the test by the time this ledger is told; a
 * value of {@code 0.0} is a direct verdict.</li>
 * <li>Suite-level GAs notify listeners once per criterion, and a suite
 * fitness function does not write per-goal fitness values onto its member
 * tests — the only trace a criterion leaves on a test is an entry in its
 * covered goals once that criterion has run. So a suite notification that
 * finds neither a fitness value nor a covered-goal entry is not evidence of
 * "not covered", only evidence that the target goal's criterion has not run
 * yet; the attempt must stay open until either the goal turns up covered, or
 * a later generation proves every criterion of the opening generation has
 * already run without covering it.</li>
 * </ul>
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
     * tests are checked one by one — if the notification carries evidence
     * about the target goal; otherwise the attempt is left pending for a
     * later notification. Individuals with no open attempt are ignored.
     */
    synchronized void evaluated(Chromosome<?> individual, long generation) {
        if (pending.isEmpty()) {
            return;
        }
        if (individual instanceof TestChromosome) {
            close((TestChromosome) individual, generation);
        } else if (individual instanceof TestSuiteChromosome) {
            for (TestChromosome test : new ArrayList<>(((TestSuiteChromosome) individual).getTestChromosomes())) {
                close(test, generation);
            }
        }
        expire(generation);
    }

    synchronized int openAttempts() {
        return pending.size();
    }

    private void close(TestChromosome test, long generation) {
        Pending entry = pending.get(test);
        if (entry == null) {
            return;
        }
        Double distance = test.getFitnessValues().get(entry.goal);
        if (distance != null) {
            // Test-level path: the goal's fitness was computed on this test in
            // this very evaluation, so the value is a direct verdict.
            pending.remove(test);
            memory.recordAttemptOutcome(entry.attemptId, distance == 0.0 ? 1 : 0);
        } else if (test.getTestCase().getCoveredGoals().contains(entry.goal)) {
            // Suite-level path: the goal's own criterion has run and covered it.
            pending.remove(test);
            memory.recordAttemptOutcome(entry.attemptId, 1);
        } else if (generation > entry.generation) {
            // Suite-level path, no evidence: every criterion of the opening
            // generation has certainly run by now (this notification belongs
            // to a later generation) without covering the goal, so the
            // absence of a covered-goal entry is finally a verdict.
            pending.remove(test);
            memory.recordAttemptOutcome(entry.attemptId, 0);
        }
        // Otherwise the goal's criterion within the opening generation may not
        // have run yet — no evidence either way, so the attempt stays open.
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
