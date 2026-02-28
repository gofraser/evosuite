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
package org.evosuite.strategy;

import org.evosuite.Properties;
import org.evosuite.coverage.TestFitnessFactory;
import org.evosuite.ga.stoppingconditions.MaxTimeStoppingCondition;
import org.evosuite.ga.stoppingconditions.StoppingCondition;
import org.evosuite.llm.LlmBudgetExceededException;
import org.evosuite.llm.LlmCallFailedException;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmService;
import org.evosuite.llm.factory.LlmSeededPopulationFactory;
import org.evosuite.llm.prompt.FewShotExampleProvider;
import org.evosuite.llm.prompt.PromptBuilder;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.response.ClusterExpansionManager;
import org.evosuite.llm.response.LlmResponseParser;
import org.evosuite.llm.response.RepairResult;
import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientState;
import org.evosuite.setup.TestCluster;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.factories.RandomLengthTestFactory;
import org.evosuite.testparser.TestParser;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.evosuite.utils.ArrayUtil;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * LLM-only strategy for test generation.
 *
 * <p>Supports two modes via {@code Properties.LLM_STRATEGY_MODE}:
 * <ul>
 *   <li>{@code SINGLE_PROMPT} — one-shot baseline: generate once and stop.</li>
 *   <li>{@code ITERATIVE_BUDGETED} — iterative baseline: initial broad query,
 *       evaluate, re-query for uncovered goals until search budget or LLM
 *       call budget is exhausted.</li>
 * </ul>
 */
public class LlmStrategy extends TestGenerationStrategy {

    private static final Logger logger = LoggerFactory.getLogger(LlmStrategy.class);

    @Override
    public TestSuiteChromosome generateTests() {
        LoggingUtils.getEvoLogger().info("* Using LLM strategy (mode={})",
                Properties.LLM_STRATEGY_MODE);

        List<TestSuiteFitnessFunction> fitnessFunctions = getFitnessFunctions();
        List<TestFitnessFactory<? extends TestFitnessFunction>> goalFactories = getConfiguredGoalFactories();
        List<TestFitnessFunction> allGoals = goalFactories.stream()
                .flatMap(f -> f.getCoverageGoals().stream())
                .collect(Collectors.toList());
        int totalGoals = allGoals.size();
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.Total_Goals, totalGoals);

        if (!canGenerateTestsForSUT()) {
            LoggingUtils.getEvoLogger().info(
                    "* Found no testable methods in the target class {}",
                    Properties.TARGET_CLASS);
            return new TestSuiteChromosome();
        }

        ClientServices.getInstance().getClientNode().changeState(ClientState.SEARCH);

        TestSuiteChromosome suite;
        if (Properties.LLM_STRATEGY_MODE == Properties.LlmStrategyMode.ITERATIVE_BUDGETED) {
            suite = runIterativeBudgeted(fitnessFunctions, allGoals);
        } else {
            suite = runSinglePrompt(fitnessFunctions);
        }

