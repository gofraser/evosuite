package org.evosuite.llm.search;

import org.evosuite.Properties;
import org.evosuite.ga.localsearch.LocalSearchBudget;
import org.evosuite.ga.localsearch.LocalSearchObjective;
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
import org.evosuite.testcase.localsearch.TestCaseLocalSearch;
import org.evosuite.testparser.TestParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * LLM-driven local search for test improvement.
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
        PromptResult prompt = new PromptBuilder()
                .withSystemPrompt()
                .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withExistingTest(test.getTestCase())
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE)
                .withInstruction("Modify this test to improve target coverage while keeping it valid JUnit.")
                .buildWithMetadata();
        try {
            String response = llmService.query(prompt, LlmFeature.LOCAL_SEARCH);
            RepairResult result = createRepairLoop().attemptParse(response, prompt.getMessages(), LlmFeature.LOCAL_SEARCH);
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

    private TestRepairLoop createRepairLoop() {
        return new TestRepairLoop(
                llmService,
                TestParser.forSUT(),
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
