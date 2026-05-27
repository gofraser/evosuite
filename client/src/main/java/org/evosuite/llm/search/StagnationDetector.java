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
import org.evosuite.llm.prompt.CoverageGoalFormatter;
import org.evosuite.llm.prompt.FewShotExampleProvider;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Detects search stagnation and requests targeted LLM assistance.
 *
 * <p>Fires when wall-clock seconds without a fitness/coverage improvement
 * exceed the configured threshold. The window resets on every improvement
 * and on every fired call.
 */
public class StagnationDetector {

    private static final Logger logger = LoggerFactory.getLogger(StagnationDetector.class);
    private static final double EPSILON = 1e-12;

    private final LlmService llmService;
    private final boolean maximizationObjective;
    private final long stagnationThresholdNanos;
    private final int thresholdSeconds;
    private final int testsPerRequest;
    private final LongSupplier nanoClock;
    private long windowStartNanos;
    private boolean windowStarted = false;
    private Double bestFitness = null;
    private Integer coveredGoals = null;

    /** Creates a detector with singleton LLM service and Properties-configured thresholds. */
    public StagnationDetector() {
        this(LlmService.getInstance(), false,
                Properties.LLM_STAGNATION_TIMEOUT_SECONDS, Properties.LLM_STAGNATION_TESTS);
    }

    /** Creates a detector with the given maximization flag and Properties-configured thresholds. */
    public StagnationDetector(boolean maximizationObjective) {
        this(LlmService.getInstance(), maximizationObjective,
                Properties.LLM_STAGNATION_TIMEOUT_SECONDS, Properties.LLM_STAGNATION_TESTS);
    }

    /** Creates a detector with explicit LLM service, maximization flag, and threshold settings. */
    public StagnationDetector(LlmService llmService,
                              boolean maximizationObjective,
                              int stagnationTimeoutSeconds,
                              int testsPerRequest) {
        this(llmService, maximizationObjective, stagnationTimeoutSeconds, testsPerRequest,
                System::nanoTime);
    }

    /** Package-private constructor for tests: allows injecting a clock. */
    StagnationDetector(LlmService llmService,
                       boolean maximizationObjective,
                       int stagnationTimeoutSeconds,
                       int testsPerRequest,
                       LongSupplier nanoClock) {
        this.llmService = llmService;
        this.maximizationObjective = maximizationObjective;
        this.thresholdSeconds = Math.max(1, stagnationTimeoutSeconds);
        this.stagnationThresholdNanos = TimeUnit.SECONDS.toNanos(this.thresholdSeconds);
        this.testsPerRequest = Math.max(1, testsPerRequest);
        this.nanoClock = nanoClock;
    }

    /** Checks for stagnation based on the current best fitness value, returning true if stagnation detected. */
    public boolean checkStagnation(double currentBestFitness) {
        if (bestFitness == null) {
            bestFitness = currentBestFitness;
            startWindow();
            return false;
        }
        boolean improved = maximizationObjective
                ? currentBestFitness > (bestFitness + EPSILON)
                : currentBestFitness < (bestFitness - EPSILON);
        if (improved) {
            bestFitness = currentBestFitness;
            startWindow();
            return false;
        }
        return checkTimeWindow();
    }

    /** Checks for stagnation based on the current covered goals count, returning true if stagnation detected. */
    public boolean checkStagnation(int currentCoveredGoals) {
        if (coveredGoals == null) {
            coveredGoals = currentCoveredGoals;
            startWindow();
            return false;
        }
        if (currentCoveredGoals > coveredGoals) {
            coveredGoals = currentCoveredGoals;
            startWindow();
            return false;
        }
        return checkTimeWindow();
    }

    private void startWindow() {
        windowStartNanos = nanoClock.getAsLong();
        windowStarted = true;
    }

    private boolean checkTimeWindow() {
        if (!windowStarted) {
            startWindow();
            return false;
        }
        if (nanoClock.getAsLong() - windowStartNanos < stagnationThresholdNanos) {
            return false;
        }
        startWindow();
        return true;
    }

    public int getTestsPerRequest() {
        return testsPerRequest;
    }

