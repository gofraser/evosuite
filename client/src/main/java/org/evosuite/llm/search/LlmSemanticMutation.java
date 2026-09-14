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
import org.evosuite.llm.response.LlmAssertionPolicyResolver;
import org.evosuite.llm.response.RepairResult;
import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * LLM-driven semantic mutation: asks the LLM to produce a meaningfully
 * mutated version of a test that targets different behaviour/state.
 */
public class LlmSemanticMutation {

    private static final Logger logger = LoggerFactory.getLogger(LlmSemanticMutation.class);

    private final LlmService llmService;

    /** Built lazily on first use; see {@link #repairLoop()}. */
    private volatile TestRepairLoop cachedRepairLoop;

    public LlmSemanticMutation() {
        this(LlmService.getInstance());
    }

    public LlmSemanticMutation(LlmService llmService) {
        this.llmService = llmService;
    }

    /**
     * Attempt a semantic mutation of the given test chromosome. One LLM call
     * (retries on transport failure are handled inside {@link LlmService});
     * a malformed response gets at most
     * {@link Properties#LLM_OPERATOR_REPAIR_ATTEMPTS} repair round-trips.
     *
     * @param test  the test to mutate
     * @param goals current goals for context
     * @return the mutated chromosome, or null if LLM mutation failed
     */
    public TestChromosome mutateSemantically(TestChromosome test,
                                              Collection<TestFitnessFunction> goals) {
        if (test == null || !llmService.isAvailable() || !llmService.hasBudget()) {
            return null;
        }

        try {
            PromptResult prompt = new PromptBuilder()
                    .withSystemPrompt()
                    .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                    .withExistingTest(test.getTestCase())
                    .withUncoveredGoals(goals)
                    .withFewShotSnippets(FewShotExampleProvider.collectSnippetsIfFewShot(goals, null))
                    .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE)
                    .withInstruction(
                            "Produce a semantically mutated version of this test. "
                            + "Make a meaningful behaviour or state change (not just renaming or "
                            + "minor edits). Target the uncovered goals if possible. "
                            + "Return exactly one complete JUnit test method."
                            + LlmAssertionPolicyResolver.instructionSuffix(false))
                    .buildWithMetadata();

            String response = llmService.query(prompt, LlmFeature.SEMANTIC_MUTATION);
            RepairResult result = repairLoop()
                    .attemptParse(response, prompt.getMessages(), LlmFeature.SEMANTIC_MUTATION);
            if (result.isSuccess() && !result.getTestCases().isEmpty()) {
                TestChromosome mutant = new TestChromosome();
                mutant.setTestCase(result.getTestCases().get(0));
                mutant.setChanged(true);
                return mutant;
            }
        } catch (LlmBudgetExceededException e) {
            logger.debug("LLM semantic mutation budget exceeded");
        } catch (LlmCallFailedException e) {
            logger.debug("LLM semantic mutation call failed: {}", e.getMessage());
        } catch (RuntimeException e) {
            logger.debug("LLM semantic mutation error: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Lazily creates the repair loop once per operator instance: building it
     * summarises the test cluster, which is too expensive to repeat per
     * application. A benign race may initialise it twice; both instances are
     * equivalent and {@link TestRepairLoop#attemptParse} resets per-call state.
     *
     * <p>Package-private for tests.
     */
    TestRepairLoop repairLoop() {
        TestRepairLoop loop = cachedRepairLoop;
        if (loop == null) {
            boolean keepAssertions = LlmAssertionPolicyResolver.keepAssertions(false);
            loop = TestRepairLoop.createDefault(llmService,
                    TestRepairLoop.RepairOptions.forAssertionPolicy(keepAssertions),
                    Properties.LLM_OPERATOR_REPAIR_ATTEMPTS);
            cachedRepairLoop = loop;
        }
        return loop;
    }

}
