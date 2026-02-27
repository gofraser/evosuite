package org.evosuite.llm.search;

import org.evosuite.Properties;
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
import org.evosuite.testparser.TestParser;
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
    private final LlmService llmService;
    private final int refreshInterval;
    private final int delayMs;
    private volatile boolean running = true;

    public AsyncLlmTestProducer(Supplier<Collection<TestFitnessFunction>> uncoveredGoalsSupplier) {
        this(uncoveredGoalsSupplier,
                LlmService.getInstance(),
                Properties.LLM_ASYNC_PRODUCER_QUEUE_SIZE,
                Properties.LLM_ASYNC_PRODUCER_REFRESH_INTERVAL,
                Properties.LLM_ASYNC_PRODUCER_DELAY_MS);
    }

    public AsyncLlmTestProducer(Supplier<Collection<TestFitnessFunction>> uncoveredGoalsSupplier,
                                LlmService llmService,
                                int queueSize,
                                int refreshInterval,
                                int delayMs) {
        this.uncoveredGoalsSupplier = uncoveredGoalsSupplier == null ? Collections::emptyList : uncoveredGoalsSupplier;
        this.llmService = llmService;
        this.testQueue = new ArrayBlockingQueue<>(Math.max(1, queueSize));
        this.refreshInterval = Math.max(1, refreshInterval);
        this.delayMs = Math.max(0, delayMs);
        this.producerThread = new Thread(this::produceLoop, "LLM-AsyncProducer");
        this.producerThread.setDaemon(true);
    }

    public void start() {
        if (!producerThread.isAlive()) {
            producerThread.start();
        }
    }

    public void stop() {
        running = false;
        producerThread.interrupt();
        try {
            producerThread.join(2000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public List<TestChromosome> drainAvailable() {
        List<TestChromosome> tests = new ArrayList<>();
        testQueue.drainTo(tests);
        return tests;
    }

    private void produceLoop() {
        TestRepairLoop repairLoop = new TestRepairLoop(
                llmService,
                TestParser.forSUTWithLlmProvenance(),
                new LlmResponseParser(),
                new ClusterExpansionManager());
        int generatedSinceRefresh = refreshInterval;
        Collection<TestFitnessFunction> currentGoals = Collections.emptyList();

        while (running) {
            if (!llmService.isAvailable() || !llmService.hasBudget()) {
                break;
            }

            if (generatedSinceRefresh >= refreshInterval || currentGoals.isEmpty()) {
                currentGoals = safeGoalsSnapshot();
                generatedSinceRefresh = 0;
                if (currentGoals.isEmpty()) {
                    break;
                }
            }

            PromptResult prompt = new PromptBuilder()
                    .withSystemPrompt()
                    .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                    .withUncoveredGoals(currentGoals)
                    .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE)
                    .withInstruction("Generate one JUnit test that targets one uncovered goal.")
                    .buildWithMetadata();
            try {
                String response = llmService.query(prompt, LlmFeature.ASYNC_PRODUCER);
                RepairResult result = repairLoop.attemptParse(response, prompt.getMessages(), LlmFeature.ASYNC_PRODUCER);
                if (result.isSuccess()) {
                    for (TestChromosome chromosome : toChromosomes(result)) {
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

    private List<TestChromosome> toChromosomes(RepairResult result) {
        List<TestChromosome> chromosomes = new ArrayList<>();
        result.getTestCases().forEach(testCase -> {
            TestChromosome chromosome = new TestChromosome();
            chromosome.setTestCase(testCase);
            chromosomes.add(chromosome);
        });
        return chromosomes;
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
