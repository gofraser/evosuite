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
import org.evosuite.llm.LlmBudgetExceededException;
import org.evosuite.llm.LlmCallFailedException;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.LlmStatistics;
import org.evosuite.llm.prompt.CoverageGoalFormatter;
import org.evosuite.llm.prompt.FewShotExampleProvider;
import org.evosuite.llm.prompt.GoalDescriptionMapper;
import org.evosuite.llm.prompt.PromptBuilder;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.prompt.TestRelevanceRanker;
import org.evosuite.llm.response.LlmAssertionPolicyResolver;
import org.evosuite.llm.response.RepairResult;
import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.rmi.ClientServices;
import org.evosuite.setup.TestCluster;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Background producer that continuously requests LLM-generated tests.
 */
public class AsyncLlmTestProducer {

    private static final Logger logger = LoggerFactory.getLogger(AsyncLlmTestProducer.class);

    private final BlockingQueue<TestChromosome> testQueue;
    private final Thread producerThread;
    private final Supplier<Collection<TestFitnessFunction>> uncoveredGoalsSupplier;
    private final Supplier<List<TestChromosome>> populationSupplier;
    private final LlmService llmService;
    private final int refreshInterval;
    private final int delayMs;
    private final RepeatedInjectionMemory repeatedInjectionMemory;
    private final GoalDescriptionMapper goalDescriptionMapper = new GoalDescriptionMapper();
    private final ProblemCardExtractor problemCardExtractor = new ProblemCardExtractor();
    private final DiagnosticCardSelector diagnosticCardSelector = new DiagnosticCardSelector();
    private final ExceptionBarrierTracker exceptionBarrierTracker = new ExceptionBarrierTracker();
    private final Map<TestChromosome, InjectionAttemptMetadata> attemptMetadataByCandidate =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private final AtomicLong diagnosticCallsAttempted = new AtomicLong();
    private final AtomicLong diagnosticCardsUsed = new AtomicLong();
    private final AtomicLong loopIterations = new AtomicLong();
    private final AtomicReference<StopReason> stoppedReason =
            new AtomicReference<>(StopReason.NONE);
    private volatile boolean running = true;
    private static final int GOALS_PER_PROMPT = 3;
    private static final int ASYNC_DIAGNOSTIC_PROBLEM_CARDS_MAX = 2;
    private static final int CONFIRMED_EMPTY_SNAPSHOTS_BEFORE_STOP = 3;
    private static final int MIN_SNAPSHOT_RETRY_DELAY_MS = 10;
    private static final int MAX_SNAPSHOT_RETRY_DELAY_MS = 1000;

    public enum StopReason {
        NONE,
        STOP_REQUESTED,
        INTERRUPTED,
        LLM_UNAVAILABLE,
        BUDGET_EXHAUSTED,
        CONFIRMED_NO_GOALS
    }

    /** Creates a producer using singleton LLM service and Properties-configured settings. */
    public AsyncLlmTestProducer(Supplier<Collection<TestFitnessFunction>> uncoveredGoalsSupplier) {
        this(uncoveredGoalsSupplier,
                null,
                LlmService.getInstance(),
                Properties.LLM_ASYNC_PRODUCER_QUEUE_SIZE,
                Properties.LLM_ASYNC_PRODUCER_REFRESH_INTERVAL,
                Properties.LLM_ASYNC_PRODUCER_DELAY_MS,
                null);
    }

    /** Creates a producer with a population supplier for existing-test context. */
    public AsyncLlmTestProducer(Supplier<Collection<TestFitnessFunction>> uncoveredGoalsSupplier,
                                Supplier<List<TestChromosome>> populationSupplier) {
        this(uncoveredGoalsSupplier,
                populationSupplier,
                LlmService.getInstance(),
                Properties.LLM_ASYNC_PRODUCER_QUEUE_SIZE,
                Properties.LLM_ASYNC_PRODUCER_REFRESH_INTERVAL,
                Properties.LLM_ASYNC_PRODUCER_DELAY_MS,
                null);
    }

