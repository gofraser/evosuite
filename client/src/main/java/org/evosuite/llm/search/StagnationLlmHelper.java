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
import org.evosuite.llm.LlmStatistics;
import org.evosuite.llm.LlmWaitBudget;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Wraps {@link StagnationDetector} and dispatches stagnation-triggered LLM
 * calls either synchronously (blocking the GA evolve loop) or asynchronously
 * (on a background worker thread). The choice is controlled by
 * {@link Properties#LLM_STAGNATION_MODE}; the {@link #maybeSubmit} and
 * {@link #drain} entry points have identical contracts in both modes, so the
 * caller is mode-agnostic and the two arms can be compared head-to-head.
 *
 * <p>Per-call metrics are emitted as {@link RuntimeVariable} totals so the
 * cost of blocking (vs. async drift) is visible in statistics output.
 */
public final class StagnationLlmHelper {

    private static final Logger logger = LoggerFactory.getLogger(StagnationLlmHelper.class);

    private final StagnationDetector detector;
    private final LlmStagnationMode mode;
    private final LongSupplier remainingBudgetSecondsSupplier;
    private final int budgetGuardSeconds;

    /**
     * When the guard is left at -1, use a conservative late-run floor to avoid
     * launching stagnation calls that are likely to be interrupted by search
     * shutdown before any response can be consumed.
     */
    private static final int DEFAULT_BUDGET_GUARD_SECONDS = 45;
    private static final long SYNC_RETURN_MARGIN_NANOS = TimeUnit.SECONDS.toNanos(2);

    private final BlockingQueue<TestChromosome> resultQueue;
    private final Map<TestChromosome, InjectionAttemptMetadata> attemptMetadataByCandidate;
    private final ExecutorService worker;
    private final AtomicReference<Future<?>> inFlight = new AtomicReference<>();
    private volatile boolean shutdown = false;

    private final AtomicLong stagnationCalls = new AtomicLong();
    private final AtomicLong stagnationLatencyMsTotal = new AtomicLong();
    private final AtomicLong stagnationBlockedMsTotal = new AtomicLong();
    private final AtomicLong stagnationInFlightGenerations = new AtomicLong();
    private final AtomicLong stagnationSkippedBudget = new AtomicLong();
    private final AtomicLong stagnationSkippedInFlight = new AtomicLong();
    private final AtomicLong stagnationPromptsSubmitted = new AtomicLong();
    private final AtomicLong stagnationResponsesReceived = new AtomicLong();
    private final AtomicLong stagnationTestsPublished = new AtomicLong();

    public StagnationLlmHelper(StagnationDetector detector,
                               LongSupplier remainingBudgetSecondsSupplier) {
        this(detector,
                Properties.LLM_STAGNATION_MODE,
                remainingBudgetSecondsSupplier,
                resolveBudgetGuardSeconds());
    }

    StagnationLlmHelper(StagnationDetector detector,
                        LlmStagnationMode mode,
                        LongSupplier remainingBudgetSecondsSupplier,
                        int budgetGuardSeconds) {
        if (detector == null) {
            throw new IllegalArgumentException("detector must not be null");
        }
        this.detector = detector;
        this.mode = mode == null ? LlmStagnationMode.ASYNC : mode;
        this.remainingBudgetSecondsSupplier = remainingBudgetSecondsSupplier == null
                ? () -> -1L : remainingBudgetSecondsSupplier;
        this.budgetGuardSeconds = Math.max(0, budgetGuardSeconds);
        // Stagnation accepts all parsed tests from the LLM response, including
        // over-production beyond the requested count.
        this.resultQueue = new LinkedBlockingQueue<>();
        this.attemptMetadataByCandidate = Collections.synchronizedMap(new IdentityHashMap<>());
        // A single-thread daemon executor is allocated for both modes. ASYNC
        // submits and returns immediately; SYNC submits and waits with a
        // truncated timeout so the call can be interrupted via Future.cancel
        // if it would otherwise outlast the search budget.
        String threadName = "llm-stagnation-" + this.mode.name().toLowerCase();
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            // Register with the sandbox so test execution from the worker
            // doesn't get sandboxed and corrupt MSecurityManager state.
            // See AsyncLlmTestProducer for the original justification.
            LlmPrivilegedThreads.registerAsPrivileged(t, threadName);
            return t;
        });
    }

    private static int resolveBudgetGuardSeconds() {
        int configured = Properties.LLM_STAGNATION_BUDGET_GUARD_SECONDS;
        if (configured >= 0) {
            return configured;
        }
        // Conservative floor: avoid firing prompts in the final seconds where
        // the call is likely to be interrupted by end-of-search teardown.
        // The per-call wait in runSync still caps actual blocking at
        // min(timeout, remaining). ASYNC drops late results via shutdown().
        return DEFAULT_BUDGET_GUARD_SECONDS;
    }

    public LlmStagnationMode getMode() {
        return mode;
    }

    /**
     * Inspects stagnation state and, if triggered, runs the LLM call now (SYNC)
     * or submits it to the background worker (ASYNC). Non-blocking in ASYNC,
     * blocking-for-up-to-{@code llm_timeout_seconds × (1 + repairAttempts)} (worst case, full repair chain)
     * in SYNC. The {@code populationSnapshot} should be a defensive copy: it
     * is read from the worker thread in ASYNC mode.
     */
    public void maybeSubmit(int coveredCount,
                            Collection<TestFitnessFunction> uncoveredGoals,
                            List<TestChromosome> populationSnapshot) {
        if (shutdown) {
            return;
        }
        if (uncoveredGoals == null || uncoveredGoals.isEmpty()) {
            return;
        }
        // Peek (non-consuming): we only reset the stagnation window once we have
        // committed to actually dispatching an LLM call. Skipping for budget,
        // availability, or an in-flight call must NOT swallow the window —
        // otherwise a tight budget guard or a long-running call can prevent
        // stagnation from ever firing.
        int uncoveredSize = uncoveredGoals.size();
        if (!detector.peekStagnation(coveredCount)) {
            if (mode == LlmStagnationMode.ASYNC) {
                tickInFlightGeneration();
            }
            return;
        }
        if (!detector.getLlmService().isAvailable() || !detector.getLlmService().hasBudget()) {
            traceOutcome("skipped:unavailable", coveredCount, uncoveredSize, -1L, 0);
            return;
        }
        if (!hasEnoughTimeLeft()) {
            stagnationSkippedBudget.incrementAndGet();
            ClientServices.track(RuntimeVariable.LLM_StagnationSkippedBudget,
                    stagnationSkippedBudget.get());
            traceOutcome("skipped:budget", coveredCount, uncoveredSize, -1L, 0);
            return;
        }
        if (mode == LlmStagnationMode.ASYNC) {
            Future<?> current = inFlight.get();
            if (current != null && !current.isDone()) {
                stagnationSkippedInFlight.incrementAndGet();
                ClientServices.track(RuntimeVariable.LLM_StagnationSkippedInFlight,
                        stagnationSkippedInFlight.get());
                tickInFlightGeneration();
                traceOutcome("skipped:in_flight", coveredCount, uncoveredSize, -1L, 0);
                return;
            }
        }
        // Committed: consume the stagnation window now.
        detector.consumeWindow();
        recordPromptSubmitted();
        traceOutcome("fired:" + mode.name().toLowerCase(), coveredCount, uncoveredSize, -1L, 0);

        int totalGoals = coveredCount + uncoveredGoals.size();
        Map<TestFitnessFunction, Double> bestFitness =
                StagnationDetector.computeBestFitnessPerGoal(uncoveredGoals, populationSnapshot);
        PromptResult prompt = detector.buildPrompt(uncoveredGoals, populationSnapshot,
                totalGoals, coveredCount, bestFitness, mode == LlmStagnationMode.ASYNC);

        switch (mode) {
            case SYNC:
                runSync(prompt, coveredCount, uncoveredSize);
                break;
            case ASYNC:
                runAsync(prompt);
                break;
        }
    }

    /**
     * Removes and returns any LLM-produced tests that are ready. Non-blocking.
     * In SYNC mode this typically returns the tests of the call that just
     * completed; in ASYNC mode it may be empty for many generations and then
     * return a batch.
     */
    public List<TestChromosome> drain() {
        List<TestChromosome> out = new ArrayList<>();
        resultQueue.drainTo(out);
        return out;
    }

    /**
     * Soft-stop: refuses new submissions but lets any in-flight async call
     * complete and publish its results. After this returns, callers may
     * still {@link #drain()} those results. Use
     * {@link #awaitTermination(long, java.util.concurrent.TimeUnit)} to wait
     * for the in-flight call to finish, then {@link #shutdown()} to release
     * the worker. In SYNC mode there is nothing in flight by the time the
     * caller can invoke this (runSync is synchronous), so it just sets the
     * "no more submissions" flag and quiesces the worker.
     */
    public void stopAcceptingSubmissions() {
        shutdown = true;
        // shutdown() (not shutdownNow()) — keeps any in-flight task running.
        worker.shutdown();
    }

    /**
     * Waits up to {@code timeout} for the worker to finish any in-flight call
     * after {@link #stopAcceptingSubmissions()}. Returns {@code true} if the
     * worker terminated within the timeout; {@code false} if the timeout
     * elapsed first.
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return worker.awaitTermination(timeout, unit);
    }

    /**
     * Hard-stop: cancels any in-flight call and forcibly stops the worker.
     * Safe to call multiple times, and safe to call after
     * {@link #stopAcceptingSubmissions()} (in which case it is mostly a
     * cleanup no-op).
     */
    public void shutdown() {
        shutdown = true;
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

    private void runSync(PromptResult prompt, int coveredCount, int uncoveredSize) {
        long t0 = System.nanoTime();
        long waitSeconds = computeSyncWaitSeconds();
        long deadlineNanos = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(waitSeconds)
                - SYNC_RETURN_MARGIN_NANOS;
        RepeatedInjectionMemory.PromptAttemptRegistration registration =
                detector.registerRepeatedAttempt(prompt, false);
        Future<List<TestChromosome>> future;
        try {
            future = worker.submit(() -> detector.executeWithPrompt(prompt, deadlineNanos));
        } catch (RejectedExecutionException e) {
            // Worker shut down between our shutdown-flag check and submit.
            logger.debug("Sync stagnation submission rejected (shutting down): {}",
                    e.getMessage());
            detector.recordRepeatedAttemptOutcome(registration.getAttemptId(), 0);
            return;
        }
        inFlight.set(future);
        List<TestChromosome> tests;
        try {
            tests = future.get(waitSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            // Wall-clock timeout: the call never produced a response — don't penalize
            // the repeated-injection cooldown for what is effectively a non-delivery.
            detector.releaseUndeliveredRepeatedAttempt(registration.getAttemptId());
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            traceOutcome("timeout:sync", coveredCount, uncoveredSize, elapsedMs, 0);
            return;
        } catch (CancellationException e) {
            // Cancelled before completion — treat as non-delivery.
            detector.releaseUndeliveredRepeatedAttempt(registration.getAttemptId());
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            traceOutcome("cancelled:sync", coveredCount, uncoveredSize, elapsedMs, 0);
            return;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            // Interrupt during the wait — non-delivery.
            detector.releaseUndeliveredRepeatedAttempt(registration.getAttemptId());
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            traceOutcome("interrupted:sync", coveredCount, uncoveredSize, elapsedMs, 0);
            return;
        } catch (ExecutionException e) {
            // Worker raised an exception while executing the call — the call was
            // "delivered" in the sense that it reached the LLM service path and
            // failed there, so the cooldown should reflect "no gain" as before.
            Throwable cause = e.getCause();
            logger.debug("Sync stagnation call failed: {}",
                    cause == null ? e.toString() : cause.toString());
            detector.recordRepeatedAttemptOutcome(registration.getAttemptId(), 0);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            traceOutcome("error:sync", coveredCount, uncoveredSize, elapsedMs, 0);
            return;
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        recordResponseReceived();
        recordCallCompleted(elapsedMs, elapsedMs);
        int produced = tests == null ? 0 : tests.size();
        traceOutcome("completed:sync", coveredCount, uncoveredSize, elapsedMs, produced);
        publishTests(tests, prompt, registration);
    }

    private long computeSyncWaitSeconds() {
        return LlmWaitBudget.repairAwareWaitSeconds(remainingBudgetSecondsSupplier);
    }

    private void runAsync(PromptResult prompt) {
        // Tracking the in-flight call with a Future and an isDone() check is
        // sufficient — we don't need to CAS-to-null when the worker completes.
        // The de-dup check in maybeSubmit (current != null && !current.isDone())
        // already treats a completed Future the same as a null one.
        RepeatedInjectionMemory.PromptAttemptRegistration registration =
                detector.registerRepeatedAttempt(prompt, true);
        try {
            inFlight.set(worker.submit(() -> {
                long t0 = System.nanoTime();
                List<TestChromosome> tests;
                boolean responseReceived = false;
                try {
                    tests = detector.executeWithPrompt(prompt);
                    responseReceived = true;
                } catch (Throwable t) {
                    // Swallow Throwable (not just RuntimeException) so that an
                    // OutOfMemoryError or other Error doesn't silently kill the
                    // worker thread with no observable record. Cancellation
                    // (Future.cancel(true)) surfaces here as InterruptedException
                    // — also caught and logged.
                    logger.debug("Async stagnation worker crashed: {}", t.toString());
                    tests = Collections.emptyList();
                }
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
                if (responseReceived) {
                    recordResponseReceived();
                }
                recordCallCompleted(elapsedMs, 0L);
                int produced = tests == null ? 0 : tests.size();
                traceOutcome("completed:async", -1, -1, elapsedMs, produced);
                publishTests(tests, prompt, registration);
            }));
        } catch (RejectedExecutionException e) {
            // Race with shutdown(): the executor was shut down between our
            // shutdown-flag check and submit. Treat as a benign skip.
            logger.debug("Async stagnation submission rejected (shutting down): {}",
                    e.getMessage());
            detector.recordRepeatedAttemptOutcome(registration.getAttemptId(), 0);
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

    private void publishTests(List<TestChromosome> tests,
                              PromptResult prompt,
                              RepeatedInjectionMemory.PromptAttemptRegistration registration) {
        if (tests == null || tests.isEmpty()) {
            detector.recordRepeatedAttemptOutcome(registration.getAttemptId(), 0);
            return;
        }
        List<ProblemCardType> cardTypes = prompt == null
                ? Collections.emptyList() : prompt.getDiagnosticCardTypes();
        int published = 0;
        for (TestChromosome tc : tests) {
            if (tc == null) {
                continue;
            }
            tc.setDiagnosticCardTypes(cardTypes);
            resultQueue.offer(tc);
            attemptMetadataByCandidate.put(tc, new InjectionAttemptMetadata(
                    registration.getAttemptId(),
                    cardTypes == null ? Collections.<ProblemCardType>emptyList()
                            : new ArrayList<>(cardTypes)));
            published++;
        }
        if (published <= 0) {
            detector.recordRepeatedAttemptOutcome(registration.getAttemptId(), 0);
            return;
        }
        ClientServices.track(RuntimeVariable.LLM_StagnationTestsPublished,
                stagnationTestsPublished.addAndGet(published));
        LlmStatistics.recordDiagnosticCandidatesPublished(cardTypes, published);
    }

    /**
     * Returns and clears diagnostic-card attribution for a drained stagnation candidate.
     *
     * <p>Empty when the candidate did not come from a diagnostic prompt.
     */
    public List<ProblemCardType> consumeDiagnosticCardTypes(TestChromosome candidate) {
        InjectionAttemptMetadata metadata = consumeAttemptMetadata(candidate);
        if (metadata != null) {
            return metadata.getDiagnosticCardTypes();
        }
        return candidate == null ? Collections.<ProblemCardType>emptyList() : candidate.getDiagnosticCardTypes();
    }

    public InjectionAttemptMetadata consumeAttemptMetadata(TestChromosome candidate) {
        if (candidate == null) {
            return null;
        }
        return attemptMetadataByCandidate.remove(candidate);
    }

    public void reportAttemptOutcome(String attemptId, int gainedGoals) {
        detector.recordRepeatedAttemptOutcome(attemptId, gainedGoals);
    }

    long getPromptsSubmitted() {
        return stagnationPromptsSubmitted.get();
    }

    long getResponsesReceived() {
        return stagnationResponsesReceived.get();
    }

    long getTestsPublished() {
        return stagnationTestsPublished.get();
    }

    private void tickInFlightGeneration() {
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
     * <pre>stagnation outcome={x} mode={SYNC|ASYNC} covered={n} uncovered={n} latencyMs={n} tests={n}</pre>
     *
     * Cheap (single SLF4J call, guarded by isDebugEnabled).
     */
    private void traceOutcome(String outcome, int coveredCount, int uncoveredSize,
                              long latencyMs, int testsProduced) {
        if (!logger.isDebugEnabled()) {
            return;
        }
        logger.debug("stagnation outcome={} mode={} covered={} uncovered={} latencyMs={} tests={}",
                outcome, mode, coveredCount, uncoveredSize, latencyMs, testsProduced);
    }
}
