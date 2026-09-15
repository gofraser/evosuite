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
package org.evosuite.llm.search.operator;

import org.evosuite.llm.LlmCallFailedException;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.search.DiagnosticCardSelector;
import org.evosuite.llm.search.InjectionAttemptMetadata;
import org.evosuite.llm.search.LlmSemanticCrossover;
import org.evosuite.llm.search.LlmSemanticMutation;
import org.evosuite.llm.search.RepeatedInjectionMemory;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link LlmOperatorExecutor}: batching, submission gates (in-flight
 * cap, budget, time guard), publication of results, attempt lifecycle in
 * {@link RepeatedInjectionMemory}, and shutdown semantics.
 */
class LlmOperatorExecutorTest {

    private static final String SINGLE_TARGET_RESPONSE =
            "```java\n"
                    + "import org.junit.Test;\n"
                    + "public class GeneratedLlmTest {\n"
                    + "  @Test\n"
                    + "  public void test_target_1() {\n"
                    + "    int one = 1;\n"
                    + "  }\n"
                    + "}\n"
                    + "```";

    private static TestChromosome chromosome() {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        return tc;
    }

    private static TestFitnessFunction goal(String name) {
        TestFitnessFunction g = mock(TestFitnessFunction.class);
        when(g.toString()).thenReturn(name);
        return g;
    }

