/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.llm.factory;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.llm.LlmBudgetExceededException;
import org.evosuite.llm.LlmCallFailedException;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.prompt.PromptBuilder;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.response.ClusterExpansionManager;
import org.evosuite.llm.response.LlmResponseParser;
import org.evosuite.llm.response.RepairResult;
import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testparser.ParseDiagnostic;
import org.evosuite.testparser.ParseResult;
import org.evosuite.testparser.TestParser;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Asynchronously fetches initial LLM tests while preserving a fallback factory.
 */
public class LlmSeededPopulationFactory implements ChromosomeFactory<TestChromosome> {

    private static final long serialVersionUID = -1785138098554527622L;
    private static final Logger logger = LoggerFactory.getLogger(LlmSeededPopulationFactory.class);

    private final ChromosomeFactory<TestChromosome> fallback;
    private final LlmService llmService;
    private final Supplier<Collection<TestFitnessFunction>> uncoveredGoalsSupplier;
    private final Queue<TestChromosome> llmSeeds = new ConcurrentLinkedQueue<>();
    private final CompletableFuture<List<TestChromosome>> pendingSeeds;
    private final AtomicBoolean seedsMerged = new AtomicBoolean(false);

    /** Creates a factory using the singleton LLM service and an empty goals supplier. */
    public LlmSeededPopulationFactory(ChromosomeFactory<TestChromosome> fallback) {
        this(fallback, LlmService.getInstance(), Collections::emptyList, ForkJoinPool.commonPool());
    }

    /** Creates a factory with explicit dependencies and an async executor. */
    public LlmSeededPopulationFactory(ChromosomeFactory<TestChromosome> fallback,
                                      LlmService llmService,
                                      Supplier<Collection<TestFitnessFunction>> uncoveredGoalsSupplier,
                                      Executor executor) {
        this.fallback = fallback;
        this.llmService = llmService;
        this.uncoveredGoalsSupplier = uncoveredGoalsSupplier == null ? Collections::emptyList : uncoveredGoalsSupplier;
        this.pendingSeeds = CompletableFuture.supplyAsync(this::generateSeeds, executor);
    }

    @Override
    public TestChromosome getChromosome() {
        mergePendingSeeds(false, 0L);
        TestChromosome seeded = llmSeeds.poll();
        if (seeded != null) {
            return seeded;
        }
        return fallback.getChromosome();
    }

    /**
     * Drains seeds from the pending future and waits up to {@code timeoutMs} milliseconds.
     */
    public List<TestChromosome> awaitAndDrainSeeds(long timeoutMs) {
        mergePendingSeeds(true, timeoutMs);
        List<TestChromosome> drained = new ArrayList<>();
        TestChromosome current;
        while ((current = llmSeeds.poll()) != null) {
            drained.add(current);
        }
        return drained;
    }

    private void mergePendingSeeds(boolean waitForCompletion, long timeoutMs) {
        if (!waitForCompletion && !pendingSeeds.isDone()) {
            return;
        }
        if (!seedsMerged.compareAndSet(false, true)) {
            return;
        }
        try {
            List<TestChromosome> produced;
            if (waitForCompletion) {
                long waitMillis = Math.max(1L, timeoutMs);
                produced = pendingSeeds.get(waitMillis, TimeUnit.MILLISECONDS);
            } else {
                produced = pendingSeeds.get();
            }
            mergeProducedSeeds(produced);
        } catch (TimeoutException e) {
            seedsMerged.set(false);
            logger.debug("Timed out while waiting for async LLM seeds");
        } catch (InterruptedException e) {
            seedsMerged.set(false);
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            logger.debug("Could not merge async LLM seeds: {}", e.getMessage());
        }
    }

    private void mergeProducedSeeds(List<TestChromosome> produced) {
        if (produced == null || produced.isEmpty()) {
            LoggingUtils.getEvoLogger().info("* LLM produced 0 valid test chromosomes.");
            return;
        }
        LoggingUtils.getEvoLogger().info("* LLM produced " + produced.size() + " valid test chromosomes.");
        llmSeeds.addAll(produced);
    }

    private List<TestChromosome> generateSeeds() {
        if (!llmService.isAvailable() || !llmService.hasBudget()) {
            return Collections.emptyList();
        }
        int requestedHint = Math.max(1, Properties.LLM_SEED_COUNT);
        PromptResult prompt = buildPrompt(requestedHint);
        try {
            String response = llmService.query(prompt, LlmFeature.SEEDING);
            RepairResult repairResult = createRepairLoop().attemptParse(
                    response, prompt.getMessages(), LlmFeature.SEEDING);
            if (!repairResult.isSuccess()) {
                logger.debug("LLM seeding failed to produce valid tests after repair.");
                if (repairResult.getParseResults() != null) {
                    for (ParseResult pr : repairResult.getParseResults()) {
                        for (ParseDiagnostic d : pr.getDiagnostics()) {
                            LoggingUtils.getEvoLogger().info("* [LLM Parse " + d.getSeverity() + "] "
                                    + d.getMessage() + " (Line " + d.getLineNumber() + ")");
                        }
                    }
                }
                return Collections.emptyList();
            }
            List<TestChromosome> seeds = toChromosomes(repairResult);
            logger.debug("LLM seeding produced {} valid test chromosomes.", seeds.size());
            return seeds;
        } catch (LlmBudgetExceededException | LlmCallFailedException e) {
            logger.debug("LLM seeding unavailable: {}", e.getMessage());
            return Collections.emptyList();
        } catch (RuntimeException e) {
            logger.debug("LLM seeding failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private PromptResult buildPrompt(int requestedTests) {
        PromptBuilder builder = new PromptBuilder()
                .withSystemPrompt()
                .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE)
                .withInstruction("Generate " + requestedTests + " JUnit test methods for the target class. "
                        + "Focus on diverse paths, edge cases, and branch coverage.");
        Collection<TestFitnessFunction> goals = uncoveredGoalsSupplier.get();
        if (goals != null && !goals.isEmpty()) {
            builder.withUncoveredGoals(goals);
        }
        return builder.buildWithMetadata();
    }

    private TestRepairLoop createRepairLoop() {
        return new TestRepairLoop(
                llmService,
                TestParser.forSUTWithLlmProvenance(),
                new LlmResponseParser(),
                new ClusterExpansionManager());
    }

    private List<TestChromosome> toChromosomes(RepairResult repairResult) {
        List<TestChromosome> chromosomes = new ArrayList<>();
        repairResult.getTestCases().stream()
                .forEach(testCase -> {
                    TestChromosome chromosome = new TestChromosome();
                    chromosome.setTestCase(testCase);
                    chromosomes.add(chromosome);
                });
        return chromosomes;
    }
}
