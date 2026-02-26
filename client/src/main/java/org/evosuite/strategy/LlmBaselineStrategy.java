package org.evosuite.strategy;

import org.evosuite.Properties;
import org.evosuite.coverage.TestFitnessFactory;
import org.evosuite.llm.factory.LlmSeededPopulationFactory;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientState;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.factories.RandomLengthTestFactory;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.evosuite.utils.ArrayUtil;
import org.evosuite.utils.LoggingUtils;

import java.util.List;

/**
 * LLM-only baseline strategy for ablation experiments.
 */
public class LlmBaselineStrategy extends TestGenerationStrategy {

    @Override
    public TestSuiteChromosome generateTests() {
        LoggingUtils.getEvoLogger().info("* Using LLM baseline strategy");

        List<TestSuiteFitnessFunction> fitnessFunctions = getFitnessFunctions();
        List<TestFitnessFactory<? extends TestFitnessFunction>> goalFactories = getConfiguredGoalFactories();
        int totalGoals = goalFactories.stream().mapToInt(factory -> factory.getCoverageGoals().size()).sum();
        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Total_Goals, totalGoals);

        if (!canGenerateTestsForSUT()) {
            LoggingUtils.getEvoLogger().info("* Found no testable methods in the target class {}",
                    Properties.TARGET_CLASS);
            return new TestSuiteChromosome();
        }

        ClientServices.getInstance().getClientNode().changeState(ClientState.SEARCH);
        LlmSeededPopulationFactory seededFactory = createSeededFactory();
        long waitMillis = Math.max(1L, Properties.LLM_TIMEOUT_SECONDS * 1000L);
        List<TestChromosome> llmSeeds = seededFactory.awaitAndDrainSeeds(waitMillis);
        int requestedSeeds = Math.max(1, Properties.LLM_SEED_COUNT);

        TestSuiteChromosome suite = new TestSuiteChromosome();
        for (int i = 0; i < Math.min(requestedSeeds, llmSeeds.size()); i++) {
            suite.addTest(llmSeeds.get(i));
        }

        if (suite.getTestChromosomes().isEmpty() && !ArrayUtil.contains(Properties.CRITERION, Properties.Criterion.EXCEPTION)) {
            sendExecutionStatistics();
            return suite;
        }

        for (TestSuiteFitnessFunction ff : fitnessFunctions) {
            ff.getFitness(suite);
        }
        sendExecutionStatistics();
        return suite;
    }

    protected List<TestFitnessFactory<? extends TestFitnessFunction>> getConfiguredGoalFactories() {
        return getFitnessFactories();
    }

    protected LlmSeededPopulationFactory createSeededFactory() {
        return new LlmSeededPopulationFactory(new RandomLengthTestFactory());
    }
}
