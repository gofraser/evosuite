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
 * LLM-driven semantic crossover: asks the LLM to produce a recombination
 * of two parent tests that combines useful setup and behaviour from both.
 */
public class LlmSemanticCrossover {

    private static final Logger logger = LoggerFactory.getLogger(LlmSemanticCrossover.class);

    private final LlmService llmService;

    /** One loop per calling thread; see {@link #repairLoop()}. */
    private final ThreadLocal<TestRepairLoop> repairLoops = ThreadLocal.withInitial(this::newRepairLoop);

    public LlmSemanticCrossover() {
        this(LlmService.getInstance());
    }

    public LlmSemanticCrossover(LlmService llmService) {
        this.llmService = llmService;
    }

    /**
     * Attempt a semantic crossover of two parent test chromosomes. One LLM
     * call (retries on transport failure are handled inside
     * {@link LlmService}); a malformed response gets at most
     * {@link Properties#LLM_OPERATOR_REPAIR_ATTEMPTS} repair round-trips.
     *
     * @param parent1 first parent
     * @param parent2 second parent
     * @param goals   current goals for context
     * @return the recombined chromosome, or null if LLM crossover failed
     */
    public TestChromosome crossoverSemantically(TestChromosome parent1,
                                                 TestChromosome parent2,
                                                 Collection<TestFitnessFunction> goals) {
        if (parent1 == null || parent2 == null
                || !llmService.isAvailable() || !llmService.hasBudget()) {
            return null;
        }

        try {
            PromptResult prompt = new PromptBuilder()
                    .withSystemPrompt()
                    .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                    .withExistingTests(java.util.Arrays.asList(
                            parent1.getTestCase(), parent2.getTestCase()))
                    .withUncoveredGoals(goals)
                    .withFewShotSnippets(FewShotExampleProvider.collectSnippetsIfFewShot(goals, null))
                    .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE)
                    .withInstruction(
                            "Produce a single new test that semantically recombines "
                            + "the two given tests. Combine useful setup, method calls, "
                            + "and behaviours from both parents into one coherent test. "
                            + "Target the uncovered goals if possible. "
                            + "Return exactly one complete JUnit test method."
                            + LlmAssertionPolicyResolver.instructionSuffix(false))
                    .buildWithMetadata();

            String response = llmService.query(prompt, LlmFeature.SEMANTIC_CROSSOVER);
            RepairResult result = repairLoop()
                    .attemptParse(response, prompt.getMessages(), LlmFeature.SEMANTIC_CROSSOVER);
            if (result.isSuccess() && !result.getTestCases().isEmpty()) {
                TestChromosome child = new TestChromosome();
                child.setTestCase(result.getTestCases().get(0));
                child.setChanged(true);
                return child;
            }
        } catch (LlmBudgetExceededException e) {
            logger.debug("LLM semantic crossover budget exceeded");
        } catch (LlmCallFailedException e) {
            logger.debug("LLM semantic crossover call failed: {}", e.getMessage());
        } catch (RuntimeException e) {
            logger.debug("LLM semantic crossover error: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Returns this thread's repair loop, building it on first use: construction
     * summarises the test cluster, which is too expensive to repeat per
     * application, while {@link TestRepairLoop#attemptParse} keeps per-call
     * conversation state in the instance, so threads must not share one.
     *
     * <p>Package-private for tests.
     */
    TestRepairLoop repairLoop() {
        return repairLoops.get();
    }

    private TestRepairLoop newRepairLoop() {
        boolean keepAssertions = LlmAssertionPolicyResolver.keepAssertions(false);
        return TestRepairLoop.createDefault(llmService,
                TestRepairLoop.RepairOptions.forAssertionPolicy(keepAssertions),
                Properties.LLM_OPERATOR_REPAIR_ATTEMPTS);
    }

}
