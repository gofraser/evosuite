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
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
        LlmStatistics.resetDiagnosticCardCounters();
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
            assertEquals(1L, helper.getPromptsSubmitted());
            assertEquals(1L, helper.getResponsesReceived());
            assertEquals(tests.size(), helper.getTestsPublished());
        } finally {
            helper.shutdown();
            service.close();
        }
    }

    @Test
    void asyncMode_returnsEmptyImmediately_thenDeliversLater() throws Exception {
        LlmStatistics.resetDiagnosticCardCounters();
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
            List<TestChromosome> tests = drainWithTimeout(helper, 10_000);
            assertFalse(tests.isEmpty(),
                    "ASYNC mode should eventually deliver tests via drain()");
            assertEquals(1L, helper.getPromptsSubmitted());
            assertEquals(1L, helper.getResponsesReceived());
            assertEquals(tests.size(), helper.getTestsPublished());
        } finally {
            helper.shutdown();
            service.close();
        }
    }

    @Test
    void asyncMode_reArmsWindowOnCompletion_notOnSubmit() throws Exception {
        // T2: the stagnation window must be re-armed when an ASYNC call
        // *completes*, not when it is submitted. Otherwise a slow call lets
        // the (consumed-at-submit) window elapse again before its results
        // are even available, and the next maybeSubmit fires immediately.
        LlmStatistics.resetDiagnosticCardCounters();
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
        model.enqueue(LlmFeature.STAGNATION, SIMPLE_JUNIT_RESPONSE);
        LlmService service = createService(model, 2);
        AtomicLong clock = new AtomicLong(0L);
        // Threshold = 1s.
        StagnationDetector detector = new StagnationDetector(service, false, 1, 1, clock::get);
        StagnationLlmHelper helper = new StagnationLlmHelper(
                detector, LlmStagnationMode.ASYNC, () -> -1L, 0);

        try {
            TestFitnessFunction goal = makeGoal("g");
            List<TestChromosome> pop = Collections.singletonList(new TestChromosome());

            helper.maybeSubmit(0, Collections.singleton(goal), pop);  // baseline; window starts at t=0
            clock.set(TimeUnit.SECONDS.toNanos(2));
            helper.maybeSubmit(0, Collections.singleton(goal), pop);  // stagnant: submits async (gated)
            assertEquals(1L, helper.getPromptsSubmitted());

            // The window is NOT consumed at submit time: even though the
            // pre-submit window (started at t=0) has long elapsed by t=5,
            // the in-flight call dedups the next submission instead of
            // firing again.
            clock.set(TimeUnit.SECONDS.toNanos(5));
            helper.maybeSubmit(0, Collections.singleton(goal), pop);
            assertEquals(1L, helper.getPromptsSubmitted(),
                    "An in-flight ASYNC call must dedup, not fire a second prompt");

            // Let the call complete at t=5; consumeWindow() re-arms the
            // window from this completion time, not from t=0 or t=2.
            responseGate.countDown();
            List<TestChromosome> tests = drainWithTimeout(helper, 10_000);
            assertFalse(tests.isEmpty(), "ASYNC mode should eventually deliver tests via drain()");

            // Just past completion (t=5.5 < 5+threshold): must not fire again.
            clock.set(TimeUnit.SECONDS.toNanos(5) + TimeUnit.MILLISECONDS.toNanos(500));
            helper.maybeSubmit(0, Collections.singleton(goal), pop);
            assertEquals(1L, helper.getPromptsSubmitted(),
                    "maybeSubmit must not fire before completion + threshold");

            // At completion + threshold (t=6s): fires again, absent improvement.
            clock.set(TimeUnit.SECONDS.toNanos(6));
            helper.maybeSubmit(0, Collections.singleton(goal), pop);
            assertEquals(2L, helper.getPromptsSubmitted(),
                    "maybeSubmit must fire at completion + threshold absent improvement");
        } finally {
            helper.shutdown();
            service.close();
        }
    }

    @Test
    void asyncMode_completionReArm_doesNotFireIfCoveredGoalsIncreaseBeforeThreshold() throws Exception {
        // T2: covered-goal improvement observed after an ASYNC completion
        // resets the window via peekStagnation's existing
        // improvement-detection, so the next fire is measured from the
        // improvement, not from the prior completion.
        LlmStatistics.resetDiagnosticCardCounters();
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
        model.enqueue(LlmFeature.STAGNATION, SIMPLE_JUNIT_RESPONSE);
        LlmService service = createService(model, 2);
        AtomicLong clock = new AtomicLong(0L);
        // Threshold = 1s.
        StagnationDetector detector = new StagnationDetector(service, false, 1, 1, clock::get);
        StagnationLlmHelper helper = new StagnationLlmHelper(
                detector, LlmStagnationMode.ASYNC, () -> -1L, 0);

        try {
            TestFitnessFunction goal = makeGoal("g");
            List<TestChromosome> pop = Collections.singletonList(new TestChromosome());

            helper.maybeSubmit(0, Collections.singleton(goal), pop);  // baseline covered=0, window starts at t=0
            clock.set(TimeUnit.SECONDS.toNanos(2));
            helper.maybeSubmit(0, Collections.singleton(goal), pop);  // stagnant: submits async (gated)
            assertEquals(1L, helper.getPromptsSubmitted());

            // Complete the call at t=2; consumeWindow() re-arms the window to t=2.
            responseGate.countDown();
            List<TestChromosome> tests = drainWithTimeout(helper, 10_000);
            assertFalse(tests.isEmpty());

            // Covered goals increase before completion + threshold (t=2.5 < 3).
            clock.set(TimeUnit.SECONDS.toNanos(2) + TimeUnit.MILLISECONDS.toNanos(500));
            helper.maybeSubmit(1, Collections.singleton(goal), pop);
            assertEquals(1L, helper.getPromptsSubmitted(),
                    "Covered-goal improvement must not itself trigger a call");

            // The OLD completion+threshold (t=3) must NOT fire: the
            // improvement at t=2.5 reset the window, so 3 - 2.5 = 0.5s is
            // still below the 1s threshold.
            clock.set(TimeUnit.SECONDS.toNanos(3));
            helper.maybeSubmit(1, Collections.singleton(goal), pop);
            assertEquals(1L, helper.getPromptsSubmitted(),
                    "Covered-goal improvement after completion must defer the next fire");

            // A full threshold after the improvement (t=3.5) fires.
            clock.set(TimeUnit.SECONDS.toNanos(3) + TimeUnit.MILLISECONDS.toNanos(500));
            helper.maybeSubmit(1, Collections.singleton(goal), pop);
            assertEquals(2L, helper.getPromptsSubmitted(),
                    "maybeSubmit must fire a full threshold after the improvement-driven reset");
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
        // The SYNC arm wraps the LLM call in a Future and waits at most the
        // remaining search budget, even though the normal wait can cover the
        // full prompt+repair chain.
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
    void asyncMode_hardShutdownDropsLateInterruptIgnoringCompletion() throws Exception {
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        CountDownLatch providerReturned = new CountDownLatch(1);
        MockChatLanguageModel model = new MockChatLanguageModel() {
            @Override
            public LlmService.LlmResponse generate(List<LlmMessage> messages, LlmFeature feature) {
                providerEntered.countDown();
                // Deliberately model a provider that ignores cancellation.
                while (true) {
                    try {
                        releaseProvider.await();
                        break;
                    } catch (InterruptedException ignored) {
                        // Keep waiting until the test permits a late completion.
                    }
                }
                providerReturned.countDown();
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
            helper.maybeSubmit(0, Collections.singleton(goal), pop);
            clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
            helper.maybeSubmit(0, Collections.singleton(goal), pop);
            assertTrue(providerEntered.await(1, TimeUnit.SECONDS));

            helper.shutdown();
            releaseProvider.countDown();
            assertTrue(providerReturned.await(1, TimeUnit.SECONDS));
            assertTrue(helper.awaitTermination(2, TimeUnit.SECONDS));

            assertTrue(helper.drain().isEmpty(),
                    "A completion returned after hard shutdown must not be published");
            assertEquals(0L, helper.getResponsesReceived());
            assertEquals(0L, helper.getTestsPublished());
            assertEquals(0L, helper.getCalls());
            assertTrue(detector.peekStagnation(0),
                    "Hard shutdown must not re-arm the stagnation window from a late completion");
        } finally {
            releaseProvider.countDown();
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

    @Test
    void drainPreservesDiagnosticCardAttributionMetadata() {
        // Intent: when the diagnostic-prompt pathway produces card types, those
        // types must flow through prompt -> publishTests -> drained candidates so
        // attribution telemetry can read them via consumeDiagnosticCardTypes.
        // The extractor's card-emission heuristics have their own dedicated
        // coverage in ProblemCardExtractorTest; here we stub buildPrompt to
        // pin the metadata-propagation path without rebuilding the full
        // extractor input (TestCase + Statements + ExecutionTrace mocks).
        Properties.LlmStagnationPromptMode oldPromptMode = Properties.LLM_STAGNATION_PROMPT;
        String oldTargetClass = Properties.TARGET_CLASS;
        try {
            Properties.LLM_STAGNATION_PROMPT = Properties.LlmStagnationPromptMode.DIAGNOSTIC;
            Properties.TARGET_CLASS = "com.example.Foo";

            MockChatLanguageModel model = new MockChatLanguageModel();
            model.enqueue(LlmFeature.STAGNATION, SIMPLE_JUNIT_RESPONSE);
            LlmService service = createService(model, 2);
            AtomicLong clock = new AtomicLong(0L);
            StagnationDetector detector = new StagnationDetector(service, false, 1, 1, clock::get) {
                @Override
                public PromptResult buildPrompt(Collection<TestFitnessFunction> uncoveredGoals,
                                                List<TestChromosome> currentPopulation,
                                                int totalGoals, int coveredGoalCount,
                                                Map<TestFitnessFunction, Double> bestFitnessPerGoal,
                                                boolean suppressInFlightRepeats) {
                    PromptResult base = super.buildPrompt(uncoveredGoals, currentPopulation,
                            totalGoals, coveredGoalCount, bestFitnessPerGoal, suppressInFlightRepeats);
                    return base.toBuilder()
                            .diagnosticCardTypes(Collections.singletonList(ProblemCardType.UNREACHED_METHOD))
                            .build();
                }
            };
            StagnationLlmHelper helper = new StagnationLlmHelper(
                    detector, LlmStagnationMode.SYNC, () -> -1L, 0);
            try {
                TestFitnessFunction goal = makeGoal("g", "com.example.Foo", "doWork()V");
                TestChromosome popEntry = mock(TestChromosome.class);
                org.evosuite.testcase.execution.ExecutionResult result =
                        mock(org.evosuite.testcase.execution.ExecutionResult.class);
                org.evosuite.testcase.execution.ExecutionTrace trace =
                        mock(org.evosuite.testcase.execution.ExecutionTrace.class);
                when(trace.getCoveredMethods()).thenReturn(Collections.singleton("com.example.Foo.other"));
                when(result.getTrace()).thenReturn(trace);
                when(result.hasTimeout()).thenReturn(false);
                when(result.hasTestException()).thenReturn(false);
                when(popEntry.getLastExecutionResult()).thenReturn(result);

                helper.maybeSubmit(0, Collections.singleton(goal), Collections.singletonList(popEntry));
                clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
                helper.maybeSubmit(0, Collections.singleton(goal), Collections.singletonList(popEntry));
                List<TestChromosome> drained = helper.drain();
                assertFalse(drained.isEmpty());
                assertTrue(helper.consumeDiagnosticCardTypes(drained.get(0))
                                .contains(ProblemCardType.UNREACHED_METHOD),
                        "Expected diagnostic card metadata to be available for drained candidates");
            } finally {
                helper.shutdown();
                service.close();
            }
        } finally {
            Properties.LLM_STAGNATION_PROMPT = oldPromptMode;
            Properties.TARGET_CLASS = oldTargetClass;
        }
    }

    private static TestFitnessFunction makeGoal(String name) {
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.toString()).thenReturn(name);
        when(goal.getFitness(org.mockito.ArgumentMatchers.any(TestChromosome.class))).thenReturn(1.0);
        return goal;
    }

    private static TestFitnessFunction makeGoal(String name, String className, String methodName) {
        TestFitnessFunction goal = makeGoal(name);
        when(goal.getTargetClass()).thenReturn(className);
        when(goal.getTargetMethod()).thenReturn(methodName);
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
