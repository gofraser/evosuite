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
import org.evosuite.Properties.LlmStagnationMode;
import org.evosuite.llm.LlmBudgetCoordinator;
import org.evosuite.llm.LlmConfiguration;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.LlmStatistics;
import org.evosuite.llm.LlmTraceRecorder;
import org.evosuite.llm.mock.MockChatLanguageModel;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StagnationLlmHelperTest {

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
    void syncMode_returnsTestsImmediatelyOnDrain() {
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.STAGNATION, SIMPLE_JUNIT_RESPONSE);
        LlmService service = createService(model, 2);
        AtomicLong clock = new AtomicLong(0L);
        StagnationDetector detector = new StagnationDetector(service, false, 1, 1, clock::get);
        StagnationLlmHelper helper = new StagnationLlmHelper(
                detector, LlmStagnationMode.SYNC, () -> -1L, 0);

        try {
            TestFitnessFunction goal = makeGoal("g");
            List<TestChromosome> pop = Collections.singletonList(new TestChromosome());

            // First call: not stagnant yet.
            helper.maybeSubmit(0, Collections.singleton(goal), pop);
            assertTrue(helper.drain().isEmpty());

            // Advance past the 1s threshold; second call ticks stagnation, SYNC runs inline.
            clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
            helper.maybeSubmit(0, Collections.singleton(goal), pop);
            List<TestChromosome> tests = helper.drain();
            assertFalse(tests.isEmpty(), "SYNC mode should produce tests on the same call");
        } finally {
            helper.shutdown();
            service.close();
        }
    }

    @Test
    void asyncMode_returnsEmptyImmediately_thenDeliversLater() throws Exception {
        CountDownLatch responseGate = new CountDownLatch(1);
        MockChatLanguageModel model = new MockChatLanguageModel() {
            @Override
            public LlmService.LlmResponse generate(List<LlmMessage> messages, LlmFeature feature) {
                try {
                    responseGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.generate(messages, feature);
            }
        };
        model.enqueue(LlmFeature.STAGNATION, SIMPLE_JUNIT_RESPONSE);
        LlmService service = createService(model, 2);
        AtomicLong clock = new AtomicLong(0L);
        StagnationDetector detector = new StagnationDetector(service, false, 1, 1, clock::get);
        StagnationLlmHelper helper = new StagnationLlmHelper(
                detector, LlmStagnationMode.ASYNC, () -> -1L, 0);

        try {
            TestFitnessFunction goal = makeGoal("g");
            List<TestChromosome> pop = Collections.singletonList(new TestChromosome());

            helper.maybeSubmit(0, Collections.singleton(goal), pop);  // not stagnant
            clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
            helper.maybeSubmit(0, Collections.singleton(goal), pop);  // submits async
            assertTrue(helper.drain().isEmpty(),
                    "ASYNC mode must not deliver tests on the submission call");

            // Subsequent submit during in-flight: deduped.
            clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
            helper.maybeSubmit(0, Collections.singleton(goal), pop);
            assertTrue(helper.drain().isEmpty());

            // Let the response complete.
            responseGate.countDown();

            // Poll briefly for the async tests to land.
            List<TestChromosome> tests = drainWithTimeout(helper, 2_000);
            assertFalse(tests.isEmpty(),
                    "ASYNC mode should eventually deliver tests via drain()");
        } finally {
            helper.shutdown();
            service.close();
        }
    }

    @Test
    void budgetGuard_skipsSubmissionWhenRemainingBelowThreshold() {
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.STAGNATION, SIMPLE_JUNIT_RESPONSE);
        LlmService service = createService(model, 2);
        AtomicLong clock = new AtomicLong(0L);
        StagnationDetector detector = new StagnationDetector(service, false, 1, 1, clock::get);
        // Guard at 60s; remaining is 5s — must skip.
        StagnationLlmHelper helper = new StagnationLlmHelper(
                detector, LlmStagnationMode.SYNC, () -> 5L, 60);

        try {
            TestFitnessFunction goal = makeGoal("g");
            List<TestChromosome> pop = Collections.singletonList(new TestChromosome());

            helper.maybeSubmit(0, Collections.singleton(goal), pop);   // not stagnant
            clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
            helper.maybeSubmit(0, Collections.singleton(goal), pop);   // stagnant but guarded
            assertEquals(0, helper.drain().size(),
                    "Helper must skip submission when remaining budget is below guard");
        } finally {
            helper.shutdown();
            service.close();
        }
    }

    @Test
    void budgetGuardSkip_doesNotConsumeStagnationWindow() {
        // Phase 3 T1.1: a skipped submission (here: budget guard) must leave the
        // detector window intact, so the *next* generation still observes
        // stagnation and can fire once the guard relaxes. Pre-fix behavior
        // reset the window on every checkStagnation()==true, even when skipped.
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.STAGNATION, SIMPLE_JUNIT_RESPONSE);
        LlmService service = createService(model, 2);
        AtomicLong clock = new AtomicLong(0L);
        AtomicLong remainingBudget = new AtomicLong(5L);
        StagnationDetector detector = new StagnationDetector(service, false, 1, 1, clock::get);
        // Guard at 60s; remaining starts at 5s — must skip, then becomes 600s.
        StagnationLlmHelper helper = new StagnationLlmHelper(
                detector, LlmStagnationMode.SYNC, remainingBudget::get, 60);

        try {
            TestFitnessFunction goal = makeGoal("g");
            List<TestChromosome> pop = Collections.singletonList(new TestChromosome());

            helper.maybeSubmit(0, Collections.singleton(goal), pop);   // not stagnant yet
            clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
            helper.maybeSubmit(0, Collections.singleton(goal), pop);   // stagnant but skipped (budget)
            assertEquals(0, helper.drain().size(),
                    "Budget-guarded submission must not deliver tests");

            // Relax the guard; window should still be expired and the next
            // call should fire without waiting another stagnation_timeout.
            remainingBudget.set(600L);
            helper.maybeSubmit(0, Collections.singleton(goal), pop);
            List<TestChromosome> tests = helper.drain();
            assertFalse(tests.isEmpty(),
                    "After the budget guard relaxes, the helper must fire immediately "
                            + "instead of waiting for a fresh stagnation window.");
        } finally {
            helper.shutdown();
            service.close();
        }
    }

    @Test
    void syncMode_truncatesWaitToRemainingBudget() throws Exception {
        // The SYNC arm wraps the LLM call in a Future and waits at most
        // min(LLM_TIMEOUT_SECONDS, remaining) seconds — so a late-firing
        // stagnation call cannot block the search loop past the deadline,
        // even if the LLM itself hangs.
        CountDownLatch responseGate = new CountDownLatch(1);
        MockChatLanguageModel model = new MockChatLanguageModel() {
            @Override
            public LlmService.LlmResponse generate(List<LlmMessage> messages, LlmFeature feature) {
                try {
                    responseGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.generate(messages, feature);
            }
        };
        model.enqueue(LlmFeature.STAGNATION, SIMPLE_JUNIT_RESPONSE);
        LlmService service = createService(model, 2);
        AtomicLong clock = new AtomicLong(0L);
        StagnationDetector detector = new StagnationDetector(service, false, 1, 1, clock::get);
        // Guard 0 (no skip) + remaining=1s makes the wait cap at 1s, even
        // though LLM_TIMEOUT_SECONDS would normally allow much longer.
        StagnationLlmHelper helper = new StagnationLlmHelper(
                detector, LlmStagnationMode.SYNC, () -> 1L, 0);

        try {
            TestFitnessFunction goal = makeGoal("g");
            List<TestChromosome> pop = Collections.singletonList(new TestChromosome());

            helper.maybeSubmit(0, Collections.singleton(goal), pop);  // not stagnant
            clock.addAndGet(TimeUnit.SECONDS.toNanos(2));

            long t0 = System.nanoTime();
            helper.maybeSubmit(0, Collections.singleton(goal), pop);  // SYNC fires, hangs
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            assertTrue(elapsedMs < 4_000,
                    "SYNC must abort the wait near the truncated deadline, "
                            + "not block on the full LLM timeout. Took " + elapsedMs + "ms");
            assertEquals(0, helper.drain().size(),
                    "Aborted SYNC call must not deliver tests");
        } finally {
            responseGate.countDown();
            helper.shutdown();
            service.close();
        }
    }

    @Test
    void asyncMode_gracefulShutdown_capturesInFlightCall() throws Exception {
        // Phase 3 T3.3: stopAcceptingSubmissions + awaitTermination should let
        // an in-flight call finish and its tests be drained, instead of
        // cancelling mid-flight like shutdown() would.
        CountDownLatch responseGate = new CountDownLatch(1);
        MockChatLanguageModel model = new MockChatLanguageModel() {
            @Override
            public LlmService.LlmResponse generate(List<LlmMessage> messages, LlmFeature feature) {
                try {
                    responseGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.generate(messages, feature);
            }
        };
        model.enqueue(LlmFeature.STAGNATION, SIMPLE_JUNIT_RESPONSE);
        LlmService service = createService(model, 2);
        AtomicLong clock = new AtomicLong(0L);
        StagnationDetector detector = new StagnationDetector(service, false, 1, 1, clock::get);
        StagnationLlmHelper helper = new StagnationLlmHelper(
                detector, LlmStagnationMode.ASYNC, () -> -1L, 0);

        try {
            TestFitnessFunction goal = makeGoal("g");
            List<TestChromosome> pop = Collections.singletonList(new TestChromosome());

            helper.maybeSubmit(0, Collections.singleton(goal), pop);   // not stagnant
            clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
            helper.maybeSubmit(0, Collections.singleton(goal), pop);   // submits async

            // Soft-stop while the worker is blocked on responseGate.
            helper.stopAcceptingSubmissions();

            // Let the response flow; the worker is still alive, will publish.
            responseGate.countDown();

            boolean terminated = helper.awaitTermination(2, TimeUnit.SECONDS);
            assertTrue(terminated, "Worker should finish in-flight call after stopAcceptingSubmissions");

            List<TestChromosome> tests = helper.drain();
            assertFalse(tests.isEmpty(),
                    "Graceful shutdown must allow the in-flight call's tests to be drained");
        } finally {
            helper.shutdown();
            service.close();
        }
    }

    @Test
    void asyncMode_submitAfterShutdown_isBenignNoOp() {
        // Phase 3 T2.2 (smoke test): maybeSubmit() and drain() called after
        // shutdown() must complete cleanly. The true race scenario
        // (RejectedExecutionException between the shutdown-flag check and
        // worker.submit) is not deterministically reachable through the
        // public API, but this test pins down the post-shutdown surface and
        // catches accidental regressions of the shutdown-flag short-circuit.
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.STAGNATION, SIMPLE_JUNIT_RESPONSE);
        LlmService service = createService(model, 2);
        AtomicLong clock = new AtomicLong(0L);
        StagnationDetector detector = new StagnationDetector(service, false, 1, 1, clock::get);
        StagnationLlmHelper helper = new StagnationLlmHelper(
                detector, LlmStagnationMode.ASYNC, () -> -1L, 0);

        try {
            TestFitnessFunction goal = makeGoal("g");
            List<TestChromosome> pop = Collections.singletonList(new TestChromosome());

            helper.maybeSubmit(0, Collections.singleton(goal), pop);  // not stagnant yet
            helper.shutdown();
            clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
            helper.maybeSubmit(0, Collections.singleton(goal), pop);
            assertTrue(helper.drain().isEmpty(),
                    "Post-shutdown maybeSubmit must produce no tests");
            // Calling shutdown() a second time must be safe.
            helper.shutdown();
        } finally {
            service.close();
        }
    }

    private static TestFitnessFunction makeGoal(String name) {
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.toString()).thenReturn(name);
        when(goal.getFitness(org.mockito.ArgumentMatchers.any(TestChromosome.class))).thenReturn(1.0);
        return goal;
    }

    private static List<TestChromosome> drainWithTimeout(StagnationLlmHelper helper, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<TestChromosome> drained = helper.drain();
            if (!drained.isEmpty()) {
                return drained;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        return helper.drain();
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
                "stagnation-helper-test");
        return new LlmService(model,
                new LlmBudgetCoordinator.Local(budget),
                configuration,
                new LlmStatistics(),
                new LlmTraceRecorder(configuration));
    }
}