    private static LlmService availableService() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.hasBudget(any(LlmFeature.class))).thenReturn(true);
        return llmService;
    }

    private static LlmOperatorExecutor newExecutor(LlmService llmService, RepeatedInjectionMemory memory,
                                                   long remainingSeconds, int budgetGuardSeconds,
                                                   int maxInFlight, int batchSize) {
        return new LlmOperatorExecutor(new LlmSemanticMutation(llmService), new LlmSemanticCrossover(llmService),
                memory, () -> remainingSeconds, budgetGuardSeconds, maxInFlight, batchSize);
    }

    private static List<TestChromosome> awaitDrain(LlmOperatorExecutor executor, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        do {
            List<TestChromosome> drained = executor.drain();
            if (!drained.isEmpty()) {
                return drained;
            }
            Thread.sleep(10L);
        } while (System.nanoTime() < deadline);
        return executor.drain();
    }

    @Test
    void oneMutationTargetProducesOneChromosomeWithAttribution() throws Exception {
        LlmService llmService = availableService();
        when(llmService.query(any(PromptResult.class), any(LlmFeature.class))).thenReturn(SINGLE_TARGET_RESPONSE);
        RepeatedInjectionMemory memory = new RepeatedInjectionMemory();
        LlmOperatorExecutor executor = newExecutor(llmService, memory, -1L, 0, 2, 1);

        TestChromosome parent = chromosome();
        parent.setInjectionLineageId(7L);
        TestFitnessFunction goal = goal("g1");
        OperatorTarget target = OperatorTarget.builder(parent, goal).build();

        try {
            assertEquals(1, executor.submit(Collections.singletonList(target)));
            List<TestChromosome> produced = awaitDrain(executor, 5);
            assertEquals(1, produced.size());

            TestChromosome candidate = produced.get(0);
            InjectionAttemptMetadata metadata = executor.consumeAttemptMetadata(candidate);
            assertNotNull(metadata);
            assertFalse(metadata.getAttemptId().isEmpty());
            assertEquals(Collections.singletonList(goal), metadata.getTargetGoals());
            assertTrue(candidate.effectiveLineages().contains(7L));

            // Still open: a fresh check for the same target key is suppressed as in-flight.
            assertEquals(DiagnosticCardSelector.DiscardReason.IN_FLIGHT_REPEAT,
                    memory.adjustPriority(target.toRepeatedInjectionTarget(), 1.0, true).getDiscardReason());
            executor.reportAttemptOutcome(metadata.getAttemptId(), 1);
            assertNotEquals(DiagnosticCardSelector.DiscardReason.IN_FLIGHT_REPEAT,
                    memory.adjustPriority(target.toRepeatedInjectionTarget(), 1.0, true).getDiscardReason());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void threeMutationTargetsWithBatchSizeTwoMakeTwoCalls() {
        LlmService llmService = availableService();
        when(llmService.query(any(PromptResult.class), any(LlmFeature.class))).thenReturn(SINGLE_TARGET_RESPONSE);
        LlmOperatorExecutor executor = newExecutor(llmService, null, -1L, 0, 3, 2);

        List<OperatorTarget> targets = Arrays.asList(
                OperatorTarget.builder(chromosome(), goal("g1")).build(),
                OperatorTarget.builder(chromosome(), goal("g2")).build(),
                OperatorTarget.builder(chromosome(), goal("g3")).build());

        try {
            assertEquals(2, executor.submit(targets));
            assertEquals(2, executor.getSubmittedCalls());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void mixedKindsNeverMixInOneCall() {
        LlmService llmService = availableService();
        when(llmService.query(any(PromptResult.class), any(LlmFeature.class))).thenReturn(SINGLE_TARGET_RESPONSE);
        LlmOperatorExecutor executor = newExecutor(llmService, null, -1L, 0, 3, 3);

        OperatorTarget mutationA = OperatorTarget.builder(chromosome(), goal("g1")).build();
        OperatorTarget crossover = OperatorTarget.builder(chromosome(), goal("g2")).partner(chromosome()).build();
        OperatorTarget mutationB = OperatorTarget.builder(chromosome(), goal("g3")).build();

        try {
            assertEquals(3, executor.submit(Arrays.asList(mutationA, crossover, mutationB)));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void inFlightCapDropsSecondSubmission() throws Exception {
        LlmService llmService = availableService();
        CountDownLatch latch = new CountDownLatch(1);
        when(llmService.query(any(PromptResult.class), any(LlmFeature.class))).thenAnswer(invocation -> {
            awaitLatch(latch);
            return SINGLE_TARGET_RESPONSE;
        });
        LlmOperatorExecutor executor = newExecutor(llmService, null, -1L, 0, 1, 1);

        OperatorTarget first = OperatorTarget.builder(chromosome(), goal("g1")).build();
        OperatorTarget second = OperatorTarget.builder(chromosome(), goal("g2")).build();

        try {
            assertEquals(1, executor.submit(Collections.singletonList(first)));
            assertEquals(0, executor.submit(Collections.singletonList(second)));
            assertEquals(1, executor.getDroppedInFlight());
            assertFalse(executor.hasCapacity());

            latch.countDown();
            List<TestChromosome> produced = awaitDrain(executor, 5);
            assertEquals(1, produced.size());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void unavailableServiceDropsUnderBudgetCounter() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isAvailable()).thenReturn(true);
        when(llmService.hasBudget(any(LlmFeature.class))).thenReturn(false);
        RepeatedInjectionMemory memory = new RepeatedInjectionMemory();
        LlmOperatorExecutor executor = newExecutor(llmService, memory, -1L, 0, 2, 1);

        OperatorTarget target = OperatorTarget.builder(chromosome(), goal("g1")).build();

        try {
            assertEquals(0, executor.submit(Collections.singletonList(target)));
            assertEquals(1, executor.getDroppedBudget());
            assertFalse(memory.adjustPriority(target.toRepeatedInjectionTarget(), 1.0, true).isSuppressed());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void timeGuardDropsWhenTooLittleTimeRemains() {
        LlmService llmService = availableService();
        when(llmService.query(any(PromptResult.class), any(LlmFeature.class))).thenReturn(SINGLE_TARGET_RESPONSE);

        LlmOperatorExecutor tight = newExecutor(llmService, null, 10L, 45, 2, 1);
        OperatorTarget target1 = OperatorTarget.builder(chromosome(), goal("g1")).build();
        try {
            assertEquals(0, tight.submit(Collections.singletonList(target1)));
            assertEquals(1, tight.getDroppedTime());
        } finally {
            tight.shutdown();
        }

        LlmOperatorExecutor unguarded = newExecutor(llmService, null, 10L, 0, 2, 1);
        OperatorTarget target2 = OperatorTarget.builder(chromosome(), goal("g2")).build();
        try {
            assertEquals(1, unguarded.submit(Collections.singletonList(target2)));
        } finally {
            unguarded.shutdown();
        }
    }

    @Test
    void emptyResponseClosesAttemptWithoutProducingCandidates() throws Exception {
        LlmService llmService = availableService();
        when(llmService.query(any(PromptResult.class), any(LlmFeature.class)))
                .thenThrow(new LlmCallFailedException("boom", null, false));
        RepeatedInjectionMemory memory = new RepeatedInjectionMemory();
        LlmOperatorExecutor executor = newExecutor(llmService, memory, -1L, 0, 2, 1);

        OperatorTarget target = OperatorTarget.builder(chromosome(), goal("g1")).build();

        try {
            assertEquals(1, executor.submit(Collections.singletonList(target)));
            awaitInFlightZero(executor, 5);
            assertTrue(executor.drain().isEmpty());
            assertEquals(0, executor.getProducedCandidates());
            // The attempt was closed (not left dangling in-flight); it may still be
            // suppressed as a recent repeat, but never as an in-flight one.
            assertNotEquals(DiagnosticCardSelector.DiscardReason.IN_FLIGHT_REPEAT,
                    memory.adjustPriority(target.toRepeatedInjectionTarget(), 1.0, true).getDiscardReason());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void shutdownReleasesOpenAttemptsAndStopsAcceptingWork() throws Exception {
        LlmService llmService = availableService();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(llmService.query(any(PromptResult.class), any(LlmFeature.class))).thenAnswer(invocation -> {
            calls.incrementAndGet();
            awaitLatch(latch);
            return SINGLE_TARGET_RESPONSE;
        });
        RepeatedInjectionMemory memory = new RepeatedInjectionMemory();
        LlmOperatorExecutor executor = newExecutor(llmService, memory, -1L, 0, 1, 1);

        OperatorTarget target = OperatorTarget.builder(chromosome(), goal("g1")).build();
        assertEquals(1, executor.submit(Collections.singletonList(target)));

        executor.shutdown();
        latch.countDown();

        assertTrue(executor.drain().isEmpty());
        // Released (not left dangling in-flight) by shutdown(), even though the worker never
        // got to publish or close it itself.
        assertNotEquals(DiagnosticCardSelector.DiscardReason.IN_FLIGHT_REPEAT,
                memory.adjustPriority(target.toRepeatedInjectionTarget(), 1.0, true).getDiscardReason());
        assertEquals(0, executor.submit(Collections.singletonList(
                OperatorTarget.builder(chromosome(), goal("g2")).build())));
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static void awaitInFlightZero(LlmOperatorExecutor executor, int timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (executor.getInFlight() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
    }
}
