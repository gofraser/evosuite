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
 * batch to a worker thread that only calls {@link
 * LlmSemanticOperator#completeDeferringExpansion} and parses the response.
 * Workers never execute a test case, nor expand the shared test cluster: a
 * response whose parse needs cluster expansion is queued unfinished and
 * completed by {@link LlmSemanticOperator#reparseWithExpansion} when {@link
 * #drain()} runs on the search thread, the only thread allowed to touch the
 * cluster. The resulting {@link TestChromosome}s are queued and evaluated
 * later by the GA, when it calls {@link #drain()}.
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
    private final ConcurrentLinkedQueue<DeferredResponse> deferredResponses = new ConcurrentLinkedQueue<>();
    private final Map<TestChromosome, InjectionAttemptMetadata> pendingMetadata = new IdentityHashMap<>();
    private final Set<String> openAttemptIds = ConcurrentHashMap.newKeySet();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger workerCounter = new AtomicInteger();
    private volatile boolean shutDown = false;

    private final AtomicLong submittedCalls = new AtomicLong();
    private final AtomicLong droppedBudget = new AtomicLong();
    private final AtomicLong droppedInFlight = new AtomicLong();
    private final AtomicLong droppedTime = new AtomicLong();
    private final AtomicLong failedCalls = new AtomicLong();
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
        this.pool = Executors.newFixedThreadPool(this.maxInFlight, r ->
                LlmPrivilegedThreads.newThread(r, "operator-" + workerCounter.incrementAndGet()));
    }

    /**
     * Picks up to {@code maxCalls} homogeneous batches out of {@code targets} (given in rank
     * order — highest priority first) and submits one call per batch, in rank order, stopping
     * early at the first in-flight cap, budget, or time-guard refusal.
     *
     * <p>{@code targets} is walked once keeping one open batch per {@link OperatorTarget.Kind}: a
     * target joins its kind's open batch, and a batch that reaches {@code batchSize} is sealed.
     * The batches actually submitted are the {@code maxCalls} with the best-ranked first target
     * (sealed batches and any still-open remainder alike) — the rest of {@code targets} is simply
     * not used this generation, not "dropped" in the counted sense of {@link #getDroppedBudget()}
     * etc.
     *
     * @return the number of calls actually submitted
     */
    public int submit(List<OperatorTarget> targets, int maxCalls, int generation) {
        if (shutDown || targets == null || targets.isEmpty() || maxCalls <= 0) {
            return 0;
        }
        int submitted = 0;
        for (List<OperatorTarget> batch : OperatorBatchGrouper.groupIntoBatches(targets, batchSize, maxCalls)) {
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
            if (submitBatch(operator, batch, generation)) {
                submitted++;
            }
        }
        return submitted;
    }

    /** Renders the prompt, registers one attempt per target on the calling thread, then submits the call. */
    private boolean submitBatch(LlmSemanticOperator operator, List<OperatorTarget> batch, int generation) {
        PromptResult prompt;
        try {
            prompt = operator.prepare(batch);
        } catch (RuntimeException e) {
            logger.debug("LLM operator prompt rendering failed: {}", e.getMessage());
            return false;
        }
        OperatorBatchAttribution attribution = new OperatorBatchAttribution(batch.size());
        for (OperatorTarget target : batch) {
            String attemptId = registerAttempt(target);
            attribution.add(target, attemptId);
            OperatorAttemptLog.getInstance().open(attemptId, generation, target, "ASYNC", batch.size());
        }

        inFlight.incrementAndGet();
        try {
            pool.execute(() -> runBatch(operator, prompt, attribution));
        } catch (RuntimeException e) {
            inFlight.decrementAndGet();
            abandon(attribution);
            return false;
        }
        submittedCalls.incrementAndGet();
        return true;
    }

    /** Registers {@code target} as its own attempt and tracks it as open; {@code ""} without memory. */
    private String registerAttempt(OperatorTarget target) {
        if (memory == null) {
            return "";
        }
        String attemptId = memory.registerAttempt(
                Collections.singletonList(target.toRepeatedInjectionTarget()), true).getAttemptId();
        if (!attemptId.isEmpty()) {
            openAttemptIds.add(attemptId);
        }
        return attemptId;
    }

    /** Worker-thread body: makes the LLM call, then publishes or releases the batch's attempts. */
    private void runBatch(LlmSemanticOperator operator, PromptResult prompt, OperatorBatchAttribution attribution) {
        try {
            TargetedResponse response = operator.completeDeferringExpansion(prompt, attribution.size());
            if (shutDown) {
                return; // shutdown() releases these attempts; do not publish or close them here.
            }
            if (response.needsClusterExpansion()) {
                // Only the search thread may expand the shared TestCluster; queue the raw
                // response for drain() to finish there instead. The attempt stays open.
                deferredResponses.add(new DeferredResponse(operator, prompt, attribution,
                        response.getRawResponse(), response.getUsage()));
                return;
            }
            handleResponse(response, attribution);
        } catch (RuntimeException e) {
            logger.debug("LLM operator call failed: {}", e.getMessage());
            if (!shutDown) {
                abandon(attribution);
            }
        } catch (Error e) {
            if (!shutDown) {
                abandon(attribution);
            }
            throw e;
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /** Publishes or releases a completed response's attempts, whether from a worker or a finished deferral. */
    private void handleResponse(TargetedResponse response, OperatorBatchAttribution attribution) {
        switch (response.getStatus()) {
            case NO_BUDGET:
            case FAILED:
                (response.getStatus() == TargetedResponse.Status.NO_BUDGET ? droppedBudget : failedCalls)
                        .incrementAndGet();
                recordBatchResponse(response, attribution);
                release(attribution);
                break;
            case DELIVERED:
            default:
                publish(response, attribution);
                break;
        }
    }

    /** Releases every attempt of {@code attribution} without recording a (mis-attributed) outcome. */
    private void release(OperatorBatchAttribution attribution) {
        for (String attemptId : attribution.getAttemptIds()) {
            if (attemptId.isEmpty()) {
                continue;
            }
            openAttemptIds.remove(attemptId);
            if (memory != null) {
                memory.releaseUndeliveredAttempt(attemptId);
            }
        }
    }

    /** Records a NO_BUDGET/FAILED response against every target of the batch. */
    private static void recordBatchResponse(TargetedResponse response, OperatorBatchAttribution attribution) {
        for (String attemptId : attribution.getAttemptIds()) {
            OperatorAttemptLog.getInstance().recordResponse(attemptId, response.getStatus(), false, false,
                    response.getRepairTurns(), response.getUsage(), null);
        }
    }

    /** Abandons a batch that never got a response: records every target as undelivered, then releases the attempts. */
    private void abandon(OperatorBatchAttribution attribution) {
        for (String attemptId : attribution.getAttemptIds()) {
            OperatorAttemptLog.getInstance().recordUndelivered(attemptId);
        }
        release(attribution);
    }

    private void publish(TargetedResponse mapped, OperatorBatchAttribution attribution) {
        int produced = 0;
        for (int i = 1; i <= attribution.size(); i++) {
            OperatorBatchAttribution.TargetSnapshot snapshot = attribution.snapshot(i - 1);
            String attemptId = snapshot.getAttemptId();
            TestCase test = mapped.forTarget(i).orElse(null);
            OperatorAttemptLog.getInstance().recordResponse(attemptId, mapped.getStatus(),
                    mapped.isParseSucceeded(), test != null, mapped.getRepairTurns(), mapped.getUsage(), test);
            if (test == null) {
                // Target i is not in the response: judged now, not credited to any candidate.
                closeAttempt(attemptId, 0);
                continue;
            }
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
            // Cannot be tied to one target's attempt; carries the batch's card types/goals only.
            InjectionAttemptMetadata metadata = new InjectionAttemptMetadata(
                    "", attribution.getCardTypes(), new LinkedHashSet<>(attribution.getGoals()));
            if (!offer(newChromosome(test, attribution.getLineages()), metadata)) {
                return;
            }
            produced++;
        }
        producedCandidates.addAndGet(produced);
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

    /**
     * Drains every candidate produced so far. Empty once shut down. First finishes any
     * response a worker deferred because parsing it needed the test cluster expanded
     * (see {@link #runBatch}) — this thread is the only one allowed to do that.
     */
    public List<TestChromosome> drain() {
        if (shutDown) {
            return Collections.emptyList();
        }
        finishDeferredResponses();
        List<TestChromosome> result = new ArrayList<>();
        Map.Entry<TestChromosome, InjectionAttemptMetadata> entry;
        while ((entry = queue.poll()) != null) {
            pendingMetadata.put(entry.getKey(), entry.getValue());
            result.add(entry.getKey());
        }
        return result;
    }

    /** Re-parses every deferred response on this (search) thread and publishes or releases it. */
    private void finishDeferredResponses() {
        DeferredResponse deferred;
        while ((deferred = deferredResponses.poll()) != null) {
            try {
                TargetedResponse response = deferred.operator.reparseWithExpansion(
                        deferred.prompt, deferred.rawResponse, deferred.attribution.size(), deferred.usage);
                handleResponse(response, deferred.attribution);
            } catch (RuntimeException e) {
                // The original call was delivered (that is how this response ended up deferred
                // in the first place) and its tokens were spent; a failure while finishing the
                // parse here is a zero-gain delivered outcome for every target, not an
                // undelivered call to release.
                logger.debug("Finishing a deferred LLM operator response failed: {}", e.getMessage());
                try {
                    handleResponse(TargetedResponse.deliveredUnparsed(deferred.usage, deferred.rawResponse),
                            deferred.attribution);
                } catch (RuntimeException fallbackFailure) {
                    logger.debug("Recording the deferred response as delivered-but-unparsed also failed: {}",
                            fallbackFailure.getMessage());
                    abandon(deferred.attribution);
                }
            }
        }
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
    public long getFailedCalls() { return failedCalls.get(); }
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
        // Best-effort ordering: shutDown is set and workers interrupted/awaited above, and
        // runBatch's shutDown check keeps an interrupted worker from publishing into these
        // queues afterwards, but a worker that ignores interruption between its shutDown check
        // and adding to a queue could still race the draining below. Any such straggler is not
        // lost: it stays in openAttemptIds and is picked up by the generic loop that follows.
        //
        // Responses still queued here were delivered (their LLM call returned) but never
        // finished/published, so they are DROPPED with their usage, not released as
        // undelivered.
        DeferredResponse deferred;
        while ((deferred = deferredResponses.poll()) != null) {
            for (String attemptId : deferred.attribution.getAttemptIds()) {
                if (attemptId.isEmpty()) {
                    continue;
                }
                OperatorAttemptLog.getInstance().recordDropped(attemptId, deferred.usage);
                closeAttempt(attemptId, 0);
            }
        }
        // Candidates still queued here were already delivered and parsed (their row already
        // carries a status, e.g. PARSED); they just never made it through drain(), so their
        // attempt is closed with zero gain rather than released as undelivered.
        Map.Entry<TestChromosome, InjectionAttemptMetadata> entry;
        while ((entry = queue.poll()) != null) {
            InjectionAttemptMetadata metadata = entry.getValue();
            if (metadata != null) {
                closeAttempt(metadata.getAttemptId(), 0);
            }
        }
        for (String attemptId : openAttemptIds) {
            OperatorAttemptLog.getInstance().recordUndelivered(attemptId);
            if (memory != null) {
                memory.releaseUndeliveredAttempt(attemptId);
            }
        }
        openAttemptIds.clear();
        queue.clear();
        pendingMetadata.clear();
        deferredResponses.clear();
    }

    /**
     * A worker's response that needed the test cluster expanded before it could finish
     * parsing, waiting for {@link #drain()} to complete it via {@link
     * LlmSemanticOperator#reparseWithExpansion}. Its attempts are left open in {@link
     * #openAttemptIds} until then.
     */
    private static final class DeferredResponse {
        private final LlmSemanticOperator operator;
        private final PromptResult prompt;
        private final OperatorBatchAttribution attribution;
        private final String rawResponse;
        private final TargetedResponse.CallUsage usage;

        DeferredResponse(LlmSemanticOperator operator, PromptResult prompt, OperatorBatchAttribution attribution,
                         String rawResponse, TargetedResponse.CallUsage usage) {
            this.operator = operator;
            this.prompt = prompt;
            this.attribution = attribution;
            this.rawResponse = rawResponse;
            this.usage = usage;
        }
    }
}