    /** Creates a producer with explicit dependencies and configuration. */
    public AsyncLlmTestProducer(Supplier<Collection<TestFitnessFunction>> uncoveredGoalsSupplier,
                                Supplier<List<TestChromosome>> populationSupplier,
                                LlmService llmService,
                                int queueSize,
                                int refreshInterval,
                                int delayMs) {
        this(uncoveredGoalsSupplier, populationSupplier, llmService, queueSize, refreshInterval, delayMs, null);
    }

    public AsyncLlmTestProducer(Supplier<Collection<TestFitnessFunction>> uncoveredGoalsSupplier,
                                Supplier<List<TestChromosome>> populationSupplier,
                                LlmService llmService,
                                int queueSize,
                                int refreshInterval,
                                int delayMs,
                                RepeatedInjectionMemory repeatedInjectionMemory) {
        this.uncoveredGoalsSupplier = uncoveredGoalsSupplier == null ? Collections::emptyList : uncoveredGoalsSupplier;
        this.populationSupplier = populationSupplier;
        this.llmService = llmService;
        this.testQueue = new ArrayBlockingQueue<>(Math.max(1, queueSize));
        this.refreshInterval = Math.max(1, refreshInterval);
        this.delayMs = Math.max(0, delayMs);
        this.repeatedInjectionMemory = repeatedInjectionMemory;
        this.producerThread = new Thread(this::produceLoop, "LLM-AsyncProducer");
        this.producerThread.setDaemon(true);
    }

    /** Starts the background producer thread if not already running. */
    public void start() {
        if (!producerThread.isAlive()) {
            LlmPrivilegedThreads.registerAsPrivileged(producerThread, "async LLM producer");
            producerThread.start();
        }
    }

