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
import org.evosuite.ga.archive.Archive;
import org.evosuite.ga.localsearch.LocalSearchBudget;
import org.evosuite.ga.localsearch.LocalSearchObjective;
import org.evosuite.llm.LlmBudgetExceededException;
import org.evosuite.llm.LlmCallFailedException;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.prompt.FewShotExampleProvider;
import org.evosuite.llm.prompt.PromptBuilder;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.response.ClusterExpansionManager;
import org.evosuite.llm.response.LlmResponseParser;
import org.evosuite.llm.response.RepairResult;
import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.localsearch.TestCaseLocalSearch;
import org.evosuite.testparser.TestParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * LLM-driven local search for test improvement.
 *
 * <p>When invoked, this operator queries the LLM with the current test and
 * uncovered-goal context to produce an improved version that covers more
 * goals. Goal context is controlled by:
 * <ul>
 *   <li>{@link Properties#LLM_LOCAL_SEARCH_RELATED_GOALS_ONLY} — when true and
 *       per-goal fitness values are available for the test, only the top-K
 *       closest uncovered goals are included (where K =
 *       {@link Properties#LLM_LOCAL_SEARCH_RELATED_GOALS_MAX}).</li>
 *   <li>When related-goal ranking is unavailable, all uncovered goals are
 *       included as fallback.</li>
 * </ul>
 */
public class LlmLocalSearch extends TestCaseLocalSearch<TestChromosome> {

    private static final Logger logger = LoggerFactory.getLogger(LlmLocalSearch.class);

    private final LlmService llmService;

    public LlmLocalSearch() {
        this(LlmService.getInstance());
    }

    public LlmLocalSearch(LlmService llmService) {
        this.llmService = llmService;
    }

    @Override
    public boolean doSearch(TestChromosome test, LocalSearchObjective<TestChromosome> objective) {
        if (test == null || objective == null) {
            return false;
        }
        if (!llmService.isAvailable() || !llmService.hasBudget()) {
            return false;
        }

        // Collect uncovered goals for prompt context
        Collection<TestFitnessFunction> goalsForPrompt = selectGoalsForPrompt(test);

        PromptBuilder builder = new PromptBuilder()
                .withSystemPrompt()
                .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withExistingTest(test.getTestCase())
                .withFewShotSnippets(FewShotExampleProvider.collectSnippetsIfFewShot(goalsForPrompt, null))
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE);

        if (goalsForPrompt != null && !goalsForPrompt.isEmpty()) {
            builder.withUncoveredGoals(goalsForPrompt);
            builder.withInstruction("Modify this test to improve target coverage, "
                    + "focusing on the uncovered goals listed above. Keep it valid JUnit.");
        } else {
            builder.withInstruction(
                    "Modify this test to improve target coverage while keeping it valid JUnit.");
        }

        PromptResult prompt = builder.buildWithMetadata();
        try {
            String response = llmService.query(prompt, LlmFeature.LOCAL_SEARCH);
            RepairResult result = createRepairLoop().attemptParse(
                    response, prompt.getMessages(), LlmFeature.LOCAL_SEARCH);
            if (!result.isSuccess()) {
                LocalSearchBudget.getInstance().countLocalSearchOnTest();
                return false;
            }
            for (TestChromosome candidate : toChromosomes(result)) {
                if (objective.hasImproved(candidate)) {
                    LocalSearchBudget.getInstance().countLocalSearchOnTest();
                    return true;
                }
            }
            LocalSearchBudget.getInstance().countLocalSearchOnTest();
            return false;
        } catch (LlmBudgetExceededException | LlmCallFailedException e) {
            logger.debug("LLM local search failed: {}", e.getMessage());
            LocalSearchBudget.getInstance().countLocalSearchOnTest();
            return false;
        } catch (RuntimeException e) {
            logger.debug("LLM local search crashed: {}", e.getMessage());
            LocalSearchBudget.getInstance().countLocalSearchOnTest();
            return false;
        }
    }

    /**
     * Selects uncovered goals to include in the LLM prompt, fetching them from the Archive.
     *
     * @param test the test chromosome under local search
     * @return the goals to include in the prompt, never null
     */
    Collection<TestFitnessFunction> selectGoalsForPrompt(TestChromosome test) {
        Set<TestFitnessFunction> allUncovered;
        try {
            allUncovered = Archive.getArchiveInstance().getUncoveredTargets();
        } catch (Exception e) {
            logger.debug("Could not retrieve uncovered goals from archive", e);
            return Collections.emptySet();
        }
        return selectGoalsForPrompt(test, allUncovered);
    }

    /**
     * Selects uncovered goals to include in the LLM prompt from the given set.
     *
     * <p>When {@link Properties#LLM_LOCAL_SEARCH_RELATED_GOALS_ONLY} is true,
     * attempts to rank uncovered goals by their fitness value for the given
     * test (lower fitness = closer to covering) and returns the top-K goals.
     * If per-goal fitness is unavailable (e.g., fitness map is empty), falls
     * back to returning all uncovered goals.
     *
     * @param test the test chromosome under local search
     * @param allUncovered all uncovered goals to consider
     * @return the goals to include in the prompt, never null
     */
    Collection<TestFitnessFunction> selectGoalsForPrompt(TestChromosome test,
                                                          Collection<TestFitnessFunction> allUncovered) {
        if (allUncovered == null || allUncovered.isEmpty()) {
            return Collections.emptySet();
        }

        if (!Properties.LLM_LOCAL_SEARCH_RELATED_GOALS_ONLY) {
            return allUncovered;
        }

        // Attempt related-goal ranking using per-goal fitness values
        Map<?, Double> fitnessValues = test.getFitnessValues();
        if (fitnessValues == null || fitnessValues.isEmpty()) {
            // Ranking unavailable — fall back to all uncovered goals
            logger.debug("Related-goal ranking unavailable (no fitness values); including all {} uncovered goals",
                    allUncovered.size());
            return allUncovered;
        }

        // Check whether the fitness map actually contains entries for any uncovered goals.
        // If no uncovered goal has a fitness entry, ranking is non-informative — fall back.
        boolean hasOverlap = allUncovered.stream().anyMatch(fitnessValues::containsKey);
        if (!hasOverlap) {
            logger.debug("Related-goal ranking non-informative (fitness map has no entries for "
                    + "uncovered goals); including all {} uncovered goals", allUncovered.size());
            return allUncovered;
        }

        // Rank uncovered goals by fitness distance (lower = closer to covering)
        int maxGoals = Properties.LLM_LOCAL_SEARCH_RELATED_GOALS_MAX;
        List<TestFitnessFunction> ranked = allUncovered.stream()
                .sorted(Comparator.comparingDouble(
                        goal -> test.getFitnessValues().getOrDefault(goal, Double.MAX_VALUE)))
                .limit(maxGoals)
                .collect(Collectors.toList());

        logger.debug("Selected {} related goals (of {} uncovered) for LLM local-search prompt",
                ranked.size(), allUncovered.size());
        return ranked;
    }

    private TestRepairLoop createRepairLoop() {
        return new TestRepairLoop(
                llmService,
                TestParser.forSUTWithLlmProvenance(),
                new LlmResponseParser(),
                new ClusterExpansionManager());
    }

    private List<TestChromosome> toChromosomes(RepairResult result) {
        java.util.ArrayList<TestChromosome> candidates = new java.util.ArrayList<>();
        result.getTestCases().forEach(testCase -> {
            TestChromosome chromosome = new TestChromosome();
            chromosome.setTestCase(testCase);
            chromosome.setChanged(true);
            candidates.add(chromosome);
        });
        return candidates;
    }
}
