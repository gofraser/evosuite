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
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testparser.TestParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Detects search stagnation and requests targeted LLM assistance.
 */
public class StagnationDetector {

    private static final Logger logger = LoggerFactory.getLogger(StagnationDetector.class);
    private static final double EPSILON = 1e-12;

    private final LlmService llmService;
    private final boolean maximizationObjective;
    private final int stagnationThreshold;
    private final int testsPerRequest;
    private int stagnantGenerations = 0;
    private Double bestFitness = null;
    private Integer coveredGoals = null;

    /** Creates a detector with singleton LLM service and Properties-configured thresholds. */
    public StagnationDetector() {
        this(LlmService.getInstance(), false, Properties.LLM_STAGNATION_GENERATIONS, Properties.LLM_STAGNATION_TESTS);
    }

    /** Creates a detector with the given maximization flag and Properties-configured thresholds. */
    public StagnationDetector(boolean maximizationObjective) {
        this(LlmService.getInstance(), maximizationObjective,
                Properties.LLM_STAGNATION_GENERATIONS, Properties.LLM_STAGNATION_TESTS);
    }

    /** Creates a detector with explicit LLM service, maximization flag, and threshold settings. */
    public StagnationDetector(LlmService llmService,
                              boolean maximizationObjective,
                              int stagnationThreshold,
                              int testsPerRequest) {
        this.llmService = llmService;
        this.maximizationObjective = maximizationObjective;
        this.stagnationThreshold = Math.max(1, stagnationThreshold);
        this.testsPerRequest = Math.max(1, testsPerRequest);
    }

    /** Checks for stagnation based on the current best fitness value, returning true if stagnation detected. */
    public boolean checkStagnation(double currentBestFitness) {
        if (bestFitness == null) {
            bestFitness = currentBestFitness;
            stagnantGenerations = 0;
            return false;
        }
        boolean improved = maximizationObjective
                ? currentBestFitness > (bestFitness + EPSILON)
                : currentBestFitness < (bestFitness - EPSILON);
        if (improved) {
            bestFitness = currentBestFitness;
            stagnantGenerations = 0;
            return false;
        }
        stagnantGenerations++;
        if (stagnantGenerations >= stagnationThreshold) {
            stagnantGenerations = 0;
            return true;
        }
        return false;
    }

    /** Checks for stagnation based on the current covered goals count, returning true if stagnation detected. */
    public boolean checkStagnation(int currentCoveredGoals) {
        if (coveredGoals == null) {
            coveredGoals = currentCoveredGoals;
            stagnantGenerations = 0;
            return false;
        }
        if (currentCoveredGoals > coveredGoals) {
            coveredGoals = currentCoveredGoals;
            stagnantGenerations = 0;
            return false;
        }
        stagnantGenerations++;
        if (stagnantGenerations >= stagnationThreshold) {
            stagnantGenerations = 0;
            return true;
        }
        return false;
    }

    /** Requests LLM-generated tests targeting uncovered goals when the search has stagnated. */
    public List<TestChromosome> requestHelp(Collection<TestFitnessFunction> uncoveredGoals,
                                            List<TestChromosome> currentPopulation) {
        if (!llmService.isAvailable() || !llmService.hasBudget()) {
            return Collections.emptyList();
        }
        if (uncoveredGoals == null || uncoveredGoals.isEmpty()) {
            return Collections.emptyList();
        }

        PromptResult prompt = buildPrompt(uncoveredGoals, currentPopulation);
        try {
            String response = llmService.query(prompt, LlmFeature.STAGNATION);
            RepairResult result = createRepairLoop().attemptParse(
                    response, prompt.getMessages(), LlmFeature.STAGNATION);
            if (!result.isSuccess()) {
                return Collections.emptyList();
            }
            return toChromosomes(result, testsPerRequest);
        } catch (LlmBudgetExceededException | LlmCallFailedException e) {
            logger.debug("Stagnation LLM request failed: {}", e.getMessage());
            return Collections.emptyList();
        } catch (RuntimeException e) {
            logger.debug("Stagnation LLM request crashed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public void reset() {
        stagnantGenerations = 0;
    }

    private PromptResult buildPrompt(Collection<TestFitnessFunction> uncoveredGoals,
                                         List<TestChromosome> currentPopulation) {
        PromptBuilder builder = new PromptBuilder()
                .withSystemPrompt()
                .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withUncoveredGoals(uncoveredGoals)
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE)
                .withInstruction("The evolutionary search stagnated with no improvement for several generations. "
                        + "Generate " + testsPerRequest + " JUnit tests targeting the uncovered goals.");

        List<TestCase> existingTests = new ArrayList<>();
        if (currentPopulation != null) {
            currentPopulation.stream()
                    .limit(3)
                    .map(TestChromosome::getTestCase)
                    .forEach(existingTests::add);
        }
        if (!existingTests.isEmpty()) {
            builder.withExistingTests(existingTests);
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

    private List<TestChromosome> toChromosomes(RepairResult repairResult, int maxCount) {
        List<TestChromosome> chromosomes = new ArrayList<>();
        repairResult.getTestCases().stream()
                .limit(Math.max(0, maxCount))
                .forEach(testCase -> {
                    TestChromosome chromosome = new TestChromosome();
                    chromosome.setTestCase(testCase);
                    chromosomes.add(chromosome);
                });
        return chromosomes;
    }
}
