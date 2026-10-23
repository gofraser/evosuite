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
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmStatistics;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Wraps {@link StagnationDetector} and dispatches stagnation-triggered LLM
 * calls on a background worker thread, so the GA keeps evolving while a call
 * is in flight. {@link #maybeSubmit} submits a call when the search has
 * stagnated and {@link #drain} returns the tests of calls that have finished.
 *
 * <p>Per-call metrics are emitted as {@link RuntimeVariable} totals.
 *
 * <p><b>Stagnation cadence:</b> the detector's stagnation window is
 * re-armed when a stagnation call <i>completes</i>, not when it is
 * submitted. The window stays armed
 * (pending) for the whole in-flight duration of a call and is only reset
 * once that call's results are computed and published, regardless of
 * whether it produced any tests. This keeps stagnation reactive:
 * successive calls require a fresh {@code llm_stagnation_timeout_seconds}
 * of no covered-goal improvement measured after the previous call's
 * results were integrated, rather than degenerating into a fixed-interval
 * producer driven by call latency. Contrast with {@link AsyncLlmTestProducer}
 * (continuous async injection), whose fixed-interval cadence is unaffected.
 */
public final class StagnationLlmHelper {

    private static final Logger logger = LoggerFactory.getLogger(StagnationLlmHelper.class);

    private final StagnationDetector detector;
    private final LongSupplier remainingBudgetSecondsSupplier;
    private final int budgetGuardSeconds;

    /**
     * When the guard is left at -1, use a conservative late-run floor to avoid
     * launching stagnation calls that are likely to be interrupted by search
     * shutdown before any response can be consumed.
     */
    private static final int DEFAULT_BUDGET_GUARD_SECONDS = 45;

    private final BlockingQueue<AttributedCandidate> resultQueue;
    /** Serializes completion publication with drain() and hard shutdown. */
    private final Object lifecycleLock = new Object();
    private final ExecutorService worker;
    private final AtomicReference<Future<?>> inFlight = new AtomicReference<>();
    private volatile boolean shutdown = false;
    private volatile boolean hardShutdown = false;

    private final AtomicLong stagnationCalls = new AtomicLong();
    private final AtomicLong stagnationLatencyMsTotal = new AtomicLong();
    private final AtomicLong stagnationBlockedMsTotal = new AtomicLong();
    private final AtomicLong stagnationInFlightGenerations = new AtomicLong();
    private final AtomicLong stagnationSkippedBudget = new AtomicLong();
    private final AtomicLong stagnationSkippedInFlight = new AtomicLong();
    private final AtomicLong stagnationPromptsSubmitted = new AtomicLong();
    private final AtomicLong stagnationResponsesReceived = new AtomicLong();
    private final AtomicLong stagnationTestsPublished = new AtomicLong();

    /**
     * Creates a helper using the configured budget guard.
     * @param detector detects stagnation and builds prompts
     * @param remainingBudgetSecondsSupplier supplies the remaining search budget in seconds
     */
    public StagnationLlmHelper(StagnationDetector detector,
                               LongSupplier remainingBudgetSecondsSupplier) {
        this(detector, remainingBudgetSecondsSupplier, resolveBudgetGuardSeconds());
    }

    StagnationLlmHelper(StagnationDetector detector,
                        LongSupplier remainingBudgetSecondsSupplier,
                        int budgetGuardSeconds) {
        if (detector == null) {
            throw new IllegalArgumentException("detector must not be null");
        }
        this.detector = detector;
        this.remainingBudgetSecondsSupplier = remainingBudgetSecondsSupplier == null
                ? () -> -1L : remainingBudgetSecondsSupplier;
        this.budgetGuardSeconds = Math.max(0, budgetGuardSeconds);
        // Stagnation accepts all parsed tests from the LLM response, including
        // over-production beyond the requested count.
        this.resultQueue = new LinkedBlockingQueue<>();
        this.worker = Executors.newSingleThreadExecutor(
                r -> LlmPrivilegedThreads.newThread(r, "stagnation-async"));
    }

    public static int resolveBudgetGuardSeconds() {
        int configured = Properties.LLM_STAGNATION_BUDGET_GUARD_SECONDS;
        if (configured >= 0) {
            return configured;
        }
        // Conservative floor: avoid firing prompts in the final seconds where
        // the call is likely to be interrupted by end-of-search teardown.
        // Late results are dropped via shutdown().
        return DEFAULT_BUDGET_GUARD_SECONDS;
    }

    /**
     * Inspects stagnation state and, if triggered, submits the LLM call to the
     * background worker. Non-blocking. The {@code populationSnapshot} should be
     * a defensive copy: it is read from the worker thread.
     *
     * @param coveredCount       goals covered so far, drives stagnation detection
     * @param totalGoals         all goals, covered or not; only the progress line in the
     *                           prompt uses it, so it may exceed
     *                           {@code coveredCount + targetGoals.size()} when the
     *                           caller narrows the targets to a frontier
     * @param targetGoals        goals the prompt should target
     * @param populationSnapshot defensive copy of the population
     */
    public void maybeSubmit(int coveredCount,
                            int totalGoals,
                            Collection<TestFitnessFunction> targetGoals,
                            List<TestChromosome> populationSnapshot) {
        if (shutdown) {
            return;
        }
        if (targetGoals == null || targetGoals.isEmpty()) {
            return;
        }
        // Peek (non-consuming): we only reset the stagnation window once we have
        // committed to actually dispatching an LLM call. Skipping for budget,
        // availability, or an in-flight call must NOT swallow the window —
        // otherwise a tight budget guard or a long-running call can prevent
        // stagnation from ever firing.
        int uncoveredSize = Math.max(0, totalGoals - coveredCount);
        int targetCount = targetGoals.size();
        if (!detector.peekStagnation(coveredCount)) {
            tickInFlightGeneration();
            return;
        }
        if (!detector.getLlmService().canCall(LlmFeature.STAGNATION)) {
            traceOutcome("skipped:unavailable", coveredCount, uncoveredSize, targetCount, -1L, 0);
            return;
        }
        if (!hasEnoughTimeLeft()) {
            if (hardShutdown) {
                return;
            }
            stagnationSkippedBudget.incrementAndGet();
            ClientServices.track(RuntimeVariable.LLM_StagnationSkippedBudget,
                    stagnationSkippedBudget.get());
            traceOutcome("skipped:budget", coveredCount, uncoveredSize, targetCount, -1L, 0);
            return;
        }
        Future<?> current = inFlight.get();
        if (current != null && !current.isDone()) {
            if (hardShutdown) {
                return;
            }
            stagnationSkippedInFlight.incrementAndGet();
            ClientServices.track(RuntimeVariable.LLM_StagnationSkippedInFlight,
                    stagnationSkippedInFlight.get());
            tickInFlightGeneration();
            traceOutcome("skipped:in_flight", coveredCount, uncoveredSize, targetCount, -1L, 0);
            return;
        }
        // Committed to firing. The window is re-armed when the call *completes*
        // (see runAsync): a ~50s call would otherwise let a 60s window
        // elapse again before its results are even available, degenerating
        // stagnation into a fixed-interval producer.
        synchronized (lifecycleLock) {
            if (hardShutdown) {
                return;
            }
            recordPromptSubmitted();
        }
        traceOutcome("fired:async", coveredCount, uncoveredSize, targetCount, -1L, 0);

        Map<TestFitnessFunction, Double> bestFitness =
                StagnationDetector.computeBestFitnessPerGoal(targetGoals, populationSnapshot);
        PromptResult prompt = detector.buildPrompt(targetGoals, populationSnapshot,
                totalGoals, coveredCount, bestFitness, true);

        runAsync(prompt);
    }

    /**
     * Removes and returns any LLM-produced tests that are ready. Non-blocking.
     * May be empty for many generations and then return a batch. Each test
     * comes with its attribution; the caller now owns the drained tickets.
     */
    public List<AttributedCandidate> drain() {
        List<AttributedCandidate> out = new ArrayList<>();
        synchronized (lifecycleLock) {
            resultQueue.drainTo(out);
        }
        return out;
    }

    /**
     * Hard-stop: cancels any in-flight call and forcibly stops the worker.
     * Safe to call multiple times.
     */
    public void shutdown() {
        synchronized (lifecycleLock) {
            hardShutdown = true;
            shutdown = true;
        }
        Future<?> current = inFlight.getAndSet(null);
        if (current != null) {
            current.cancel(true);
        }
        worker.shutdownNow();
        try {
            worker.awaitTermination(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runAsync(PromptResult prompt) {
        // Tracking the in-flight call with a Future and an isDone() check is
        // sufficient — we don't need to CAS-to-null when the worker completes.
        // The de-dup check in maybeSubmit (current != null && !current.isDone())
        // already treats a completed Future the same as a null one.
        synchronized (lifecycleLock) {
            if (hardShutdown) {
                return;
            }
            AttemptTicket ticket = detector.registerRepeatedAttempt(prompt, true);
            try {
                inFlight.set(worker.submit(() -> {
                    long t0 = System.nanoTime();
                    StagnationDetector.CallResult result;
                    try {
                        result = detector.executeWithPrompt(prompt);
                    } catch (Throwable t) {
                        // Swallow Throwable (not just RuntimeException) so that an
                        // OutOfMemoryError or other Error doesn't silently kill the
                        // worker thread with no observable record. Cancellation
                        // (Future.cancel(true)) surfaces here as InterruptedException
                        // — also caught and logged.
                        logger.debug("Async stagnation worker crashed: {}", t.toString());
                        result = StagnationDetector.CallResult.UNDELIVERED;
                    }
                    // Future.cancel(true) only requests interruption.  A provider
                    // can ignore it and return later, so hard shutdown is checked
                    // after the provider call and again while holding the lifecycle
                    // lock before mutating detector state, metrics, or queues.
                    synchronized (lifecycleLock) {
                        if (hardShutdown) {
                            return;
                        }
                        // Re-arm the stagnation window now that this call has
                        // completed, regardless of whether it produced any tests:
                        // the next ASYNC submission requires a fresh stagnation
                        // period measured from this point, not from submit time
                        // (T2). A failed/empty call still re-arms — it does not
                        // leave the window permanently consumed.
                        detector.consumeWindow();
                        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
                        if (result.isDelivered()) {
                            recordResponseReceived();
                        }
                        recordCallCompleted(elapsedMs, 0L);
                        traceOutcome("completed:async", -1, -1, -1, elapsedMs, result.getTests().size());
                        publishResult(result, prompt, ticket);
                    }
                }));
            } catch (RejectedExecutionException e) {
                // Race with shutdown(): the executor was shut down between our
                // shutdown-flag check and submit. Treat as a benign skip.
                logger.debug("Async stagnation submission rejected (shutting down): {}",
                        e.getMessage());
                if (!hardShutdown) {
                    ticket.release();
                }
            }
        }
    }

    private void recordCallCompleted(long latencyMs, long blockedMs) {
        stagnationCalls.incrementAndGet();
        stagnationLatencyMsTotal.addAndGet(Math.max(0, latencyMs));
        stagnationBlockedMsTotal.addAndGet(Math.max(0, blockedMs));
        ClientServices.track(RuntimeVariable.LLM_StagnationCalls, stagnationCalls.get());
        ClientServices.track(RuntimeVariable.LLM_StagnationLatencyMsTotal,
                stagnationLatencyMsTotal.get());
        ClientServices.track(RuntimeVariable.LLM_StagnationBlockedMsTotal,
                stagnationBlockedMsTotal.get());
    }

    private void recordPromptSubmitted() {
        ClientServices.track(RuntimeVariable.LLM_StagnationPromptsSubmitted,
                stagnationPromptsSubmitted.incrementAndGet());
    }

    private void recordResponseReceived() {
        ClientServices.track(RuntimeVariable.LLM_StagnationResponsesReceived,
                stagnationResponsesReceived.incrementAndGet());
    }

    /**
     * Publishes a delivered call's tests; a call that never delivered a response
     * just releases its attempt, since there is no outcome to judge.
     */
    private void publishResult(StagnationDetector.CallResult result, PromptResult prompt, AttemptTicket ticket) {
        if (hardShutdown) {
            return;
        }
        if (result.isDelivered()) {
            publishTests(result.getTests(), prompt, ticket);
        } else {
            ticket.release();
        }
    }

    private void publishTests(List<TestChromosome> tests, PromptResult prompt, AttemptTicket ticket) {
        if (hardShutdown) {
            return;
        }
        if (tests == null || tests.isEmpty()) {
            ticket.close(0);
            return;
        }
        List<ProblemCardType> cardTypes = prompt == null
                ? Collections.emptyList() : prompt.getDiagnosticCardTypes();
        Collection<TestFitnessFunction> targetGoals = ProblemCard.relatedGoals(
                prompt == null ? null : prompt.getDiagnosticCards());
        int published = 0;
        for (TestChromosome tc : tests) {
            if (tc == null) {
                continue;
            }
            tc.setDiagnosticCardTypes(cardTypes);
            InjectionAttemptMetadata metadata = new InjectionAttemptMetadata(
                    ticket,
                    cardTypes == null ? Collections.<ProblemCardType>emptyList()
                            : new ArrayList<>(cardTypes),
                    targetGoals);
            if (hardShutdown) {
                return;
            }
            resultQueue.offer(new AttributedCandidate(tc, metadata));
            published++;
        }
        if (published <= 0) {
            ticket.close(0);
            return;
        }
        ClientServices.track(RuntimeVariable.LLM_StagnationTestsPublished,
                stagnationTestsPublished.addAndGet(published));
        LlmStatistics.recordDiagnosticCandidatesPublished(cardTypes, published);
    }

    long getPromptsSubmitted() {
        return stagnationPromptsSubmitted.get();
    }

    long getCalls() {
        return stagnationCalls.get();
    }

    long getResponsesReceived() {
        return stagnationResponsesReceived.get();
    }

    long getTestsPublished() {
        return stagnationTestsPublished.get();
    }

    private void tickInFlightGeneration() {
        if (hardShutdown) {
            return;
        }
        Future<?> current = inFlight.get();
        if (current != null && !current.isDone()) {
            long v = stagnationInFlightGenerations.incrementAndGet();
            ClientServices.track(RuntimeVariable.LLM_StagnationInFlightGenerations, v);
        }
    }

    private boolean hasEnoughTimeLeft() {
        if (budgetGuardSeconds <= 0) {
            return true;
        }
        long remaining = remainingBudgetSecondsSupplier.getAsLong();
        if (remaining < 0) {
            return true;
        }
        return remaining >= budgetGuardSeconds;
    }

    /**
     * One structured debug line per stagnation outcome. Fields that don't
     * apply to the event are passed as {@code -1}. Format is grep-friendly:
     *
     * <pre>stagnation outcome={x} covered={n} uncovered={n} targets={n} latencyMs={n} tests={n}</pre>
     *
     * <p>{@code uncovered} is the true count of goals not yet covered; {@code targets} is how many
     * of them the prompt listed (DynaMOSA narrows this to its frontier). Cheap (single SLF4J call,
     * guarded by isDebugEnabled).
     */
    private void traceOutcome(String outcome, int coveredCount, int uncoveredSize, int targetCount,
                              long latencyMs, int testsProduced) {
        if (!logger.isDebugEnabled()) {
            return;
        }
        logger.debug("stagnation outcome={} covered={} uncovered={} targets={} latencyMs={} tests={}",
                outcome, coveredCount, uncoveredSize, targetCount, latencyMs, testsProduced);
    }
}
