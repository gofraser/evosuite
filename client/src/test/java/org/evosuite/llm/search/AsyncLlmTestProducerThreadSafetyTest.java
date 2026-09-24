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
 * You should have received a copy of the GNU Lesser General Public License
 * along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.llm.search;

import org.evosuite.Properties;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AsyncLlmTestProducerThreadSafetyTest {

    @Test
    @SuppressWarnings("unchecked")
    void safePopulationSnapshotDoesNotMutateLiveChromosomes() throws Exception {
        boolean oldSelection = Properties.LLM_RELEVANCE_BASED_TEST_SELECTION;
        Properties.LLM_RELEVANCE_BASED_TEST_SELECTION = true;
        try {
            TestChromosome live = new TestChromosome();
            TestFitnessFunction goal = new FixedFitnessGoal("example.Target", "method()V", 1.0);

            AsyncLlmTestProducer producer = new AsyncLlmTestProducer(
                    () -> Collections.singleton(goal),
                    () -> Collections.singletonList(live),
                    mock(LlmService.class),
                    1,
                    1,
                    0);

            Method snapshotMethod = AsyncLlmTestProducer.class
                    .getDeclaredMethod("safeChromosomeSnapshot");
            snapshotMethod.setAccessible(true);
            List<TestChromosome> detached = (List<TestChromosome>) snapshotMethod.invoke(producer);

            Method projectMethod = AsyncLlmTestProducer.class
                    .getDeclaredMethod("projectTestCases", List.class, Collection.class);
            projectMethod.setAccessible(true);
            List<TestCase> tests = (List<TestCase>) projectMethod.invoke(
                    producer, detached, Collections.singleton(goal));

            assertEquals(1, tests.size(), "Expected one test from population snapshot");
            assertTrue(live.getFitnessValues().isEmpty(),
                    "Live chromosome fitness cache must not be mutated by async snapshot ranking");
        } finally {
            Properties.LLM_RELEVANCE_BASED_TEST_SELECTION = oldSelection;
        }
    }

    @Test
    void transientGoalSnapshotFailureDoesNotStopProduction() throws Exception {
        LlmService service = mock(LlmService.class);
        when(service.isAvailable()).thenReturn(true);
        when(service.hasBudget()).thenReturn(true);
        TestFitnessFunction goal = new FixedFitnessGoal("example.Target", "method()V", 1.0);
        AtomicInteger snapshots = new AtomicInteger();
        AsyncLlmTestProducer producer = new StubProducingAsyncLlmTestProducer(
                () -> {
                    if (snapshots.getAndIncrement() == 0) {
                        throw new java.util.ConcurrentModificationException("transient snapshot race");
                    }
                    return Collections.singleton(goal);
                },
                service);

        try {
            producer.start();
            List<TestChromosome> produced = Collections.emptyList();
            long deadline = System.currentTimeMillis() + 2000L;
            while (produced.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
                produced = producer.drainAvailable();
            }
            assertFalse(produced.isEmpty(), "transient snapshot failure must not kill the producer");
            assertTrue(snapshots.get() >= 2);
        } finally {
            producer.stop();
        }
    }

    @Test
    void stopDropsCandidateThatArrivesAfterAnInterruptIgnoringRequest() throws Exception {
        LlmService service = mock(LlmService.class);
        when(service.isAvailable()).thenReturn(true);
        when(service.hasBudget()).thenReturn(true);
        CountDownLatch requestEntered = new CountDownLatch(1);
        CountDownLatch releaseRequest = new CountDownLatch(1);
        CountDownLatch requestReturned = new CountDownLatch(1);
        TestChromosome lateCandidate = new TestChromosome();
        AsyncLlmTestProducer producer = new AsyncLlmTestProducer(
                () -> Collections.singleton(new FixedFitnessGoal("example.Target", "method()V", 1.0)),
                null, service, 1, 1, 0) {
            @Override
            protected List<TestChromosome> requestCandidates(PromptResult prompt, TestRepairLoop repairLoop) {
                requestEntered.countDown();
                boolean interrupted = false;
                while (true) {
                    try {
                        releaseRequest.await();
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                requestReturned.countDown();
                return Collections.singletonList(lateCandidate);
            }
        };

        try {
            producer.start();
            assertTrue(requestEntered.await(1, TimeUnit.SECONDS));
            // stop() returns after its bounded join even when the request ignores
            // interruption. Releasing it afterwards exercises the late-result path.
            producer.stop();
            releaseRequest.countDown();
            assertTrue(requestReturned.await(1, TimeUnit.SECONDS));
            assertTrue(producer.drainAvailable().isEmpty(),
                    "A candidate returned after stop() must not be published");
            assertNull(producer.consumeAttemptMetadata(lateCandidate));
        } finally {
            releaseRequest.countDown();
            producer.stop();
        }
    }

    @Test
    void fullQueueWaitsForConsumerInsteadOfDroppingCandidates() throws Exception {
        LlmService service = mock(LlmService.class);
        when(service.isAvailable()).thenReturn(true);
        when(service.hasBudget()).thenReturn(true);
        List<TestChromosome> firstResponse = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            firstResponse.add(new TestChromosome());
        }
        AtomicInteger requests = new AtomicInteger();
        AsyncLlmTestProducer producer = new AsyncLlmTestProducer(
                () -> Collections.singleton(new FixedFitnessGoal("example.Target", "method()V", 1.0)),
                null, service, 2, 1, 1) {
            @Override
            protected List<TestChromosome> requestCandidates(PromptResult prompt, TestRepairLoop repairLoop) {
                return requests.getAndIncrement() == 0 ? firstResponse : Collections.emptyList();
            }
        };

        try {
            producer.start();
            long deadline = System.currentTimeMillis() + 2000L;
            while (requests.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            // With the queue full and nobody draining, the producer must wait
            // rather than drop candidates and issue further LLM requests.
            Thread.sleep(300L);
            assertEquals(1, requests.get(), "producer must not query the LLM while its queue is full");

            List<TestChromosome> drained = new ArrayList<>();
            deadline = System.currentTimeMillis() + 2000L;
            while (drained.size() < firstResponse.size() && System.currentTimeMillis() < deadline) {
                drained.addAll(producer.drainAvailable());
                Thread.sleep(10L);
            }
            assertEquals(firstResponse.size(), drained.size(), "every candidate must reach the consumer");
            for (int i = 0; i < firstResponse.size(); i++) {
                assertSame(firstResponse.get(i), drained.get(i));
                assertNotNull(producer.consumeAttemptMetadata(drained.get(i)));
            }
        } finally {
            producer.stop();
        }
    }

    @Test
    void stopReleasesProducerWaitingOnFullQueue() throws Exception {
        LlmService service = mock(LlmService.class);
        when(service.isAvailable()).thenReturn(true);
        when(service.hasBudget()).thenReturn(true);
        CountDownLatch requestReturned = new CountDownLatch(1);
        TestChromosome first = new TestChromosome();
        TestChromosome blocked = new TestChromosome();
        AsyncLlmTestProducer producer = new AsyncLlmTestProducer(
                () -> Collections.singleton(new FixedFitnessGoal("example.Target", "method()V", 1.0)),
                null, service, 1, 1, 0) {
            @Override
            protected List<TestChromosome> requestCandidates(PromptResult prompt, TestRepairLoop repairLoop) {
                requestReturned.countDown();
                return Arrays.asList(first, blocked);
            }
        };

        producer.start();
        assertTrue(requestReturned.await(1, TimeUnit.SECONDS));
        Thread.sleep(100L);
        long started = System.nanoTime();
        producer.stop();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMs < 1000L, "stop() must not wait out its join timeout, took " + elapsedMs + " ms");
        assertEquals(AsyncLlmTestProducer.StopReason.STOP_REQUESTED, producer.getStoppedReason());
        List<TestChromosome> drained = producer.drainAvailable();
        assertEquals(Collections.singletonList(first), drained);
        assertNull(producer.consumeAttemptMetadata(blocked));
    }

    private static final class StubProducingAsyncLlmTestProducer extends AsyncLlmTestProducer {

        private StubProducingAsyncLlmTestProducer(
                Supplier<Collection<TestFitnessFunction>> goalsSupplier,
                LlmService service) {
            super(goalsSupplier, null, service, 1, 1, 1);
        }

        @Override
        protected List<TestChromosome> requestCandidates(PromptResult prompt, TestRepairLoop repairLoop) {
            return Collections.singletonList(new TestChromosome());
        }
    }

    private static final class FixedFitnessGoal extends TestFitnessFunction {
        private static final long serialVersionUID = 1L;
        private final String targetClass;
        private final String targetMethod;
        private final double fitness;

        private FixedFitnessGoal(String targetClass, String targetMethod, double fitness) {
            this.targetClass = targetClass;
            this.targetMethod = targetMethod;
            this.fitness = fitness;
        }

        @Override
        public double getFitness(TestChromosome individual, ExecutionResult result) {
            return fitness;
        }

        @Override
        public ExecutionResult runTest(org.evosuite.testcase.TestCase test) {
            return new ExecutionResult(test);
        }

        @Override
        public int compareTo(TestFitnessFunction other) {
            return 0;
        }

        @Override
        public int hashCode() {
            int result = targetClass.hashCode();
            result = 31 * result + targetMethod.hashCode();
            return result;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FixedFitnessGoal)) {
                return false;
            }
            FixedFitnessGoal that = (FixedFitnessGoal) other;
            return targetClass.equals(that.targetClass) && targetMethod.equals(that.targetMethod);
        }

        @Override
        public String getTargetClass() {
            return targetClass;
        }

        @Override
        public String getTargetMethod() {
            return targetMethod;
        }
    }
}
