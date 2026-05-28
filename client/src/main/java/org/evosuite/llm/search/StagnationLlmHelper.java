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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
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
     * Capacity rationale: at most one LLM call is in flight (de-duped by
     * {@link #inFlight}). Each call publishes at most {@code testsPerRequest}
     * tests, and the drain runs every generation in
     * {@code collectExternalCandidates}. Worst case the queue therefore holds
     * one batch of tests between publish and drain. Cap at
     * {@code max(testsPerRequest, MIN_QUEUE_CAPACITY)} so a transient drain
     * skip doesn't immediately drop tests, and log on overflow so a real
     * drain stall is observable.
     */
    private static final int MIN_QUEUE_CAPACITY = 16;
    private final BlockingQueue<TestChromosome> resultQueue;
    private final ExecutorService worker;                  // null in SYNC mode
    private final AtomicReference<Future<?>> inFlight = new AtomicReference<>();
    private volatile boolean shutdown = false;

    private final AtomicLong stagnationCalls = new AtomicLong();
    private final AtomicLong stagnationLatencyMsTotal = new AtomicLong();
    private final AtomicLong stagnationBlockedMsTotal = new AtomicLong();
    private final AtomicLong stagnationInFlightGenerations = new AtomicLong();
    private final AtomicLong stagnationSkippedBudget = new AtomicLong();
    private final AtomicLong stagnationSkippedInFlight = new AtomicLong();

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
        int capacity = Math.max(MIN_QUEUE_CAPACITY, detector.getTestsPerRequest());
        this.resultQueue = new ArrayBlockingQueue<>(capacity);
        if (this.mode == LlmStagnationMode.ASYNC) {
            this.worker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "llm-stagnation-async");
                t.setDaemon(true);
                // Register with the sandbox so test execution from the worker
                // doesn't get sandboxed and corrupt MSecurityManager state.
                // See AsyncLlmTestProducer for the original justification.
                LlmPrivilegedThreads.registerAsPrivileged(t, "llm-stagnation-async");
                return t;
            });
        } else {
            this.worker = null;
        }
    }

    private static int resolveBudgetGuardSeconds() {
        int configured = Properties.LLM_STAGNATION_BUDGET_GUARD_SECONDS;
        if (configured >= 0) {
            return configured;
        }
        // Default guard = per-call timeout × (1 + repair attempts), the
        // worst-case wall-clock cost of a single stagnation call in SYNC mode
        // (one initial query plus up to LLM_REPAIR_ATTEMPTS repair turns).
        // Using just LLM_TIMEOUT_SECONDS would let calls overrun the remaining
        // search budget by up to LLM_REPAIR_ATTEMPTS × LLM_TIMEOUT_SECONDS.
        int timeout = Math.max(0, Properties.LLM_TIMEOUT_SECONDS);
        int repairAttempts = Math.max(0, Properties.LLM_REPAIR_ATTEMPTS);
        long worstCase = (long) timeout * (1L + repairAttempts);
        return (int) Math.min(worstCase, Integer.MAX_VALUE);
    }

    public LlmStagnationMode getMode() {
        return mode;
    }

    /**
     * Inspects stagnation state and, if triggered, runs the LLM call now (SYNC)
     * or submits it to the background worker (ASYNC). Non-blocking in ASYNC,
     * blocking-for-up-to-{@code llm_timeout_seconds × (1 + repairAttempts)}
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
        traceOutcome("fired:" + mode.name().toLowerCase(), coveredCount, uncoveredSize, -1L, 0);

        int totalGoals = coveredCount + uncoveredGoals.size();
        Map<TestFitnessFunction, Double> bestFitness =
                StagnationDetector.computeBestFitnessPerGoal(uncoveredGoals, populationSnapshot);
        PromptResult prompt = detector.buildPrompt(uncoveredGoals, populationSnapshot,
                totalGoals, coveredCount, bestFitness);

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
     * the worker. SYNC mode has no in-flight state, so this is just a
     * "no more submissions" flag.
     */
    public void stopAcceptingSubmissions() {
        shutdown = true;
        if (worker != null) {
            // shutdown() (not shutdownNow()) — keeps the in-flight task running.
            worker.shutdown();
        }
    }

    /**
     * Waits up to {@code timeout} for the ASYNC worker to finish any in-flight
     * call after {@link #stopAcceptingSubmissions()}. Returns {@code true} if
     * the worker terminated within the timeout (or is SYNC-only, i.e. nothing
     * to wait for); {@code false} if the timeout elapsed first.
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        if (worker == null) {
            return true;
        }
        return worker.awaitTermination(timeout, unit);
    }

    /**
     * Hard-stop: cancels any in-flight async call and forcibly stops the
     * worker. Safe to call multiple times, and safe to call after
     * {@link #stopAcceptingSubmissions()} (in which case it is mostly a
     * cleanup no-op).
     */
    public void shutdown() {
        shutdown = true;
        Future<?> current = inFlight.getAndSet(null);
        if (current != null) {
            current.cancel(true);
        }
        if (worker != null) {
            worker.shutdownNow();
            try {
                worker.awaitTermination(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runSync(PromptResult prompt, int coveredCount, int uncoveredSize) {
        long t0 = System.nanoTime();
        List<TestChromosome> tests = detector.executeWithPrompt(prompt);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        recordCallCompleted(elapsedMs, elapsedMs);
        int produced = tests == null ? 0 : tests.size();
        traceOutcome("completed:sync", coveredCount, uncoveredSize, elapsedMs, produced);
        publishTests(tests);
    }

    private void runAsync(PromptResult prompt) {
        // Tracking the in-flight call with a Future and an isDone() check is
        // sufficient — we don't need to CAS-to-null when the worker completes.
        // The de-dup check in maybeSubmit (current != null && !current.isDone())
        // already treats a completed Future the same as a null one.
        try {
            inFlight.set(worker.submit(() -> {
                long t0 = System.nanoTime();
                List<TestChromosome> tests;
                try {
                    tests = detector.executeWithPrompt(prompt);
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
                recordCallCompleted(elapsedMs, 0L);
                int produced = tests == null ? 0 : tests.size();
                traceOutcome("completed:async", -1, -1, elapsedMs, produced);
                publishTests(tests);
            }));
        } catch (RejectedExecutionException e) {
            // Race with shutdown(): the executor was shut down between our
            // shutdown-flag check and submit. Treat as a benign skip.
            logger.debug("Async stagnation submission rejected (shutting down): {}",
                    e.getMessage());
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

    private void publishTests(List<TestChromosome> tests) {
        if (tests == null || tests.isEmpty()) {
            return;
        }
        int dropped = 0;
        for (TestChromosome tc : tests) {
            if (!resultQueue.offer(tc)) {
                dropped++;
            }
        }
        if (dropped > 0) {
            logger.debug("Stagnation result queue full (capacity {}); dropped {} test(s) — "
                    + "drain side stalled?", resultQueue.remainingCapacity() + resultQueue.size(), dropped);
        }
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
