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
package org.evosuite.ga.metaheuristics.mosa;

import org.evosuite.ga.metaheuristics.mosa.structural.MultiCriteriaManager;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stress-tests {@link DynaMOSA#publishCurrentGoals()} / {@link DynaMOSA#getCurrentGoals()}
 * under concurrent access: a single writer thread repeatedly mutates the goals manager's
 * live frontier and publishes it, while several reader threads call {@code
 * getCurrentGoals()} in a tight loop.
 *
 * <p>The design under test never lets a reader see a partially-built or subsequently
 * mutated set: each publish builds a brand-new immutable set and swaps a volatile
 * reference, so a reader's snapshot is always exactly one of the states the writer
 * published, never a torn mix of two, and never subject to a
 * {@link java.util.ConcurrentModificationException} caused by the writer's later edits.
 */
class CurrentGoalsSnapshotConcurrencyTest extends MosaTestSupport {

    @Test
    void concurrentPublishAndReadNeverExposesAnInconsistentOrMutableSnapshot() throws Exception {
        DynaMOSA dynaMosa = new DynaMOSA(TestChromosome::new);
        dynaMosa.goalsManager = new MultiCriteriaManager(Collections.emptyList());

        int goalCount = 5;
        TestFitnessFunction[] goals = new TestFitnessFunction[goalCount];
        for (int i = 0; i < goalCount; i++) {
            goals[i] = CurrentGoalsSupplierTest.simpleGoal("g" + i);
        }

        // The exact sequence of states the writer will publish: grow 0..N goals, then
        // shrink back down to 0, repeated so both additions and removals are exercised.
        List<Set<TestFitnessFunction>> sequence = new ArrayList<>();
        for (int cycle = 0; cycle < 3; cycle++) {
            for (int size = 0; size <= goalCount; size++) {
                sequence.add(new HashSet<>(Arrays.asList(goals).subList(0, size)));
            }
            for (int size = goalCount - 1; size >= 0; size--) {
                sequence.add(new HashSet<>(Arrays.asList(goals).subList(0, size)));
            }
        }
        Set<Set<TestFitnessFunction>> validStates = new HashSet<>(sequence);

        List<Throwable> errors = new CopyOnWriteArrayList<>();
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch startLatch = new CountDownLatch(1);

        Thread writer = new Thread(() -> {
            try {
                startLatch.await();
                for (Set<TestFitnessFunction> target : sequence) {
                    Set<TestFitnessFunction> live = dynaMosa.goalsManager.getCurrentGoals();
                    live.clear();
                    live.addAll(target);
                    dynaMosa.publishCurrentGoals();
                    Thread.sleep(1);
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        }, "goals-writer");

        int readerCount = 4;
        ExecutorService readerPool = Executors.newFixedThreadPool(readerCount);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < readerCount; i++) {
            futures.add(readerPool.submit(() -> {
                try {
                    startLatch.await();
                    while (!stop.get()) {
                        Set<TestFitnessFunction> observed = dynaMosa.getCurrentGoals();
                        // Force real traversal: a leaked live reference being mutated
                        // concurrently by the writer would raise a CME here.
                        int size = 0;
                        for (TestFitnessFunction ignored : observed) {
                            size++;
                        }
                        if (size != observed.size()) {
                            errors.add(new AssertionError(
                                    "size changed while iterating: " + observed));
                        }
                        if (!validStates.contains(observed)) {
                            errors.add(new AssertionError(
                                    "observed a snapshot that was never published: " + observed));
                        }
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }

        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
        startLatch.countDown();
        writer.start();
        writer.join(5_000);
        assertFalse(writer.isAlive(), "writer thread should have finished within the timeout");

        // Keep readers spinning until the window elapses so they also exercise the
        // period after the writer stopped (volatile visibility of the final state).
        while (System.nanoTime() < deadlineNanos) {
            Thread.onSpinWait();
        }
        stop.set(true);
        readerPool.shutdown();
        assertTrue(readerPool.awaitTermination(5, TimeUnit.SECONDS),
                "reader threads should terminate promptly once stopped");
        for (Future<?> future : futures) {
            assertTrue(future.isDone(), "every reader task should have completed");
        }

        assertTrue(errors.isEmpty(), "no thread should observe an inconsistent snapshot "
                + "or throw (including ConcurrentModificationException): " + errors);

        // Volatile visibility: once the writer is done, readers must see the final
        // published state.
        Set<TestFitnessFunction> finalExpected = sequence.get(sequence.size() - 1);
        assertEquals(finalExpected, dynaMosa.getCurrentGoals(),
                "the final published state must be visible after the writer finishes");
        assertNotSame(dynaMosa.goalsManager.getCurrentGoals(), dynaMosa.getCurrentGoals(),
                "the published snapshot must never be the manager's live set");
    }
}
