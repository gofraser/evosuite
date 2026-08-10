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
import org.evosuite.llm.LlmStatistics;
import org.evosuite.llm.LlmWaitBudget;
import org.evosuite.llm.prompt.FewShotExampleProvider;
import org.evosuite.llm.prompt.PromptBuilder;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.response.LlmAssertionPolicyResolver;
import org.evosuite.llm.response.RepairResult;
import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.statements.Statement;
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
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Asynchronously fetches initial LLM tests while preserving a fallback factory.
 */
public class LlmSeededPopulationFactory implements ChromosomeFactory<TestChromosome>, AutoCloseable {

    private static final long serialVersionUID = -1785138098554527622L;
    private static final Logger logger = LoggerFactory.getLogger(LlmSeededPopulationFactory.class);
    /** Blocking provider I/O must never occupy ForkJoinPool.commonPool workers. */
    private static final ExecutorService LLM_SEED_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "llm-initial-seeding");
        thread.setDaemon(true);
        return thread;
    });

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(LLM_SEED_EXECUTOR::shutdownNow,
                "llm-initial-seeding-shutdown"));
    }

    private final ChromosomeFactory<TestChromosome> fallback;
    private final LlmService llmService;
    private final Supplier<Collection<TestFitnessFunction>> uncoveredGoalsSupplier;
    private final boolean llmStrategyContext;
    private final Queue<TestChromosome> llmSeeds = new ConcurrentLinkedQueue<>();
    private final Set<String> llmSeedKeys = ConcurrentHashMap.newKeySet();
    private final Executor executor;
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean acceptingSeeds = new AtomicBoolean(true);
    private final AtomicBoolean seedsMerged = new AtomicBoolean(false);
    private volatile CompletableFuture<List<TestChromosome>> pendingSeeds;
    private volatile long repairDeadlineNanos = Long.MAX_VALUE;

    /** Creates a factory using the singleton LLM service and an empty goals supplier. */
    public LlmSeededPopulationFactory(ChromosomeFactory<TestChromosome> fallback) {
        this(fallback, false);
    }

    /** Creates a factory with strategy-context awareness for assertion policy AUTO resolution. */
    public LlmSeededPopulationFactory(ChromosomeFactory<TestChromosome> fallback,
                                      boolean llmStrategyContext) {
        this(fallback, LlmService.getInstance(), Collections::emptyList, LLM_SEED_EXECUTOR,
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
        // Callers may omit the executor; older call sites also passed the
        // common pool explicitly.  Both must use the dedicated I/O executor.
        this.executor = executor == null || executor == ForkJoinPool.commonPool()
                ? LLM_SEED_EXECUTOR : executor;
        // The task begins eagerly so seeding can overlap setup, but its
        // deadline is installed first.  awaitAndDrainSeeds() may use a
        // shorter caller wait and cancel the task; it must never be the point
        // at which a repair deadline is first made visible to the worker.
        this.repairDeadlineNanos = computeDeadlineNanos(LlmWaitBudget.repairAwareWaitMillis());
        this.pendingSeeds = CompletableFuture.supplyAsync(this::generateSeeds, this.executor);
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
        long waitMillis = Math.max(1L, timeoutMs);
        mergePendingSeeds(true, timeoutMs);
        List<TestChromosome> drained = new ArrayList<>();
        TestChromosome current;
        while ((current = llmSeeds.poll()) != null) {
            drained.add(current);
        }
        return drained;
    }

    private void mergePendingSeeds(boolean waitForCompletion, long timeoutMs) {
        CompletableFuture<List<TestChromosome>> currentFuture = pendingSeeds;
        if (currentFuture == null) {
            return;
        }
        if (!waitForCompletion && !currentFuture.isDone()) {
            return;
        }
        if (!seedsMerged.compareAndSet(false, true)) {
            return;
        }
        long waitMillis = Math.max(1L, timeoutMs);
        try {
            List<TestChromosome> produced;
            if (waitForCompletion) {
                produced = currentFuture.get(waitMillis, TimeUnit.MILLISECONDS);
            } else {
                produced = currentFuture.get();
            }
            if (acceptingSeeds.get()) {
                mergeProducedSeeds(produced);
            }
        } catch (TimeoutException e) {
            int retained = llmSeeds.size();
            cancel();
            LoggingUtils.getEvoLogger().info(
                    "* LLM seeding timed out after {}ms; cancelled pending task; retaining {} partial seed(s)",
                    waitMillis, retained);
            logger.warn("Timed out while waiting for async LLM seeds after {}ms; retaining {} partial seed(s)",
                    waitMillis, retained);
        } catch (CancellationException e) {
            // Future was cancelled (e.g. by a previous timeout). Log once and
            // leave seedsMerged=true so subsequent calls short-circuit.
            logger.debug("Async LLM seeds future was cancelled before merge");
        } catch (InterruptedException e) {
            cancel();
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LoggingUtils.getEvoLogger().info(
                    "* LLM seeding failed: {}", e.getMessage());
            logger.warn("Could not merge async LLM seeds: {}", e.getMessage());
        }
    }

    /**
     * Stops accepting new LLM seeds while retaining seeds already validated
     * and queued before cancellation.  This is stronger than Future.cancel:
     * CompletableFuture actions can continue after cancellation when a
     * provider ignores interruption.
     */
    public void cancel() {
        synchronized (lifecycleLock) {
            acceptingSeeds.set(false);
            CompletableFuture<List<TestChromosome>> currentFuture = pendingSeeds;
            if (currentFuture != null && !currentFuture.isDone()) {
                currentFuture.cancel(true);
            }
        }
    }

    /** Cancels outstanding seeding work.  Intended for generator lifecycle cleanup. */
    @Override
    public void close() {
        cancel();
    }

    private void mergeProducedSeeds(List<TestChromosome> produced) {
        if (produced == null || produced.isEmpty()) {
            LoggingUtils.getEvoLogger().info("* LLM produced 0 valid test chromosomes.");
            return;
        }
        int accepted = enqueueSeeds(produced);
        if (accepted == produced.size()) {
            LoggingUtils.getEvoLogger().info("* LLM produced " + accepted + " valid test chromosomes.");
        } else {
            LoggingUtils.getEvoLogger().info(
                    "* LLM produced {} valid test chromosomes ({} new after partial retention).",
                    produced.size(), accepted);
        }
    }

    private List<TestChromosome> generateSeeds() {
        if (!acceptingSeeds.get() || Thread.currentThread().isInterrupted()) {
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
            String response = llmService.query(prompt, LlmFeature.SEEDING, repairDeadlineNanos);
            if (!acceptingSeeds.get() || Thread.currentThread().isInterrupted()) {
                LoggingUtils.getEvoLogger().info("* LLM seeding interrupted after LLM query");
                return Collections.emptyList();
            }
            RepairResult repairResult = org.evosuite.llm.response.TestRepairLoop
                    .createDefault(llmService,
                            TestRepairLoop.RepairOptions.forAssertionPolicy(
                                    LlmAssertionPolicyResolver.keepAssertions(llmStrategyContext)))
                    .attemptParse(response, prompt.getMessages(), LlmFeature.SEEDING,
                            repairDeadlineNanos, this::publishPartialSeeds);
            if (!acceptingSeeds.get() || Thread.currentThread().isInterrupted()) {
                return Collections.emptyList();
            }
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
            LlmStatistics.recordInitialPopulationCandidatesValidated(seeds.size());
            if (seeds.isEmpty()) {
                LoggingUtils.getEvoLogger().info(
                        "* LLM seeding: repair succeeded but produced 0 chromosomes");
            }
            return seeds;
        } catch (LlmBudgetExceededException | LlmCallFailedException e) {
            LoggingUtils.getEvoLogger().info("* LLM seeding unavailable: {}", e.getMessage());
            logger.warn("LLM seeding unavailable: {}", e.getMessage());
            return Collections.emptyList();
        } catch (CancellationException e) {
            logger.debug("LLM seeding cancelled during parse/compile validation: {}", e.getMessage());
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

    private void publishPartialSeeds(List<ParseResult> partialResults) {
        if (!acceptingSeeds.get() || partialResults == null || partialResults.isEmpty()) {
            return;
        }
        List<TestChromosome> partialSeeds = new ArrayList<>(partialResults.size());
        for (ParseResult parseResult : partialResults) {
            if (parseResult == null || parseResult.getTestCase() == null) {
                continue;
            }
            TestChromosome chromosome = new TestChromosome();
            chromosome.setTestCase(parseResult.getTestCase());
            partialSeeds.add(chromosome);
        }
        int accepted = enqueueSeeds(partialSeeds);
        if (accepted > 0) {
            LoggingUtils.getEvoLogger().info(
                    "* LLM seeding retained {} partial valid test chromosome(s)", accepted);
        }
    }

    private int enqueueSeeds(List<TestChromosome> seeds) {
        if (seeds == null || seeds.isEmpty()) {
            return 0;
        }
        synchronized (lifecycleLock) {
            if (!acceptingSeeds.get()) {
                return 0;
            }
            int accepted = 0;
            for (TestChromosome seed : seeds) {
                if (seed == null || seed.getTestCase() == null) {
                    continue;
                }
                markParsedFromLlm(seed.getTestCase());
                if (llmSeedKeys.add(fingerprint(seed.getTestCase()))) {
                    llmSeeds.add(seed);
                    accepted++;
                }
            }
            LlmStatistics.recordInitialPopulationCandidatesQueued(accepted);
            return accepted;
        }
    }

    private void markParsedFromLlm(TestCase testCase) {
        for (Statement statement : testCase) {
            statement.setParsedFromLlm(true);
        }
    }

    private String fingerprint(TestCase testCase) {
        try {
            String code = testCase.toCode();
            if (code != null && !code.trim().isEmpty()) {
                return code.replaceAll("\\s+", " ").trim();
            }
        } catch (RuntimeException ignored) {
            // Fall back to identity below if the test cannot be rendered.
        }
        return "id_" + System.identityHashCode(testCase);
    }

    private long computeDeadlineNanos(long waitMillis) {
        long now = System.nanoTime();
        long waitNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, waitMillis));
        if (now > Long.MAX_VALUE - waitNanos) {
            return Long.MAX_VALUE;
        }
        return now + waitNanos;
    }

    private static boolean isRecoverableSeedingError(Throwable throwable) {
        // LinkageError already covers VerifyError, NoClassDefFoundError,
        // ClassFormatError, UnsupportedClassVersionError, and friends.
        return throwable instanceof LinkageError || throwable instanceof TypeNotPresentException;
    }

}
