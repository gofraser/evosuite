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
import org.evosuite.llm.prompt.FewShotExampleProvider;
import org.evosuite.llm.prompt.GoalDescriptionMapper;
import org.evosuite.llm.prompt.PromptBuilder;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.prompt.TestRelevanceRanker;
import org.evosuite.llm.response.LlmAssertionPolicyResolver;
import org.evosuite.llm.response.RepairResult;
import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.TestCase;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
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
    private final Map<TestChromosome, InjectionAttemptMetadata> attemptMetadataByCandidate =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private volatile boolean running = true;
    private static final int GOALS_PER_PROMPT = 3;

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
        running = false;
        producerThread.interrupt();
        try {
            producerThread.join(2000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

        while (running) {
            if (!llmService.isAvailable() || !llmService.hasBudget()) {
                break;
            }

            if (generatedSinceRefresh >= refreshInterval || currentGoals.isEmpty()) {
                currentGoals = safeGoalsSnapshot();
                currentTests = safePopulationSnapshot(currentGoals);
                generatedSinceRefresh = 0;
                if (currentGoals.isEmpty()) {
                    break;
                }
            }
            Collection<TestFitnessFunction> promptGoals = selectPromptGoals(currentGoals);
            if (promptGoals.isEmpty()) {
                generatedSinceRefresh++;
                sleepDelay();
                continue;
            }

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

            PromptResult prompt = builder.buildWithMetadata().toBuilder()
                    .repeatedInjectionTargets(toRepeatedTargets(promptGoals))
                    .build();
            RepeatedInjectionMemory.PromptAttemptRegistration registration = repeatedInjectionMemory == null
                    ? RepeatedInjectionMemory.PromptAttemptRegistration.empty()
                    : repeatedInjectionMemory.registerAttempt(prompt.getRepeatedInjectionTargets(), true);
            try {
                String response = llmService.query(prompt, LlmFeature.ASYNC_PRODUCER);
                RepairResult result = repairLoop.attemptParse(
                        response, prompt.getMessages(), LlmFeature.ASYNC_PRODUCER);
                if (result.isSuccess()) {
                    boolean producedAny = false;
                    for (TestChromosome chromosome : result.toChromosomes()) {
                        try {
                            testQueue.put(chromosome);
                            attemptMetadataByCandidate.put(chromosome, new InjectionAttemptMetadata(
                                    registration.getAttemptId(), Collections.<ProblemCardType>emptyList()));
                            producedAny = true;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            if (repeatedInjectionMemory != null && !registration.isEmpty()) {
                                repeatedInjectionMemory.recordAttemptOutcome(registration.getAttemptId(), 0);
                            }
                            running = false;
                            return;
                        }
                    }
                    if (!producedAny && repeatedInjectionMemory != null && !registration.isEmpty()) {
                        repeatedInjectionMemory.recordAttemptOutcome(registration.getAttemptId(), 0);
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

    private Collection<TestFitnessFunction> safeGoalsSnapshot() {
        try {
            Collection<TestFitnessFunction> goals = uncoveredGoalsSupplier.get();
            if (goals == null || goals.isEmpty()) {
                return Collections.emptyList();
            }
            return new ArrayList<>(goals);
        } catch (RuntimeException e) {
            logger.debug("Async producer goal snapshot failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Snapshots up to 2 relevant tests from the population supplier. */
    private List<TestCase> safePopulationSnapshot(Collection<TestFitnessFunction> goals) {
        if (populationSupplier == null) {
            return Collections.emptyList();
        }
        try {
            List<TestChromosome> pop = populationSupplier.get();
            if (pop == null || pop.isEmpty()) {
                return Collections.emptyList();
            }

            // Work on detached chromosomes to avoid mutating/reading live GA chromosomes
            // from this background thread (which can race with GA fitness iteration).
            List<TestChromosome> detached = new ArrayList<>(pop.size());
            for (TestChromosome tc : pop) {
                if (tc != null) {
                    detached.add(tc.clone());
                }
            }
            if (detached.isEmpty()) {
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
        } catch (RuntimeException e) {
            logger.debug("Async producer population snapshot failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void sleepDelay() {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
