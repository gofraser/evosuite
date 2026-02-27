package org.evosuite.llm.factory;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
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
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Probabilistic LLM test factory with non-LLM fallback.
 */
public class LlmTestChromosomeFactory implements ChromosomeFactory<TestChromosome> {

    private static final long serialVersionUID = -7563751833887015047L;
    private static final Logger logger = LoggerFactory.getLogger(LlmTestChromosomeFactory.class);

    private final ChromosomeFactory<TestChromosome> fallback;
    private final LlmService llmService;
    private final Supplier<Collection<TestFitnessFunction>> uncoveredGoalsSupplier;

    public LlmTestChromosomeFactory(ChromosomeFactory<TestChromosome> fallback) {
        this(fallback, LlmService.getInstance(), Collections::emptyList);
    }

    public LlmTestChromosomeFactory(ChromosomeFactory<TestChromosome> fallback,
                                    LlmService llmService,
                                    Supplier<Collection<TestFitnessFunction>> uncoveredGoalsSupplier) {
        this.fallback = fallback;
        this.llmService = llmService;
        this.uncoveredGoalsSupplier = uncoveredGoalsSupplier == null ? Collections::emptyList : uncoveredGoalsSupplier;
    }

    @Override
    public TestChromosome getChromosome() {
        if (!shouldUseLlm()) {
            return fallback.getChromosome();
        }
        try {
            TestChromosome llmChromosome = generateViaLlm();
            if (llmChromosome != null) {
                return llmChromosome;
            }
        } catch (LlmBudgetExceededException e) {
            logger.debug("LLM test factory budget exhausted: {}", e.getMessage());
        } catch (LlmCallFailedException e) {
            logger.debug("LLM test factory call failed: {}", e.getMessage());
        } catch (RuntimeException e) {
            logger.debug("LLM test factory failed: {}", e.getMessage());
        }
        return fallback.getChromosome();
    }

    private boolean shouldUseLlm() {
        if (!Properties.LLM_TEST_FACTORY || !llmService.isAvailable()) {
            return false;
        }
        if (!llmService.hasBudget()) {
            return false;
        }
        double probability = Math.max(0.0d, Math.min(1.0d, Properties.LLM_TEST_FACTORY_PROBABILITY));
        return Randomness.nextDouble() < probability;
    }

    private TestChromosome generateViaLlm() {
        PromptResult prompt = buildPrompt();
        String response = llmService.query(prompt, LlmFeature.TEST_FACTORY);
        RepairResult repairResult = createRepairLoop().attemptParse(response, prompt.getMessages(), LlmFeature.TEST_FACTORY);
        if (!repairResult.isSuccess() || repairResult.getTestCases().isEmpty()) {
            return null;
        }
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(repairResult.getTestCases().get(0));
        return chromosome;
    }

    private PromptResult buildPrompt() {
        PromptBuilder builder = new PromptBuilder()
                .withSystemPrompt()
                .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE)
                .withInstruction("Generate one JUnit test that is likely to improve coverage.");
        Collection<TestFitnessFunction> goals = uncoveredGoalsSupplier.get();
        if (goals != null && !goals.isEmpty()) {
            builder.withUncoveredGoals(goals);
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
}
