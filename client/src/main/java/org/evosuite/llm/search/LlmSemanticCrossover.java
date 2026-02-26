/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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

import java.util.Collection;
import java.util.List;

/**
 * LLM-driven semantic crossover: asks the LLM to produce a recombination
 * of two parent tests that combines useful setup and behaviour from both.
 */
public class LlmSemanticCrossover {

    private static final Logger logger = LoggerFactory.getLogger(LlmSemanticCrossover.class);

    private final LlmService llmService;

    public LlmSemanticCrossover() {
        this(LlmService.getInstance());
    }

    public LlmSemanticCrossover(LlmService llmService) {
        this.llmService = llmService;
    }

    /**
     * Attempt a semantic crossover of two parent test chromosomes.
     *
     * @param parent1 first parent
     * @param parent2 second parent
     * @param goals   uncovered goals for context
     * @return the recombined chromosome, or null if LLM crossover failed
     */
    public TestChromosome crossoverSemantically(TestChromosome parent1,
                                                 TestChromosome parent2,
                                                 Collection<TestFitnessFunction> goals) {
        if (parent1 == null || parent2 == null
                || !llmService.isAvailable()) {
            return null;
        }

        for (int attempt = 0; attempt < Properties.LLM_OPERATOR_MAX_ATTEMPTS; attempt++) {
            try {
                PromptResult prompt = new PromptBuilder()
                        .withSystemPrompt()
                        .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                        .withExistingTests(java.util.Arrays.asList(
                                parent1.getTestCase(), parent2.getTestCase()))
                        .withUncoveredGoals(goals)
                        .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE)
                        .withInstruction(
                                "Produce a single new test that semantically recombines "
                                + "the two given tests. Combine useful setup, method calls, "
                                + "and behaviours from both parents into one coherent test. "
                                + "Target the uncovered goals if possible. "
                                + "Return exactly one complete JUnit test method.")
                        .buildWithMetadata();

                String response = llmService.query(prompt, LlmFeature.SEMANTIC_CROSSOVER);
                RepairResult result = createRepairLoop().attemptParse(
                        response, prompt.getMessages(), LlmFeature.SEMANTIC_CROSSOVER);
                if (result.isSuccess() && !result.getTestCases().isEmpty()) {
                    TestChromosome child = new TestChromosome();
                    child.setTestCase(result.getTestCases().get(0));
                    child.setChanged(true);
                    return child;
                }
            } catch (LlmBudgetExceededException e) {
                logger.debug("LLM semantic crossover budget exceeded");
                return null;
            } catch (LlmCallFailedException e) {
                logger.debug("LLM semantic crossover call failed on attempt {}: {}",
                        attempt, e.getMessage());
            } catch (RuntimeException e) {
                logger.debug("LLM semantic crossover error on attempt {}: {}",
                        attempt, e.getMessage());
            }
        }
        return null;
    }

    private TestRepairLoop createRepairLoop() {
        return new TestRepairLoop(
                llmService,
                TestParser.forSUT(),
                new LlmResponseParser(),
                new ClusterExpansionManager());
    }
}
