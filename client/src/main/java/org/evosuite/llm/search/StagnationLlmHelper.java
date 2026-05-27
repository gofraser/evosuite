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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
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

    private final LinkedBlockingQueue<TestChromosome> resultQueue = new LinkedBlockingQueue<>();
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
        if (this.mode == LlmStagnationMode.ASYNC) {
            this.worker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "llm-stagnation-async");
                t.setDaemon(true);
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
        return Math.max(0, Properties.LLM_TIMEOUT_SECONDS);
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
        if (!detector.checkStagnation(coveredCount)) {
            if (mode == LlmStagnationMode.ASYNC) {
                tickInFlightGeneration();
            }
            return;
        }
        if (!detector.getLlmService().isAvailable() || !detector.getLlmService().hasBudget()) {
            return;
        }
        if (!hasEnoughTimeLeft()) {
            stagnationSkippedBudget.incrementAndGet();
            ClientServices.track(RuntimeVariable.LLM_StagnationSkippedBudget,
                    stagnationSkippedBudget.get());
            return;
        }
        if (mode == LlmStagnationMode.ASYNC) {
            Future<?> current = inFlight.get();
            if (current != null && !current.isDone()) {
                stagnationSkippedInFlight.incrementAndGet();
                ClientServices.track(RuntimeVariable.LLM_StagnationSkippedInFlight,
                        stagnationSkippedInFlight.get());
                tickInFlightGeneration();
                return;
            }
        }

        int totalGoals = coveredCount + uncoveredGoals.size();
        Map<TestFitnessFunction, Double> bestFitness =
                StagnationDetector.computeBestFitnessPerGoal(uncoveredGoals, populationSnapshot);
        PromptResult prompt = detector.buildPrompt(uncoveredGoals, populationSnapshot,
                totalGoals, coveredCount, bestFitness);

        switch (mode) {
            case SYNC:
                runSync(prompt);
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

    /** Cancels any in-flight async call and stops the worker. Safe to call multiple times. */
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

    private void runSync(PromptResult prompt) {
        long t0 = System.nanoTime();
        List<TestChromosome> tests = detector.executeWithPrompt(prompt);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        recordCallCompleted(elapsedMs, elapsedMs);
        publishTests(tests);
    }

    private void runAsync(PromptResult prompt) {
        final AtomicReference<Future<?>> selfRef = new AtomicReference<>();
        Future<?> submitted = worker.submit(() -> {
            long t0 = System.nanoTime();
            List<TestChromosome> tests;
            try {
                tests = detector.executeWithPrompt(prompt);
            } catch (RuntimeException e) {
                logger.debug("Async stagnation worker crashed: {}", e.getMessage());
                tests = Collections.emptyList();
            }
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            recordCallCompleted(elapsedMs, 0L);
            publishTests(tests);
            inFlight.compareAndSet(selfRef.get(), null);
        });
        selfRef.set(submitted);
        inFlight.set(submitted);
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
        for (TestChromosome tc : tests) {
            resultQueue.offer(tc);
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
}