    /** Stops the producer thread and waits up to 2 seconds for it to terminate. */
    public void stop() {
        recordStopReason(StopReason.STOP_REQUESTED);
        running = false;
        producerThread.interrupt();
        try {
            producerThread.join(2000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public StopReason getStoppedReason() {
        return stoppedReason.get();
    }

    public long getLoopIterations() {
        return loopIterations.get();
    }

    /** Drains and returns all currently queued test chromosomes without blocking. */
    public List<TestChromosome> drainAvailable() {
        List<TestChromosome> tests = new ArrayList<>();
        testQueue.drainTo(tests);
        return tests;
    }

    public InjectionAttemptMetadata consumeAttemptMetadata(TestChromosome candidate) {
        if (candidate == null) {
            return null;
        }
        return attemptMetadataByCandidate.remove(candidate);
    }

    public void reportAttemptOutcome(String attemptId, int gainedGoals) {
        if (repeatedInjectionMemory == null || attemptId == null || attemptId.trim().isEmpty()) {
            return;
        }
        repeatedInjectionMemory.recordAttemptOutcome(attemptId, gainedGoals);
    }

    private void produceLoop() {
        boolean keepAssertions = LlmAssertionPolicyResolver.keepAssertions(false);
        TestRepairLoop repairLoop = TestRepairLoop.createDefault(
                llmService, TestRepairLoop.RepairOptions.forAssertionPolicy(keepAssertions));
        int generatedSinceRefresh = refreshInterval;
        Collection<TestFitnessFunction> currentGoals = Collections.emptyList();
        List<TestCase> currentTests = Collections.emptyList();
        List<ProblemCard> cachedDiagnosticCards = Collections.emptyList();
        int consecutiveConfirmedEmptySnapshots = 0;
        int consecutiveSnapshotFailures = 0;

        while (running) {
            long iterations = loopIterations.incrementAndGet();
            ClientServices.track(RuntimeVariable.LLM_AsyncProducer_LoopIterations, iterations);
            if (!llmService.isAvailable()) {
                recordStopReason(StopReason.LLM_UNAVAILABLE);
                break;
            }
            if (!llmService.hasBudget()) {
                recordStopReason(StopReason.BUDGET_EXHAUSTED);
                break;
            }

            if (generatedSinceRefresh >= refreshInterval || currentGoals.isEmpty()) {
                GoalsSnapshot snapshot = safeGoalsSnapshot();
                if (snapshot.failed()) {
                    consecutiveSnapshotFailures++;
                    sleepSnapshotRetry(consecutiveSnapshotFailures);
                    continue;
                }
                consecutiveSnapshotFailures = 0;
                currentGoals = snapshot.goals();
                if (currentGoals.isEmpty()) {
                    consecutiveConfirmedEmptySnapshots++;
                    if (consecutiveConfirmedEmptySnapshots >= CONFIRMED_EMPTY_SNAPSHOTS_BEFORE_STOP) {
                        recordStopReason(StopReason.CONFIRMED_NO_GOALS);
                        break;
                    }
                    sleepSnapshotRetry(consecutiveConfirmedEmptySnapshots);
                    continue;
                }
                consecutiveConfirmedEmptySnapshots = 0;
                List<TestChromosome> populationChromosomes = safeChromosomeSnapshot();
                currentTests = projectTestCases(populationChromosomes, currentGoals);
                cachedDiagnosticCards = maybeComputeDiagnosticCards(currentGoals, populationChromosomes);
                generatedSinceRefresh = 0;
            }

            PromptResult prompt;
            List<ProblemCardType> cardTypes;
            if (shouldUseDiagnosticPrompt()) {
                ClientServices.track(RuntimeVariable.LLM_AsyncProducer_DiagnosticCalls,
                        diagnosticCallsAttempted.incrementAndGet());
            }
            if (shouldUseDiagnosticPrompt() && !cachedDiagnosticCards.isEmpty()) {
                prompt = buildDiagnosticPromptForAsync(currentGoals, currentTests, cachedDiagnosticCards);
                cardTypes = extractCardTypes(cachedDiagnosticCards);
                ClientServices.track(RuntimeVariable.LLM_AsyncProducer_Cards_Used,
                        diagnosticCardsUsed.addAndGet(cardTypes.size()));
            } else {
                Collection<TestFitnessFunction> promptGoals = selectPromptGoals(currentGoals);
                if (promptGoals.isEmpty()) {
                    generatedSinceRefresh++;
                    sleepDelay();
                    continue;
                }
                prompt = buildPoolPromptForAsync(promptGoals, currentTests);
                cardTypes = Collections.emptyList();
            }
            RepeatedInjectionMemory.PromptAttemptRegistration registration = repeatedInjectionMemory == null
                    ? RepeatedInjectionMemory.PromptAttemptRegistration.empty()
                    : repeatedInjectionMemory.registerAttempt(prompt.getRepeatedInjectionTargets(), true);
            try {
                List<TestChromosome> candidates = requestCandidates(prompt, repairLoop);
                if (!candidates.isEmpty()) {
                    boolean producedAny = false;
                    int published = 0;
                    for (TestChromosome chromosome : candidates) {
                        try {
                            if (!cardTypes.isEmpty()) {
                                chromosome.setDiagnosticCardTypes(cardTypes);
                            }
                            testQueue.put(chromosome);
                            attemptMetadataByCandidate.put(chromosome, new InjectionAttemptMetadata(
                                    registration.getAttemptId(),
                                    cardTypes.isEmpty()
                                            ? Collections.<ProblemCardType>emptyList()
                                            : new ArrayList<>(cardTypes)));
                            producedAny = true;
                            published++;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            recordStopReason(StopReason.INTERRUPTED);
                            if (repeatedInjectionMemory != null && !registration.isEmpty()) {
                                repeatedInjectionMemory.recordAttemptOutcome(registration.getAttemptId(), 0);
                            }
                            running = false;
                            return;
                        }
                    }
                    if (!producedAny && repeatedInjectionMemory != null && !registration.isEmpty()) {
                        repeatedInjectionMemory.recordAttemptOutcome(registration.getAttemptId(), 0);
                    } else if (published > 0 && !cardTypes.isEmpty()) {
                        LlmStatistics.recordDiagnosticCandidatesPublished(cardTypes, published);
                    }
                } else if (repeatedInjectionMemory != null && !registration.isEmpty()) {
                    repeatedInjectionMemory.recordAttemptOutcome(registration.getAttemptId(), 0);
                }
                generatedSinceRefresh++;
                sleepDelay();
            } catch (LlmBudgetExceededException e) {
                if (repeatedInjectionMemory != null && !registration.isEmpty()) {
                    repeatedInjectionMemory.recordAttemptOutcome(registration.getAttemptId(), 0);
                }
                recordStopReason(StopReason.BUDGET_EXHAUSTED);
                break;
            } catch (LlmCallFailedException e) {
                logger.debug("Async LLM producer call failed: {}", e.getMessage());
                if (repeatedInjectionMemory != null && !registration.isEmpty()) {
                    repeatedInjectionMemory.recordAttemptOutcome(registration.getAttemptId(), 0);
                }
                sleepDelay();
            } catch (RuntimeException e) {
                logger.debug("Async LLM producer failed: {}", e.getMessage());
                if (repeatedInjectionMemory != null && !registration.isEmpty()) {
                    repeatedInjectionMemory.recordAttemptOutcome(registration.getAttemptId(), 0);
                }
                sleepDelay();
            }
        }
        if (stoppedReason.get() == StopReason.NONE && !running) {
            recordStopReason(Thread.currentThread().isInterrupted()
                    ? StopReason.INTERRUPTED : StopReason.STOP_REQUESTED);
        }
    }

    /** Queries the LLM and parses candidate tests for one producer iteration. */
    protected List<TestChromosome> requestCandidates(PromptResult prompt, TestRepairLoop repairLoop) {
        String response = llmService.query(prompt, LlmFeature.ASYNC_PRODUCER);
        RepairResult result = repairLoop.attemptParse(
                response, prompt.getMessages(), LlmFeature.ASYNC_PRODUCER);
        return result.isSuccess() ? result.toChromosomes() : Collections.emptyList();
    }

    private boolean shouldUseDiagnosticPrompt() {
        return Properties.LLM_ASYNC_PRODUCER_PROMPT == Properties.LlmAsyncProducerPromptMode.DIAGNOSTIC;
    }

    private List<ProblemCard> maybeComputeDiagnosticCards(Collection<TestFitnessFunction> goals,
                                                          List<TestChromosome> population) {
        if (!shouldUseDiagnosticPrompt() || goals == null || goals.isEmpty()
                || population == null || population.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            Set<String> goalMethods = new LinkedHashSet<>();
            for (TestFitnessFunction goal : goals) {
                if (goal == null) {
                    continue;
                }
                String key = goalDescriptionMapper.describeMethodOperation(goal).getExecutionKey();
                if (!key.isEmpty()) {
                    goalMethods.add(key);
                }
            }
            exceptionBarrierTracker.observe(population, goalMethods);
            Map<String, ExceptionBarrierTracker.AggregatedStats> historicalStats =
                    exceptionBarrierTracker.snapshot(goalMethods);
            ProblemCardExtractor.ExtractionResult extraction =
                    problemCardExtractor.extractWithTelemetry(goals, population,
                            Integer.MAX_VALUE, historicalStats);
            List<ProblemCard> extracted = extraction.getCards();
            if (extracted.isEmpty()) {
                return Collections.emptyList();
            }
            DiagnosticCardSelector.SelectionResult selection = diagnosticCardSelector.select(
                    extracted, ASYNC_DIAGNOSTIC_PROBLEM_CARDS_MAX, repeatedInjectionMemory, true);
            return selection.getSelectedCards();
        } catch (RuntimeException e) {
            logger.debug("Async producer diagnostic-card extraction failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private PromptResult buildPoolPromptForAsync(Collection<TestFitnessFunction> promptGoals,
                                                 List<TestCase> currentTests) {
        PromptBuilder builder = new PromptBuilder()
                .withSystemPrompt()
                .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withUncoveredGoals(promptGoals)
                .withFewShotSnippets(FewShotExampleProvider.collectSnippetsIfFewShot(promptGoals, null))
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE)
                .withInstruction("Generate one JUnit test that targets one uncovered goal."
                        + LlmAssertionPolicyResolver.instructionSuffix(false));
        if (Properties.LLM_ASYNC_PRODUCER_INCLUDE_TESTS && !currentTests.isEmpty()) {
            builder.withExistingTests(currentTests);
        }
        return builder.buildWithMetadata().toBuilder()
                .repeatedInjectionTargets(toRepeatedTargets(promptGoals))
                .build();
    }

    private PromptResult buildDiagnosticPromptForAsync(Collection<TestFitnessFunction> currentGoals,
                                                       List<TestCase> currentTests,
                                                       List<ProblemCard> cards) {
        CoverageGoalFormatter goalFormatter = new CoverageGoalFormatter();
        // Restrict the goals section to the goals related to the selected cards so the prompt
        // stays small. Falls back to the full uncovered-goal set when card relations are empty.
        Set<TestFitnessFunction> cardRelatedGoals = new LinkedHashSet<>();
        for (ProblemCard card : cards) {
            if (card != null) {
                cardRelatedGoals.addAll(card.getRelatedGoals());
            }
        }
        Collection<TestFitnessFunction> goalsForPrompt = cardRelatedGoals.isEmpty()
                ? currentGoals : cardRelatedGoals;
        String goalsSection = goalFormatter.format(goalsForPrompt, null);

        PromptBuilder builder = new PromptBuilder()
                .withSystemPrompt()
                .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withFewShotSnippets(FewShotExampleProvider.collectSnippetsIfFewShot(goalsForPrompt, null))
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE)
                .withInstruction("Generate one focused JUnit test that addresses one of the diagnostic "
                        + "problem cards below while targeting uncovered goals. Prefer compact, "
                        + "executable call sequences over assertions."
                        + LlmAssertionPolicyResolver.instructionSuffix(false))
                .withInstruction("Priority problem cards:\n" + ProblemCardFormatter.format(cards))
                .withInstruction("Uncovered goals:\n" + goalsSection);
        if (Properties.LLM_ASYNC_PRODUCER_INCLUDE_TESTS && !currentTests.isEmpty()) {
            builder.withExistingTests(currentTests);
        }
        return builder.buildWithMetadata().toBuilder()
                .diagnosticCardTypes(extractCardTypes(cards))
                .repeatedInjectionTargets(extractRepeatedTargetsFromCards(cards))
                .build();
    }

    private List<ProblemCardType> extractCardTypes(List<ProblemCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return Collections.emptyList();
        }
        List<ProblemCardType> types = new ArrayList<>(cards.size());
        for (ProblemCard card : cards) {
            if (card != null && card.getType() != null) {
                types.add(card.getType());
            }
        }
        return types;
    }

    private List<RepeatedInjectionTarget> extractRepeatedTargetsFromCards(List<ProblemCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return Collections.emptyList();
        }
        List<RepeatedInjectionTarget> targets = new ArrayList<>(cards.size());
        for (ProblemCard card : cards) {
            if (card != null) {
                targets.add(card.toRepeatedInjectionTarget());
            }
        }
        return targets;
    }

    /** Snapshots the population as detached chromosomes so the extractor can read traces safely. */
    private List<TestChromosome> safeChromosomeSnapshot() {
        if (populationSupplier == null) {
            return Collections.emptyList();
        }
        try {
            List<TestChromosome> pop = populationSupplier.get();
            if (pop == null || pop.isEmpty()) {
                return Collections.emptyList();
            }
            List<TestChromosome> detached = new ArrayList<>(pop.size());
            for (TestChromosome tc : pop) {
                if (tc != null) {
                    detached.add(tc.clone());
                }
            }
            return detached;
        } catch (RuntimeException e) {
            logger.debug("Async producer chromosome snapshot failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Ranks detached chromosomes by relevance and projects to TestCases for existing-test context. */
    private List<TestCase> projectTestCases(List<TestChromosome> detached,
                                            Collection<TestFitnessFunction> goals) {
        if (detached == null || detached.isEmpty()) {
            return Collections.emptyList();
        }
        List<TestChromosome> ranked;
        try {
            ranked = TestRelevanceRanker.rankByRelevance(detached, goals, 2);
        } catch (RuntimeException e) {
            logger.debug("Async producer relevance ranking failed; falling back to first tests: {}",
                    e.getMessage());
            ranked = detached.size() <= 2 ? detached : detached.subList(0, 2);
        }
        List<TestCase> result = new ArrayList<>(ranked.size());
        for (TestChromosome tc : ranked) {
            TestCase test = tc.getTestCase();
            if (test != null) {
                result.add(test);
            }
        }
        return result;
    }

    private Collection<TestFitnessFunction> selectPromptGoals(Collection<TestFitnessFunction> currentGoals) {
        if (currentGoals == null || currentGoals.isEmpty()) {
            return Collections.emptyList();
        }
        List<TestFitnessFunction> goals = new ArrayList<>(currentGoals);
        if (repeatedInjectionMemory == null || goals.size() <= GOALS_PER_PROMPT) {
            return goals;
        }
        goals.sort((left, right) -> Double.compare(goalPriority(right), goalPriority(left)));
        List<TestFitnessFunction> selected = new ArrayList<>();
        for (TestFitnessFunction goal : goals) {
            if (selected.size() >= GOALS_PER_PROMPT) {
                break;
            }
            if (goalPriority(goal) != Double.NEGATIVE_INFINITY) {
                selected.add(goal);
            }
        }
        return selected;
    }

    private double goalPriority(TestFitnessFunction goal) {
        if (goal == null || repeatedInjectionMemory == null) {
            return goal == null ? Double.NEGATIVE_INFINITY : 1.0;
        }
        RepeatedInjectionTarget target = toRepeatedTarget(goal);
        RepeatedInjectionMemory.SelectionAdjustment adjustment =
                repeatedInjectionMemory.adjustPriority(target, 1.0, true);
        return adjustment.isSuppressed() ? Double.NEGATIVE_INFINITY : adjustment.getPriority();
    }

    private List<RepeatedInjectionTarget> toRepeatedTargets(Collection<TestFitnessFunction> goals) {
        if (goals == null || goals.isEmpty()) {
            return Collections.emptyList();
        }
        List<RepeatedInjectionTarget> targets = new ArrayList<>();
        for (TestFitnessFunction goal : goals) {
            RepeatedInjectionTarget target = toRepeatedTarget(goal);
            if (target != null && !target.getKey().isEmpty()) {
                targets.add(target);
            }
        }
        return targets;
    }

    private RepeatedInjectionTarget toRepeatedTarget(TestFitnessFunction goal) {
        if (goal == null) {
            return null;
        }
        GoalDescriptionMapper.OperationTarget methodTarget = goalDescriptionMapper.describeMethodOperation(goal);
        String key = methodTarget.getExecutionKey();
        if (key.isEmpty()) {
            key = goalDescriptionMapper.describe(goal);
        }
        String fingerprint = goal.getClass().getSimpleName() + ":" + goalDescriptionMapper.describe(goal);
        return new RepeatedInjectionTarget(key, fingerprint);
    }

    private GoalsSnapshot safeGoalsSnapshot() {
        try {
            Collection<TestFitnessFunction> goals = uncoveredGoalsSupplier.get();
            if (goals == null || goals.isEmpty()) {
                return GoalsSnapshot.success(Collections.emptyList());
            }
            return GoalsSnapshot.success(new ArrayList<>(goals));
        } catch (RuntimeException e) {
            logger.debug("Async producer goal snapshot failed: {}", e.getMessage());
            return GoalsSnapshot.failure();
        }
    }

    private void recordStopReason(StopReason reason) {
        if (reason != null && reason != StopReason.NONE
                && stoppedReason.compareAndSet(StopReason.NONE, reason)) {
            ClientServices.track(RuntimeVariable.LLM_AsyncProducer_Stopped_Reason, reason.name());
        }
    }

    private void sleepDelay() {
        sleep(delayMs);
    }

    private void sleepSnapshotRetry(int consecutiveAttempts) {
        int exponent = Math.min(6, Math.max(0, consecutiveAttempts - 1));
        long baseDelay = Math.max(MIN_SNAPSHOT_RETRY_DELAY_MS, delayMs);
        long retryDelay = Math.min(MAX_SNAPSHOT_RETRY_DELAY_MS, baseDelay << exponent);
        sleep((int) retryDelay);
    }

    private void sleep(int millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordStopReason(StopReason.INTERRUPTED);
            running = false;
        }
    }

    private static final class GoalsSnapshot {
        private final Collection<TestFitnessFunction> goals;
        private final boolean failed;

        private GoalsSnapshot(Collection<TestFitnessFunction> goals, boolean failed) {
            this.goals = goals;
            this.failed = failed;
        }

        private static GoalsSnapshot success(Collection<TestFitnessFunction> goals) {
            return new GoalsSnapshot(goals, false);
        }

        private static GoalsSnapshot failure() {
            return new GoalsSnapshot(Collections.emptyList(), true);
        }

        private Collection<TestFitnessFunction> goals() {
            return goals;
        }

        private boolean failed() {
            return failed;
        }
    }
}