        emitParsedStatementRatio(suite);
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.Generations, 1);
        sendExecutionStatistics();
        return suite;
    }

    /**
     * Computes and emits the ratio of LLM-parsed statements to total statements.
     */
    private void emitParsedStatementRatio(TestSuiteChromosome suite) {
        int totalStatements = 0;
        int parsedStatements = 0;
        for (TestChromosome tc : suite.getTestChromosomes()) {
            for (int i = 0; i < tc.getTestCase().size(); i++) {
                totalStatements++;
                if (tc.getTestCase().getStatement(i).isParsedFromLlm()) {
                    parsedStatements++;
                }
            }
        }
        double ratio = totalStatements > 0
                ? (double) parsedStatements / totalStatements : 0.0;
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.LLM_Parsed_Statement_Ratio, ratio);
    }

    /**
     * SINGLE_PROMPT mode: one-shot LLM call, evaluate, return.
     */
    private TestSuiteChromosome runSinglePrompt(
            List<TestSuiteFitnessFunction> fitnessFunctions) {
        LlmSeededPopulationFactory seededFactory = createSeededFactory();
        long waitMillis = Math.max(1L, Properties.LLM_TIMEOUT_SECONDS * 1000L);
        List<TestChromosome> llmSeeds = seededFactory.awaitAndDrainSeeds(waitMillis);
        LoggingUtils.getEvoLogger().info("* Received {} LLM seeds",
                llmSeeds.size());

        TestSuiteChromosome suite = new TestSuiteChromosome();
        llmSeeds.forEach(suite::addTest);

        if (suite.getTestChromosomes().isEmpty()
                && !ArrayUtil.contains(Properties.CRITERION,
                Properties.Criterion.EXCEPTION)) {
            return suite;
        }

        for (TestSuiteFitnessFunction ff : fitnessFunctions) {
            ff.getFitness(suite);
        }
        return suite;
    }

    /**
     * Runs the ITERATIVE_BUDGETED mode.
     * <ol>
     *   <li>Initial broad-coverage query</li>
     *   <li>Evaluate tests, compute uncovered goals</li>
     *   <li>Follow-up queries targeting uncovered goals</li>
     *   <li>Stop when stopping-condition budget or LLM call budget exhausted</li>
     * </ol>
     */
    private TestSuiteChromosome runIterativeBudgeted(
            List<TestSuiteFitnessFunction> fitnessFunctions,
            List<TestFitnessFunction> allGoals) {
        StoppingCondition<TestSuiteChromosome> stoppingCondition = getStoppingCondition();
        stoppingCondition.reset();

        LlmService llmService = getLlmService();
        TestSuiteChromosome suite = new TestSuiteChromosome();
        List<Double> ratioTimeline = new ArrayList<>();

        // --- Initial broad-coverage query ---
        List<TestChromosome> initialTests = queryForBroadCoverage(llmService);
        for (TestChromosome tc : initialTests) {
            suite.addTest(tc);
        }
        evaluateSuite(suite, fitnessFunctions);
        ratioTimeline.add(computeParsedRatio(suite));

        if (isFinished(suite, stoppingCondition)) {
            emitRatioTimeline(ratioTimeline);
            return suite;
        }

        // --- Iterative follow-up queries ---
        int iteration = 0;
        while (!isFinished(suite, stoppingCondition)) {
            iteration++;
            Set<TestFitnessFunction> coveredGoals = suite.getCoveredGoals();
            Collection<TestFitnessFunction> uncoveredGoals = allGoals.stream()
                    .filter(g -> !coveredGoals.contains(g))
                    .collect(Collectors.toList());

            if (uncoveredGoals.isEmpty()) {
                LoggingUtils.getEvoLogger().info(
                        "* All goals covered after {} iterations", iteration);
                break;
            }

            if (!llmService.isAvailable() || !llmService.hasBudget()) {
                LoggingUtils.getEvoLogger().info(
                        "* LLM budget exhausted after {} iterations", iteration);
                break;
            }

            List<TestChromosome> newTests = queryForUncoveredGoals(
                    llmService, uncoveredGoals, suite.getTestChromosomes());

            if (!newTests.isEmpty()) {
                for (TestChromosome tc : newTests) {
                    suite.addTest(tc);
                }
                evaluateSuite(suite, fitnessFunctions);
                ratioTimeline.add(computeParsedRatio(suite));

                LoggingUtils.getEvoLogger().info(
                        "* Iteration {}: suite size={}, covered goals={}",
                        iteration, suite.size(), suite.getCoveredGoals().size());
            }

            // Advance count-based stopping conditions to guarantee termination.
            // Time-based conditions (MaxTimeStoppingCondition) advance via wall clock
            // and must not be force-advanced to avoid shortening the budget.
            if (!(stoppingCondition instanceof MaxTimeStoppingCondition)) {
                stoppingCondition.forceCurrentValue(
                        stoppingCondition.getCurrentValue() + 1);
            }
        }

        emitRatioTimeline(ratioTimeline);
        return suite;
    }

    /**
     * Initial LLM query: broad coverage of the CUT.
     */
    private List<TestChromosome> queryForBroadCoverage(
            LlmService llmService) {
        if (!llmService.isAvailable() || !llmService.hasBudget()) {
            return Collections.emptyList();
        }
        PromptResult prompt = new PromptBuilder()
                .withSystemPrompt()
                .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withFewShotSnippets(FewShotExampleProvider.collectSnippetsIfFewShot(null, null))
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE)
                .withInstruction("Generate JUnit test methods that maximize code coverage of the target class. "
                        + "Cover all reachable methods, branches, boundary values, and exception paths.")
                .buildWithMetadata();

        return queryAndParse(llmService, prompt,
                LlmFeature.ITERATIVE_STRATEGY);
    }

    /**
     * Follow-up LLM query targeting specific uncovered goals.
     */
    private List<TestChromosome> queryForUncoveredGoals(
            LlmService llmService,
            Collection<TestFitnessFunction> uncoveredGoals,
            List<TestChromosome> currentTests) {
        PromptBuilder builder = new PromptBuilder()
                .withSystemPrompt()
                .withSutContext(Properties.TARGET_CLASS, TestCluster.getInstance())
                .withUncoveredGoals(uncoveredGoals)
                .withFewShotSnippets(FewShotExampleProvider.collectSnippetsIfFewShot(uncoveredGoals, null))
                .withPromptTechnique(Properties.LLM_PROMPT_TECHNIQUE)
                .withInstruction("The following coverage goals are still uncovered. "
                        + "Generate JUnit test methods specifically targeting these uncovered goals.");

        if (currentTests != null && !currentTests.isEmpty()) {
            List<org.evosuite.testcase.TestCase> existing = currentTests.stream()
                    .limit(3)
                    .map(TestChromosome::getTestCase)
                    .collect(Collectors.toList());
            builder.withExistingTests(existing);
        }
        PromptResult prompt = builder.buildWithMetadata();
        return queryAndParse(llmService, prompt,
                LlmFeature.ITERATIVE_STRATEGY);
    }

    private List<TestChromosome> queryAndParse(
            LlmService llmService, PromptResult prompt,
            LlmFeature feature) {
        try {
            String response = llmService.query(prompt, feature);
            RepairResult result = createRepairLoop(llmService)
                    .attemptParse(response, prompt.getMessages(), feature);
            if (!result.isSuccess()) {
                return Collections.emptyList();
            }
            return toChromosomes(result);
        } catch (LlmBudgetExceededException | LlmCallFailedException e) {
            logger.debug("LLM query failed: {}", e.getMessage());
            return Collections.emptyList();
        } catch (RuntimeException e) {
            logger.debug("LLM query crashed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void evaluateSuite(TestSuiteChromosome suite,
                               List<TestSuiteFitnessFunction> fitnessFunctions) {
        for (TestSuiteFitnessFunction ff : fitnessFunctions) {
            ff.getFitness(suite);
        }
    }

    private List<TestChromosome> toChromosomes(RepairResult repairResult) {
        List<TestChromosome> chromosomes = new ArrayList<>();
        repairResult.getTestCases().stream()
                .forEach(testCase -> {
                    TestChromosome tc = new TestChromosome();
                    tc.setTestCase(testCase);
                    chromosomes.add(tc);
                });
        return chromosomes;
    }

    protected TestRepairLoop createRepairLoop(LlmService llmService) {
        return new TestRepairLoop(
                llmService,
                TestParser.forSUTWithLlmProvenance(),
                new LlmResponseParser(),
                new ClusterExpansionManager());
    }

    protected List<TestFitnessFactory<? extends TestFitnessFunction>> getConfiguredGoalFactories() {
        return getFitnessFactories();
    }

    protected LlmSeededPopulationFactory createSeededFactory() {
        return new LlmSeededPopulationFactory(new RandomLengthTestFactory());
    }

    protected LlmService getLlmService() {
        return LlmService.getInstance();
    }

    /**
     * Computes the ratio of LLM-parsed statements to total statements in a suite.
     */
    public static double computeParsedRatio(TestSuiteChromosome suite) {
        int total = 0;
        int parsed = 0;
        for (TestChromosome tc : suite.getTestChromosomes()) {
            for (int i = 0; i < tc.getTestCase().size(); i++) {
                total++;
                if (tc.getTestCase().getStatement(i).isParsedFromLlm()) {
                    parsed++;
                }
            }
        }
        return total > 0 ? (double) parsed / total : 0.0;
    }

    private void emitRatioTimeline(List<Double> timeline) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < timeline.size(); i++) {
            if (i > 0) {
                sb.append(";");
            }
            sb.append(String.format(Locale.ROOT, "%.4f", timeline.get(i)));
        }
        ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.LLM_Parsed_Statement_Ratio_Timeline,
                        sb.toString());
    }
}
