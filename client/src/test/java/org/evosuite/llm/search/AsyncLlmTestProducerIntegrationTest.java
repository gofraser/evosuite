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
package org.evosuite.llm.search;

import org.evosuite.Properties;
import org.evosuite.llm.*;
import org.evosuite.llm.mock.MockChatLanguageModel;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncLlmTestProducerIntegrationTest {

    private static final String SIMPLE_JUNIT_RESPONSE =
            "```java\n" +
                    "import org.junit.Test;\n" +
                    "public class GeneratedLlmTest {\n" +
                    "  @Test\n" +
                    "  public void generatedTest() {\n" +
                    "  }\n" +
                    "}\n" +
                    "```";

    @Test
    void asyncProducerPushesChromosomesIntoQueue() throws Exception {
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.ASYNC_PRODUCER, SIMPLE_JUNIT_RESPONSE);
        LlmService service = createService(model, 2);

        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.toString()).thenReturn("goal-1");

        AsyncLlmTestProducer producer = new AsyncLlmTestProducer(
                () -> Collections.singleton(goal),
                null,
                service,
                5,
                1,
                0,
                null);

        try {
            producer.start();
            List<AttributedCandidate> produced = awaitAvailable(producer, 10, TimeUnit.SECONDS);
            assertFalse(produced.isEmpty(), "producer should provide at least one chromosome");
        } finally {
            producer.stop();
            service.close();
        }
    }

    @Test
    void asyncProducerPreservesAttemptMetadataForDrainedChromosomes() throws Exception {
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.ASYNC_PRODUCER, SIMPLE_JUNIT_RESPONSE);
        LlmService service = createService(model, 2);

        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.toString()).thenReturn("goal-1");
        when(goal.getTargetClass()).thenReturn("com.example.Foo");
        when(goal.getTargetMethod()).thenReturn("work()V");

        AsyncLlmTestProducer producer = new AsyncLlmTestProducer(
                () -> Collections.singleton(goal),
                null,
                service,
                5,
                1,
                0,
                new RepeatedInjectionMemory());

        try {
            producer.start();
            List<AttributedCandidate> produced = awaitAvailable(producer, 10, TimeUnit.SECONDS);
            assertFalse(produced.isEmpty(), "producer should provide at least one chromosome");
            InjectionAttemptMetadata metadata = produced.get(0).getMetadata();
            assertNotNull(metadata);
            assertFalse(metadata.getAttemptId().isEmpty());
        } finally {
            producer.stop();
            service.close();
        }
    }

    @Test
    void asyncProducerFallsBackToPoolPromptWhenDiagnosticModeHasNoCards() throws Exception {
        Properties.LlmAsyncProducerPromptMode previousMode = Properties.LLM_ASYNC_PRODUCER_PROMPT;
        Properties.LLM_ASYNC_PRODUCER_PROMPT = Properties.LlmAsyncProducerPromptMode.DIAGNOSTIC;
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.ASYNC_PRODUCER, SIMPLE_JUNIT_RESPONSE);
        LlmService service = createService(model, 2);

        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.toString()).thenReturn("goal-1");

        AsyncLlmTestProducer producer = new AsyncLlmTestProducer(
                () -> Collections.singleton(goal),
                null,
                service,
                5,
                1,
                0,
                null);

        try {
            producer.start();
            List<AttributedCandidate> produced = awaitAvailable(producer, 10, TimeUnit.SECONDS);
            assertFalse(produced.isEmpty(),
                    "DIAGNOSTIC mode must fall back to the pool prompt when no cards can be extracted");
        } finally {
            producer.stop();
            service.close();
            Properties.LLM_ASYNC_PRODUCER_PROMPT = previousMode;
        }
    }

    @Test
    void asyncProducerStopsAfterConsecutiveConfirmedEmptySnapshots() throws Exception {
        LlmService service = mock(LlmService.class);
        when(service.isAvailable()).thenReturn(true);
        when(service.hasBudget(any(LlmFeature.class))).thenReturn(true);
        AsyncLlmTestProducer producer = new AsyncLlmTestProducer(
                Collections::emptyList,
                null,
                service,
                1,
                1,
                1,
                null);

        try {
            producer.start();
            long deadline = System.currentTimeMillis() + 2000L;
            while (producer.getStoppedReason() == AsyncLlmTestProducer.StopReason.NONE
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            assertEquals(AsyncLlmTestProducer.StopReason.CONFIRMED_NO_GOALS,
                    producer.getStoppedReason());
            assertEquals(3L, producer.getLoopIterations());
        } finally {
            producer.stop();
        }
    }

    @Test
    void candidatesOfOneResponseBecomeVisibleTogether() throws Exception {
        LlmService service = createService(new MockChatLanguageModel(), 2);
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.toString()).thenReturn("goal-1");
        when(goal.getTargetClass()).thenReturn("com.example.Foo");
        when(goal.getTargetMethod()).thenReturn("work()V");
        AtomicBoolean responded = new AtomicBoolean();

        // A queue smaller than the response: a consumer draining between two
        // candidates would close the attempt before the second one is reported.
        AsyncLlmTestProducer producer = new AsyncLlmTestProducer(
                () -> Collections.singleton(goal), null, service, 1, 1, 20,
                new RepeatedInjectionMemory()) {
            @Override
            protected List<TestChromosome> requestCandidates(PromptResult prompt, TestRepairLoop repairLoop) {
                if (responded.compareAndSet(false, true)) {
                    return Arrays.asList(new TestChromosome(), new TestChromosome());
                }
                return Collections.emptyList();
            }
        };

        try {
            producer.start();
            List<AttributedCandidate> produced = awaitAvailable(producer, 10, TimeUnit.SECONDS);
            assertEquals(2, produced.size(),
                    "both candidates of one response must be drained together");
            InjectionAttemptMetadata first = produced.get(0).getMetadata();
            InjectionAttemptMetadata second = produced.get(1).getMetadata();
            assertNotNull(first);
            assertEquals(first.getAttemptId(), second.getAttemptId());
        } finally {
            producer.stop();
            service.close();
        }
    }

    @Test
    void failedAndUnaffordableCallsReleaseTheirAttempts() throws Exception {
        LlmService service = createService(new MockChatLanguageModel(), 2);
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.toString()).thenReturn("goal-1");
        when(goal.getTargetClass()).thenReturn("com.example.Foo");
        when(goal.getTargetMethod()).thenReturn("work()V");
        RepeatedInjectionMemory memory = spy(new RepeatedInjectionMemory());
        AtomicBoolean failedOnce = new AtomicBoolean();

        // First call fails at the provider, the second finds the budget spent; neither delivers a
        // response, so neither may count as a no-gain attempt.
        AsyncLlmTestProducer producer = new AsyncLlmTestProducer(
                () -> Collections.singleton(goal), null, service, 5, 1, 0, memory) {
            @Override
            protected List<TestChromosome> requestCandidates(PromptResult prompt, TestRepairLoop repairLoop) {
                if (failedOnce.compareAndSet(false, true)) {
                    throw new LlmCallFailedException("provider down", null, true);
                }
                throw new LlmBudgetExceededException("no budget left");
            }
        };

        try {
            producer.start();
            long deadline = System.currentTimeMillis() + 10_000L;
            while (producer.getStoppedReason() == AsyncLlmTestProducer.StopReason.NONE
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            assertEquals(AsyncLlmTestProducer.StopReason.BUDGET_EXHAUSTED, producer.getStoppedReason());
            verify(memory, times(2)).releaseUndeliveredAttempt(anyString());
            verify(memory, never()).recordAttemptOutcome(anyString(), anyInt());
        } finally {
            producer.stop();
            service.close();
        }
    }

    private static LlmService createService(LlmService.ChatLanguageModel model, int budget) {
        LlmConfiguration configuration = new LlmConfiguration(
                Properties.LlmProvider.NONE,
                "mock",
                "",
                "",
                0.0,
                1024,
                2,
                0,
                1,
                false,
                Paths.get("target/llm-test-traces"),
                "async-integration");
        return new LlmService(model,
                LlmBudgetCoordinator.of(budget),
                configuration,
                new LlmStatistics(),
                new LlmTraceRecorder(configuration));
    }

    private static List<AttributedCandidate> awaitAvailable(AsyncLlmTestProducer producer,
                                                        long timeout,
                                                        TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        do {
            List<AttributedCandidate> produced = producer.drainAvailable();
            if (!produced.isEmpty()) {
                return produced;
            }
            TimeUnit.MILLISECONDS.sleep(10L);
        } while (System.nanoTime() < deadline);
        return producer.drainAvailable();
    }
}
