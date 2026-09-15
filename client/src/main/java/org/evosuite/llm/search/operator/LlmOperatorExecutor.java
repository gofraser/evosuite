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

import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.response.TargetedResponse;
import org.evosuite.llm.search.InjectionAttemptMetadata;
import org.evosuite.llm.search.LlmPrivilegedThreads;
import org.evosuite.llm.search.LlmSemanticCrossover;
import org.evosuite.llm.search.LlmSemanticMutation;
import org.evosuite.llm.search.LlmSemanticOperator;
import org.evosuite.llm.search.ProblemCardType;
import org.evosuite.llm.search.RepeatedInjectionMemory;
import org.evosuite.llm.search.RepeatedInjectionTarget;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Background executor for the LLM semantic mutation/crossover operators in
 * {@code ASYNC} mode. {@link #submit} renders each batch's prompt on the
 * calling (search) thread via {@link LlmSemanticOperator#prepare} — that step
 * reads parent chromosome state and must not race the GA — then hands the
 * batch to a worker thread that only calls {@link LlmSemanticOperator#complete}
 * and parses the response. Workers never execute a test case: the resulting
 * {@link TestChromosome}s are queued and evaluated later by the GA, when it
 * calls {@link #drain()} on the search thread.
 */
public final class LlmOperatorExecutor {

    private static final Logger logger = LoggerFactory.getLogger(LlmOperatorExecutor.class);

    private final LlmSemanticMutation mutation;
    private final LlmSemanticCrossover crossover;
    private final RepeatedInjectionMemory memory;
    private final LongSupplier remainingSearchSecondsSupplier;
    private final int budgetGuardSeconds;
    private final int maxInFlight;
    private final int batchSize;
    private final ExecutorService pool;

    private final ConcurrentLinkedQueue<Map.Entry<TestChromosome, InjectionAttemptMetadata>> queue =
            new ConcurrentLinkedQueue<>();
    private final Map<TestChromosome, InjectionAttemptMetadata> pendingMetadata = new IdentityHashMap<>();
    private final Set<String> openAttemptIds = ConcurrentHashMap.newKeySet();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger workerCounter = new AtomicInteger();
    private volatile boolean shutDown = false;

    private final AtomicLong submittedCalls = new AtomicLong();
    private final AtomicLong droppedBudget = new AtomicLong();
    private final AtomicLong droppedInFlight = new AtomicLong();
    private final AtomicLong droppedTime = new AtomicLong();
    private final AtomicLong producedCandidates = new AtomicLong();
    private final AtomicLong inFlightGenerations = new AtomicLong();

    public LlmOperatorExecutor(LlmSemanticMutation mutation, LlmSemanticCrossover crossover,
                               RepeatedInjectionMemory memory,
                               LongSupplier remainingSearchSecondsSupplier,
                               int budgetGuardSeconds, int maxInFlight, int batchSize) {
        this.mutation = mutation;
        this.crossover = crossover;
        this.memory = memory;
        this.remainingSearchSecondsSupplier = remainingSearchSecondsSupplier == null
                ? () -> -1L : remainingSearchSecondsSupplier;
        this.budgetGuardSeconds = Math.max(0, budgetGuardSeconds);
        this.maxInFlight = Math.max(1, maxInFlight);
        this.batchSize = Math.max(1, batchSize);
        this.pool = Executors.newFixedThreadPool(this.maxInFlight, r -> {
            Thread t = new Thread(r, "LLM-Operator-" + workerCounter.incrementAndGet());
            t.setDaemon(true);
            LlmPrivilegedThreads.registerAsPrivileged(t, t.getName());
            return t;
        });
    }

    /**
     * Groups {@code targets} into homogeneous batches (never mixing mutation
     * and crossover targets) and submits one call per batch, in order,
     * stopping at the first in-flight cap, budget, or time-guard refusal.
     *
     * @return the number of calls actually submitted
     */
    public int submit(List<OperatorTarget> targets) {
        if (shutDown || targets == null || targets.isEmpty()) {
            return 0;
        }
        int submitted = 0;
        for (List<OperatorTarget> batch : groupIntoBatches(targets)) {
            if (inFlight.get() >= maxInFlight) {
                droppedInFlight.incrementAndGet();
                break;
            }
            LlmSemanticOperator operator = batch.get(0).getKind() == OperatorTarget.Kind.CROSSOVER
                    ? crossover : mutation;
            if (!operator.canCall()) {
                droppedBudget.incrementAndGet();
                break;
            }
            if (budgetGuardSeconds > 0) {
                long remaining = remainingSearchSecondsSupplier.getAsLong();
                if (remaining >= 0 && remaining < budgetGuardSeconds) {
                    droppedTime.incrementAndGet();
                    break;
                }
            }
            if (submitBatch(operator, batch)) {
                submitted++;
            }
        }
        return submitted;
    }

    private List<List<OperatorTarget>> groupIntoBatches(List<OperatorTarget> targets) {
        List<List<OperatorTarget>> batches = new ArrayList<>();
        List<OperatorTarget> current = null;
        OperatorTarget.Kind currentKind = null;
        for (OperatorTarget target : targets) {
            if (target == null) {
                continue;
            }
            if (current == null || target.getKind() != currentKind || current.size() >= batchSize) {
                current = new ArrayList<>();
                batches.add(current);
                currentKind = target.getKind();
            }
            current.add(target);
        }
        return batches;
    }

    /** Renders the prompt and registers the attempt on the calling thread, then submits the call. */
    private boolean submitBatch(LlmSemanticOperator operator, List<OperatorTarget> batch) {
        PromptResult prompt;
        try {
            prompt = operator.prepare(batch);
        } catch (RuntimeException e) {
            logger.debug("LLM operator prompt rendering failed: {}", e.getMessage());
            return false;
        }
        List<RepeatedInjectionTarget> repeatedTargets = new ArrayList<>(batch.size());
        OperatorBatchAttribution attribution = new OperatorBatchAttribution(batch.size());
        for (OperatorTarget target : batch) {
            repeatedTargets.add(target.toRepeatedInjectionTarget());
            attribution.add(target);
        }
        RepeatedInjectionMemory.PromptAttemptRegistration registration = memory == null
                ? RepeatedInjectionMemory.PromptAttemptRegistration.empty()
                : memory.registerAttempt(repeatedTargets, true);
        String attemptId = registration.getAttemptId();
        if (!attemptId.isEmpty()) {
            openAttemptIds.add(attemptId);
        }

        inFlight.incrementAndGet();
        try {
            pool.execute(() -> runBatch(operator, prompt, attemptId, attribution));
        } catch (RuntimeException e) {
            inFlight.decrementAndGet();
            closeAttempt(attemptId, 0);
            return false;
        }
        submittedCalls.incrementAndGet();
        return true;
    }

    /** Worker-thread body: makes the LLM call, then publishes the resulting candidates. */
    private void runBatch(LlmSemanticOperator operator, PromptResult prompt, String attemptId,
                          OperatorBatchAttribution attribution) {
        try {
            TargetedResponse mapped = operator.complete(prompt, attribution.size());
            if (shutDown) {
                return; // shutdown() releases this attempt; do not publish or close it here.
            }
            publish(mapped, attemptId, attribution);
        } catch (RuntimeException e) {
            logger.debug("LLM operator call failed: {}", e.getMessage());
            if (!shutDown) {
                closeAttempt(attemptId, 0);
            }
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private void publish(TargetedResponse mapped, String attemptId, OperatorBatchAttribution attribution) {
        if (mapped == null || mapped.isEmpty()) {
            closeAttempt(attemptId, 0);
            return;
        }
        int produced = 0;
        for (int i = 1; i <= attribution.size(); i++) {
            TestCase test = mapped.forTarget(i).orElse(null);
            if (test == null) {
                continue;
            }
            OperatorBatchAttribution.TargetSnapshot snapshot = attribution.snapshot(i - 1);
            List<ProblemCardType> cardTypes = snapshot.getCardType() == null
                    ? Collections.<ProblemCardType>emptyList()
                    : Collections.singletonList(snapshot.getCardType());
            InjectionAttemptMetadata metadata = new InjectionAttemptMetadata(
                    attemptId, cardTypes, Collections.singletonList(snapshot.getGoal()));
            if (!offer(newChromosome(test, snapshot.getLineages()), metadata)) {
                return;
            }
            produced++;
        }
        for (TestCase test : mapped.unmatched()) {
            InjectionAttemptMetadata metadata = new InjectionAttemptMetadata(
                    attemptId, attribution.getCardTypes(), new LinkedHashSet<>(attribution.getGoals()));
            if (!offer(newChromosome(test, attribution.getLineages()), metadata)) {
                return;
            }
            produced++;
        }
        producedCandidates.addAndGet(produced);
        if (produced == 0) {
            closeAttempt(attemptId, 0);
        }
    }

    private static TestChromosome newChromosome(TestCase test, Set<Long> lineages) {
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(test);
        chromosome.setChanged(true);
        chromosome.addDescentLineages(lineages);
        return chromosome;
    }

    /** Queues one candidate/metadata pair, discarding it silently once shut down. */
    private boolean offer(TestChromosome chromosome, InjectionAttemptMetadata metadata) {
        if (shutDown) {
            return false;
        }
        queue.add(new AbstractMap.SimpleImmutableEntry<>(chromosome, metadata));
        return true;
    }

    private void closeAttempt(String attemptId, int gainedGoals) {
        if (attemptId == null || attemptId.isEmpty()) {
            return;
        }
        openAttemptIds.remove(attemptId);
        if (memory != null) {
            memory.recordAttemptOutcome(attemptId, gainedGoals);
        }
    }

    /** Drains every candidate produced so far. Empty once shut down. */
    public List<TestChromosome> drain() {
        if (shutDown) {
            return Collections.emptyList();
        }
        List<TestChromosome> result = new ArrayList<>();
        Map.Entry<TestChromosome, InjectionAttemptMetadata> entry;
        while ((entry = queue.poll()) != null) {
            pendingMetadata.put(entry.getKey(), entry.getValue());
            result.add(entry.getKey());
        }
        return result;
    }

    /** Removes and returns the attribution metadata for a candidate drawn from {@link #drain()}. */
    public InjectionAttemptMetadata consumeAttemptMetadata(TestChromosome candidate) {
        return candidate == null ? null : pendingMetadata.remove(candidate);
    }

    /** Reports how many goals a previously produced candidate ended up covering; closes its attempt. */
    public void reportAttemptOutcome(String attemptId, int gainedGoals) {
        closeAttempt(attemptId, gainedGoals);
    }

    /** Whether another call could be submitted right now (capacity left, not shut down). */
    public boolean hasCapacity() {
        return !shutDown && inFlight.get() < maxInFlight;
    }

    public int getInFlight() { return inFlight.get(); }

    /** Call once per generation; counts a generation during which at least one call was in flight. */
    public void tickGeneration() {
        if (inFlight.get() > 0) {
            inFlightGenerations.incrementAndGet();
        }
    }

    public long getSubmittedCalls() { return submittedCalls.get(); }
    public long getDroppedBudget() { return droppedBudget.get(); }
    public long getDroppedInFlight() { return droppedInFlight.get(); }
    public long getDroppedTime() { return droppedTime.get(); }
    public long getProducedCandidates() { return producedCandidates.get(); }
    public long getInFlightGenerations() { return inFlightGenerations.get(); }

    /**
     * Stops accepting work, cancels in-flight calls, and releases every attempt
     * that was still open (i.e. a worker never got to publish or close it)
     * rather than letting it linger and suppress future selection. Idempotent.
     */
    public void shutdown() {
        shutDown = true;
        pool.shutdownNow();
        try {
            pool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (memory != null) {
            for (String attemptId : openAttemptIds) {
                memory.releaseUndeliveredAttempt(attemptId);
            }
        }
        openAttemptIds.clear();
        queue.clear();
        pendingMetadata.clear();
    }
}
