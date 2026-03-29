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
import org.evosuite.llm.prompt.PromptBuilder;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.prompt.TestRelevanceRanker;
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
import java.util.List;
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
    private volatile boolean running = true;

    /** Creates a producer using singleton LLM service and Properties-configured settings. */
    public AsyncLlmTestProducer(Supplier<Collection<TestFitnessFunction>> uncoveredGoalsSupplier) {
        this(uncoveredGoalsSupplier,
                null,
                LlmService.getInstance(),
                Properties.LLM_ASYNC_PRODUCER_QUEUE_SIZE,
                Properties.LLM_ASYNC_PRODUCER_REFRESH_INTERVAL,
                Properties.LLM_ASYNC_PRODUCER_DELAY_MS);
    }

    /** Creates a producer with a population supplier for existing-test context. */
    public AsyncLlmTestProducer(Supplier<Collection<TestFitnessFunction>> uncoveredGoalsSupplier,
                                Supplier<List<TestChromosome>> populationSupplier) {
        this(uncoveredGoalsSupplier,
                populationSupplier,
                LlmService.getInstance(),
                Properties.LLM_ASYNC_PRODUCER_QUEUE_SIZE,
                Properties.LLM_ASYNC_PRODUCER_REFRESH_INTERVAL,
                Properties.LLM_ASYNC_PRODUCER_DELAY_MS);
    }

    /** Creates a producer with explicit dependencies and configuration. */
    public AsyncLlmTestProducer(Supplier<Collection<TestFitnessFunction>> uncoveredGoalsSupplier,
                                Supplier<List<TestChromosome>> populationSupplier,
                                LlmService llmService,
                                int queueSize,
                                int refreshInterval,
                                int delayMs) {
        this.uncoveredGoalsSupplier = uncoveredGoalsSupplier == null ? Collections::emptyList : uncoveredGoalsSupplier;
        this.populationSupplier = populationSupplier;
        this.llmService = llmService;
        this.testQueue = new ArrayBlockingQueue<>(Math.max(1, queueSize));
        this.refreshInterval = Math.max(1, refreshInterval);
        this.delayMs = Math.max(0, delayMs);
        this.producerThread = new Thread(this::produceLoop, "LLM-AsyncProducer");
        this.producerThread.setDaemon(true);
    }

    /** Starts the background producer thread if not already running. */
    public void start() {
        if (!producerThread.isAlive()) {
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

    private void produceLoop() {
        TestRepairLoop repairLoop = TestRepairLoop.createDefault(llmService);
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

            PromptBuilder builder = new PromptBuilder()
                    .withSystemPrompt()
                    .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                    .withUncoveredGoals(currentGoals)
                    .withFewShotSnippets(FewShotExampleProvider.collectSnippetsIfFewShot(currentGoals, null))
                    .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE)
                    .withInstruction("Generate one JUnit test that targets one uncovered goal.");

            if (Properties.LLM_ASYNC_PRODUCER_INCLUDE_TESTS && !currentTests.isEmpty()) {
                builder.withExistingTests(currentTests);
            }

            PromptResult prompt = builder.buildWithMetadata();
            try {
                String response = llmService.query(prompt, LlmFeature.ASYNC_PRODUCER);
                RepairResult result = repairLoop.attemptParse(
                        response, prompt.getMessages(), LlmFeature.ASYNC_PRODUCER);
                if (result.isSuccess()) {
                    for (TestChromosome chromosome : result.toChromosomes()) {
                        testQueue.offer(chromosome);
                    }
                }
                generatedSinceRefresh++;
                sleepDelay();
            } catch (LlmBudgetExceededException e) {
                break;
            } catch (LlmCallFailedException e) {
                logger.debug("Async LLM producer call failed: {}", e.getMessage());
                sleepDelay();
            } catch (RuntimeException e) {
                logger.debug("Async LLM producer failed: {}", e.getMessage());
                sleepDelay();
            }
        }
    }

    private Collection<TestFitnessFunction> safeGoalsSnapshot() {
        try {
            Collection<TestFitnessFunction> goals = uncoveredGoalsSupplier.get();
            return goals == null ? Collections.emptyList() : goals;
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
            List<TestChromosome> ranked = TestRelevanceRanker.rankByRelevance(pop, goals, 2);
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
