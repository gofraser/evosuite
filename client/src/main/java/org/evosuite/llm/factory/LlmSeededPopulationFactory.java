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
package org.evosuite.llm.factory;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.llm.LlmBudgetExceededException;
import org.evosuite.llm.LlmCallFailedException;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.prompt.FewShotExampleProvider;
import org.evosuite.llm.prompt.PromptBuilder;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.response.LlmAssertionPolicyResolver;
import org.evosuite.llm.response.RepairResult;
import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testparser.ParseDiagnostic;
import org.evosuite.testparser.ParseResult;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CancellationException;
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
    private final boolean llmStrategyContext;
    private final Queue<TestChromosome> llmSeeds = new ConcurrentLinkedQueue<>();
    private final CompletableFuture<List<TestChromosome>> pendingSeeds;
    private final AtomicBoolean seedsMerged = new AtomicBoolean(false);

    /** Creates a factory using the singleton LLM service and an empty goals supplier. */
    public LlmSeededPopulationFactory(ChromosomeFactory<TestChromosome> fallback) {
        this(fallback, false);
    }

    /** Creates a factory with strategy-context awareness for assertion policy AUTO resolution. */
    public LlmSeededPopulationFactory(ChromosomeFactory<TestChromosome> fallback,
                                      boolean llmStrategyContext) {
        this(fallback, LlmService.getInstance(), Collections::emptyList, ForkJoinPool.commonPool(),
                llmStrategyContext);
    }

    /** Creates a factory with explicit dependencies and an async executor. */
    public LlmSeededPopulationFactory(ChromosomeFactory<TestChromosome> fallback,
                                      LlmService llmService,
                                      Supplier<Collection<TestFitnessFunction>> uncoveredGoalsSupplier,
                                      Executor executor) {
        this(fallback, llmService, uncoveredGoalsSupplier, executor, false);
    }

    /** Creates a factory with explicit dependencies, async executor, and assertion-policy context. */
    public LlmSeededPopulationFactory(ChromosomeFactory<TestChromosome> fallback,
                                      LlmService llmService,
                                      Supplier<Collection<TestFitnessFunction>> uncoveredGoalsSupplier,
                                      Executor executor,
                                      boolean llmStrategyContext) {
        this.fallback = fallback;
        this.llmService = llmService;
        this.uncoveredGoalsSupplier = uncoveredGoalsSupplier == null ? Collections::emptyList : uncoveredGoalsSupplier;
        this.llmStrategyContext = llmStrategyContext;
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
        long waitMillis = Math.max(1L, timeoutMs);
        try {
            List<TestChromosome> produced;
            if (waitForCompletion) {
                produced = pendingSeeds.get(waitMillis, TimeUnit.MILLISECONDS);
            } else {
                produced = pendingSeeds.get();
            }
            mergeProducedSeeds(produced);
        } catch (TimeoutException e) {
            // Commit the decision: we tried, we timed out. Don't reset the
            // merged flag — a retry would just observe a cancelled future and
            // re-log the same failure to the operator.
            pendingSeeds.cancel(true);
            LoggingUtils.getEvoLogger().info(
                    "* LLM seeding timed out after {}ms; cancelled pending task", waitMillis);
            logger.warn("Timed out while waiting for async LLM seeds after {}ms", waitMillis);
        } catch (CancellationException e) {
            // Future was cancelled (e.g. by a previous timeout). Log once and
            // leave seedsMerged=true so subsequent calls short-circuit.
            logger.debug("Async LLM seeds future was cancelled before merge");
        } catch (InterruptedException e) {
            seedsMerged.set(false);
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LoggingUtils.getEvoLogger().info(
                    "* LLM seeding failed: {}", e.getMessage());
            logger.warn("Could not merge async LLM seeds: {}", e.getMessage());
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
        if (Thread.currentThread().isInterrupted()) {
            LoggingUtils.getEvoLogger().info("* LLM seeding skipped: thread interrupted before start");
            return Collections.emptyList();
        }
        if (!llmService.isAvailable()) {
            LoggingUtils.getEvoLogger().info("* LLM seeding skipped: service is not available "
                    + "(check LLM provider configuration and JDK compiler availability)");
            return Collections.emptyList();
        }
        if (!llmService.hasBudget()) {
            LoggingUtils.getEvoLogger().info("* LLM seeding skipped: call budget exhausted");
            return Collections.emptyList();
        }
        PromptResult prompt = buildPrompt();
        try {
            String response = llmService.query(prompt, LlmFeature.SEEDING);
            if (Thread.currentThread().isInterrupted()) {
                LoggingUtils.getEvoLogger().info("* LLM seeding interrupted after LLM query");
                return Collections.emptyList();
            }
            RepairResult repairResult = org.evosuite.llm.response.TestRepairLoop
                    .createDefault(llmService,
                            TestRepairLoop.RepairOptions.forAssertionPolicy(
                                    LlmAssertionPolicyResolver.keepAssertions(llmStrategyContext)))
                    .attemptParse(response, prompt.getMessages(), LlmFeature.SEEDING);
            if (!repairResult.isSuccess()) {
                LoggingUtils.getEvoLogger().info(
                        "* LLM seeding failed after {} attempt(s)",
                        repairResult.getAttemptsUsed());
                for (String diag : repairResult.getDiagnostics()) {
                    LoggingUtils.getEvoLogger().info("*   Diagnostic: {}", diag);
                    logger.warn("  LLM repair diagnostic: {}", diag);
                }
                if (repairResult.getParseResults() != null) {
                    for (ParseResult pr : repairResult.getParseResults()) {
                        for (ParseDiagnostic d : pr.getDiagnostics()) {
                            LoggingUtils.getEvoLogger().info(
                                    "*   Parse {}: {} (Line {})",
                                    d.getSeverity(), d.getMessage(), d.getLineNumber());
                        }
                    }
                }
                return Collections.emptyList();
            }
            List<TestChromosome> seeds = repairResult.toChromosomes();
            if (seeds.isEmpty()) {
                LoggingUtils.getEvoLogger().info(
                        "* LLM seeding: repair succeeded but produced 0 chromosomes");
            }
            return seeds;
        } catch (LlmBudgetExceededException | LlmCallFailedException e) {
            LoggingUtils.getEvoLogger().info("* LLM seeding unavailable: {}", e.getMessage());
            logger.warn("LLM seeding unavailable: {}", e.getMessage());
            return Collections.emptyList();
        } catch (Error e) {
            if (isRecoverableSeedingError(e)) {
                LoggingUtils.getEvoLogger().info("* LLM seeding unavailable: {}", e.getMessage());
                logger.warn("LLM seeding unavailable due to recoverable linkage/runtime error: {}", e.getMessage());
                return Collections.emptyList();
            }
            throw e;
        } catch (RuntimeException e) {
            LoggingUtils.getEvoLogger().info("* LLM seeding failed: {}", e.getMessage());
            logger.warn("LLM seeding failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private PromptResult buildPrompt() {
        Collection<TestFitnessFunction> goals = uncoveredGoalsSupplier.get();
        PromptBuilder builder = new PromptBuilder()
                .withSystemPrompt()
                .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withTestClusterContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withFewShotSnippets(FewShotExampleProvider.collectSnippetsIfFewShot(goals, null))
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE)
                .withInstruction("Generate as many JUnit test methods for the target class as necessary. "
                        + "Focus on diverse paths, edge cases, and branch coverage."
                        + LlmAssertionPolicyResolver.instructionSuffix(llmStrategyContext));
        if (goals != null && !goals.isEmpty()) {
            builder.withUncoveredGoals(goals);
        }
        return builder.buildWithMetadata();
    }

    private static boolean isRecoverableSeedingError(Throwable throwable) {
        // LinkageError already covers VerifyError, NoClassDefFoundError,
        // ClassFormatError, UnsupportedClassVersionError, and friends.
        return throwable instanceof LinkageError || throwable instanceof TypeNotPresentException;
    }

}
