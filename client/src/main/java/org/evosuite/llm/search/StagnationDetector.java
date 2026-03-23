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
package org.evosuite.llm.search;

import org.evosuite.Properties;
import org.evosuite.llm.LlmBudgetExceededException;
import org.evosuite.llm.LlmCallFailedException;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.prompt.CoverageGoalFormatter;
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
import java.util.Map;

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
        return requestHelp(uncoveredGoals, currentPopulation, 0, 0, null);
    }

    /**
     * Requests LLM-generated tests with enriched diagnostic context.
     *
     * @param uncoveredGoals     remaining uncovered goals
     * @param currentPopulation  current best tests
     * @param totalGoals         total number of coverage goals (0 to omit from prompt)
     * @param coveredGoalCount   number of goals already covered
     * @param bestFitnessPerGoal optional fitness-distance map for "almost covered" annotations
     */
    public List<TestChromosome> requestHelp(Collection<TestFitnessFunction> uncoveredGoals,
                                            List<TestChromosome> currentPopulation,
                                            int totalGoals, int coveredGoalCount,
                                            Map<TestFitnessFunction, Double> bestFitnessPerGoal) {
        if (!llmService.isAvailable() || !llmService.hasBudget()) {
            return Collections.emptyList();
        }
        if (uncoveredGoals == null || uncoveredGoals.isEmpty()) {
            return Collections.emptyList();
        }

        PromptResult prompt = buildPrompt(uncoveredGoals, currentPopulation,
                totalGoals, coveredGoalCount, bestFitnessPerGoal);
        try {
            String response = llmService.query(prompt, LlmFeature.STAGNATION);
            RepairResult result = TestRepairLoop.createDefault(llmService).attemptParse(
                    response, prompt.getMessages(), LlmFeature.STAGNATION);
            if (!result.isSuccess()) {
                return Collections.emptyList();
            }
            return result.toChromosomes(testsPerRequest);
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
                                         List<TestChromosome> currentPopulation,
                                         int totalGoals, int coveredGoalCount,
                                         Map<TestFitnessFunction, Double> bestFitnessPerGoal) {
        List<TestChromosome> popCandidates = currentPopulation != null
                ? currentPopulation : Collections.emptyList();

        CoverageGoalFormatter goalFormatter = new CoverageGoalFormatter();
        String goalsSection = goalFormatter.format(uncoveredGoals, bestFitnessPerGoal);

        String instruction = buildEnrichedInstruction(totalGoals, coveredGoalCount);

        PromptBuilder builder = new PromptBuilder()
                .withSystemPrompt()
                .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withTestClusterContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withFewShotSnippets(FewShotExampleProvider.collectSnippetsIfFewShot(
                        uncoveredGoals, new ArrayList<>(popCandidates)))
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE)
                .withInstruction(instruction);

        // Add pre-formatted goals (bypasses PromptBuilder.withUncoveredGoals to
        // include fitness annotations)
        builder.withInstruction("Uncovered goals:\n" + goalsSection);

        List<TestCase> existingTests = new ArrayList<>();
        if (currentPopulation != null) {
            TestRelevanceRanker.rankByRelevance(currentPopulation, uncoveredGoals, 3)
                    .stream()
                    .map(TestChromosome::getTestCase)
                    .forEach(existingTests::add);
        }
        if (!existingTests.isEmpty()) {
            builder.withExistingTests(existingTests);
        }
        return builder.buildWithMetadata();
    }

    private String buildEnrichedInstruction(int totalGoals, int coveredGoalCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("The evolutionary search stagnated after ")
                .append(stagnationThreshold)
                .append(" generations with no fitness improvement.");
        if (totalGoals > 0) {
            double pct = 100.0 * coveredGoalCount / totalGoals;
            sb.append(String.format(" Current coverage: %d/%d goals (%.1f%%).",
                    coveredGoalCount, totalGoals, pct));
        }
        sb.append(" Goals marked [almost covered] were close to being reached — focus on those first.");
        sb.append(" Generate ").append(testsPerRequest)
                .append(" JUnit tests targeting the uncovered goals.");
        return sb.toString();
    }

}