    public LlmService getLlmService() {
        return llmService;
    }

    /** Requests LLM-generated tests targeting uncovered goals when the search has stagnated. */
    public List<TestChromosome> requestHelp(Collection<TestFitnessFunction> uncoveredGoals,
                                            List<TestChromosome> currentPopulation) {
        return requestHelp(uncoveredGoals, currentPopulation, 0, 0, null);
    }

    /**
     * Executes the stagnation LLM call for a pre-built prompt: query, parse, and
     * repair. Returns the tests produced (possibly empty). Performs no
     * thread-unsafe reads of the population — safe to invoke from a background
     * worker.
     */
    public List<TestChromosome> executeWithPrompt(PromptResult prompt) {
        if (prompt == null) {
            return Collections.emptyList();
        }
        if (!llmService.isAvailable() || !llmService.hasBudget()) {
            return Collections.emptyList();
        }
        boolean keepAssertions = LlmAssertionPolicyResolver.keepAssertions(false);
        try {
            String response = llmService.query(prompt, LlmFeature.STAGNATION);
            RepairResult result = TestRepairLoop
                    .createDefault(llmService, TestRepairLoop.RepairOptions.forAssertionPolicy(keepAssertions))
                    .attemptParse(response, prompt.getMessages(), LlmFeature.STAGNATION);
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
        boolean keepAssertions = LlmAssertionPolicyResolver.keepAssertions(false);

        PromptResult prompt = buildPrompt(uncoveredGoals, currentPopulation,
                totalGoals, coveredGoalCount, bestFitnessPerGoal);
        try {
            String response = llmService.query(prompt, LlmFeature.STAGNATION);
            RepairResult result = TestRepairLoop
                    .createDefault(llmService, TestRepairLoop.RepairOptions.forAssertionPolicy(keepAssertions))
                    .attemptParse(response, prompt.getMessages(), LlmFeature.STAGNATION);
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
        windowStarted = false;
    }

    /**
     * Returns the per-goal best fitness over the given population. Computed on
     * the caller's thread because it iterates the live population and computes
     * fitness against each goal.
     */
    public static Map<TestFitnessFunction, Double> computeBestFitnessPerGoal(
            Collection<TestFitnessFunction> uncoveredGoals,
            List<TestChromosome> population) {
        Map<TestFitnessFunction, Double> bestFitness = new LinkedHashMap<>();
        if (uncoveredGoals == null) {
            return bestFitness;
        }
        for (TestFitnessFunction goal : uncoveredGoals) {
            double best = Double.MAX_VALUE;
            if (population != null) {
                for (TestChromosome tc : population) {
                    double f = goal.getFitness(tc);
                    if (f < best) {
                        best = f;
                    }
                }
            }
            bestFitness.put(goal, best);
        }
        return bestFitness;
    }

    /**
     * Builds the stagnation prompt for the given snapshot. Public so async
     * callers can build the prompt on the search thread and then submit the
     * LLM call on a background worker.
     *
     * <p>Branches on {@link Properties#LLM_STAGNATION_PROMPT}: {@code POOL}
     * sends the full uncovered-goal set plus top-3 relevant tests with
     * population-wide fitness annotations; {@code TEST_ANCHORED} anchors on
     * the population's best near-miss and restricts the goal section to the
     * anchor's K closest uncovered goals with the anchor's own fitness as the
     * annotation source. Falls back to {@code POOL} when anchor selection
     * yields no usable signal.
     */
    public PromptResult buildPrompt(Collection<TestFitnessFunction> uncoveredGoals,
                                    List<TestChromosome> currentPopulation,
                                    int totalGoals, int coveredGoalCount,
                                    Map<TestFitnessFunction, Double> bestFitnessPerGoal) {
        if (Properties.LLM_STAGNATION_PROMPT == Properties.LlmStagnationPromptMode.TEST_ANCHORED) {
            PromptResult anchored = buildTestAnchoredPrompt(uncoveredGoals, currentPopulation,
                    totalGoals, coveredGoalCount);
            if (anchored != null) {
                return anchored;
            }
            logger.debug("Test-anchored prompt unavailable (no usable anchor fitness); "
                    + "falling back to pool prompt for this call.");
        }
        return buildPoolPrompt(uncoveredGoals, currentPopulation,
                totalGoals, coveredGoalCount, bestFitnessPerGoal);
    }

    private PromptResult buildPoolPrompt(Collection<TestFitnessFunction> uncoveredGoals,
                                         List<TestChromosome> currentPopulation,
                                         int totalGoals, int coveredGoalCount,
                                         Map<TestFitnessFunction, Double> bestFitnessPerGoal) {
        List<TestChromosome> popCandidates = currentPopulation != null
                ? currentPopulation : Collections.emptyList();

        CoverageGoalFormatter goalFormatter = new CoverageGoalFormatter();
        String goalsSection = goalFormatter.format(uncoveredGoals, bestFitnessPerGoal);

        String instruction = buildPoolInstruction(totalGoals, coveredGoalCount);

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

    private PromptResult buildTestAnchoredPrompt(Collection<TestFitnessFunction> uncoveredGoals,
                                                 List<TestChromosome> currentPopulation,
                                                 int totalGoals, int coveredGoalCount) {
        if (currentPopulation == null || currentPopulation.isEmpty()) {
            return null;
        }
        StagnationAnchorSelector.AnchorSelection selection = StagnationAnchorSelector.select(
                currentPopulation, uncoveredGoals,
                Properties.LLM_STAGNATION_ANCHOR_RELATED_GOALS_MAX);
        if (selection == null) {
            return null;
        }

        CoverageGoalFormatter goalFormatter = new CoverageGoalFormatter();
        String goalsSection = goalFormatter.format(selection.getGoals(),
                selection.getGoalFitness());

        String instruction = buildAnchoredInstruction(totalGoals, coveredGoalCount);

        PromptBuilder builder = new PromptBuilder()
                .withSystemPrompt()
                .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withTestClusterContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withExistingTest(selection.getAnchor().getTestCase())
                .withFewShotSnippets(FewShotExampleProvider.collectSnippetsIfFewShot(
                        selection.getGoals(),
                        new ArrayList<>(Collections.singletonList(selection.getAnchor()))))
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE)
                .withInstruction(instruction)
                .withInstruction("Uncovered goals (annotated with the test's fitness on each; "
                        + "lower = closer to covering):\n" + goalsSection);
        return builder.buildWithMetadata();
    }

    private String buildPoolInstruction(int totalGoals, int coveredGoalCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("The evolutionary search stagnated for at least ")
                .append(thresholdSeconds)
                .append(" seconds with no fitness improvement.");
        if (totalGoals > 0) {
            double pct = 100.0 * coveredGoalCount / totalGoals;
            sb.append(String.format(" Current coverage: %d/%d goals (%.1f%%).",
                    coveredGoalCount, totalGoals, pct));
        }
        sb.append(" Goals marked [almost covered] were close to being reached — focus on those first.");
        sb.append(" Generate ").append(testsPerRequest)
                .append(" JUnit tests targeting the uncovered goals.")
                .append(LlmAssertionPolicyResolver.instructionSuffix(false));
        return sb.toString();
    }

    private String buildAnchoredInstruction(int totalGoals, int coveredGoalCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("The evolutionary search stagnated for at least ")
                .append(thresholdSeconds)
                .append(" seconds with no fitness improvement.");
        if (totalGoals > 0) {
            double pct = 100.0 * coveredGoalCount / totalGoals;
            sb.append(String.format(" Current coverage: %d/%d goals (%.1f%%).",
                    coveredGoalCount, totalGoals, pct));
        }
        sb.append(" The following test is closest in the population to covering some "
                + "uncovered goals. The annotated goal list shows this test's fitness "
                + "on each — the lowest-fitness goal is the most likely near-miss. "
                + "Modify or extend this test to cover those goals while keeping it "
                + "valid JUnit. Return up to ")
                .append(testsPerRequest)
                .append(" JUnit test methods.")
                .append(LlmAssertionPolicyResolver.instructionSuffix(false));
        return sb.toString();
    }

}
