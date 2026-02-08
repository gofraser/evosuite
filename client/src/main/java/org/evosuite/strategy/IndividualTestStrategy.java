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
package org.evosuite.strategy;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.ShutdownTestWriter;
import org.evosuite.coverage.TestFitnessFactory;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.ga.stoppingconditions.MaxStatementsStoppingCondition;
import org.evosuite.ga.stoppingconditions.StoppingCondition;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientState;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.evosuite.testsuite.TestSuiteMinimizer;
import org.evosuite.testsuite.factories.FixedSizeTestSuiteChromosomeFactory;
import org.evosuite.utils.ArrayUtil;
import org.evosuite.utils.LoggingUtils;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * This strategy selects one coverage goal at a time
 * and generates a test that satisfies it.
 *
 * <p>
 * The order of goals is randomized. Coincidental coverage is
 * checked.
 * </p>
 *
 * @author gordon
 */
public class IndividualTestStrategy extends TestGenerationStrategy {

    private static final Logger logger = LoggerFactory.getLogger(IndividualTestStrategy.class);

    @Override
    public TestSuiteChromosome generateTests() {
        // In order to improve strategy's performance, in here we explicitly disable EvoSuite's
        // archive, as it is not used anyway by this strategy
        Properties.TEST_ARCHIVE = false;

        // Set up search algorithm
        LoggingUtils.getEvoLogger().info("* Setting up search algorithm for individual test generation");
        ExecutionTracer.enableTraceCalls();

        PropertiesTestGAFactory factory = new PropertiesTestGAFactory();

        List<TestSuiteFitnessFunction> fitnessFunctions = getFitnessFunctions();

        long startTime = System.currentTimeMillis() / 1000;

        // Get list of goals
        List<TestFitnessFactory<? extends TestFitnessFunction>> goalFactories = getFitnessFactories();
        // long goalComputationStart = System.currentTimeMillis();
        List<TestFitnessFunction> goals = new ArrayList<>();
        LoggingUtils.getEvoLogger().info("* Total number of test goals: ");
        for (TestFitnessFactory<? extends TestFitnessFunction> goalFactory : goalFactories) {
            goals.addAll(goalFactory.getCoverageGoals());
            LoggingUtils.getEvoLogger().info("  - {} {}",
                    goalFactory.getClass().getSimpleName().replace("CoverageFactory", ""),
                    goalFactory.getCoverageGoals().size());
        }

        if (!canGenerateTestsForSUT()) {
            LoggingUtils.getEvoLogger().info("* Found no testable methods in the target class {}",
                    Properties.TARGET_CLASS);
            ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Total_Goals, goals.size());

            return new TestSuiteChromosome();
        }

        // Need to shuffle goals because the order may make a difference
        if (Properties.SHUFFLE_GOALS) {
            Randomness.shuffle(goals);
        }
        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Total_Goals,
                goals.size());

        LoggingUtils.getEvoLogger().info("* Total number of test goals: {}", goals.size());

        // Bootstrap with random testing to cover easy goals
        ClientServices.getInstance().getClientNode().changeState(ClientState.SEARCH);

        StoppingCondition<TestSuiteChromosome> stoppingCondition = getStoppingCondition();
        TestSuiteChromosome suite = bootstrapRandomSuite(goalFactories.get(0));
        Set<Integer> covered = new HashSet<>();
        int coveredGoals = 0;
        int num = 0;

        for (TestFitnessFunction fitnessFunction : goals) {
            if (fitnessFunction.isCoveredBy(suite)) {
                covered.add(num);
                coveredGoals++;
            }
            num++;
        }
        if (coveredGoals > 0) {
            LoggingUtils.getEvoLogger().info("* Random bootstrapping covered {} test goals", coveredGoals);
        }

        int totalGoals = goals.size();
        if (coveredGoals == totalGoals) {
            getZeroFitness().setFinished();
        }

        int currentBudget = 0;

        long totalBudget = Properties.SEARCH_BUDGET;
        LoggingUtils.getEvoLogger().info("* Budget: {}", NumberFormat.getIntegerInstance().format(totalBudget));

        while (currentBudget < totalBudget && coveredGoals < totalGoals
                && !getGlobalTime().isFinished() && !ShutdownTestWriter.isInterrupted()) {
            long budget = (totalBudget - currentBudget) / (totalGoals - coveredGoals);
            logger.info("Budget: {}/{}", budget, (totalBudget - currentBudget));
            logger.info("Statements: {}/{}", currentBudget, totalBudget);
            logger.info("Goals covered: {}/{}", coveredGoals, totalGoals);
            stoppingCondition.setLimit(budget);

            num = 0;
            for (TestFitnessFunction fitnessFunction : goals) {

                if (covered.contains(num)) {
                    num++;
                    continue;
                }

                GeneticAlgorithm<TestChromosome> ga = factory.getSearchAlgorithm();

                if (Properties.PRINT_CURRENT_GOALS) {
                    LoggingUtils.getEvoLogger().info("* Searching for goal {} ({} left) : {}", num,
                            (totalGoals - coveredGoals),
                            fitnessFunction);
                }

                if (ShutdownTestWriter.isInterrupted()) {
                    num++;
                    continue;
                }
                if (getGlobalTime().isFinished()) {
                    LoggingUtils.getEvoLogger().info("Skipping goal because time is up");
                    num++;
                    continue;
                }

                ga.addFitnessFunction(fitnessFunction);

                // Perform search
                logger.info("Starting evolution for goal {}", fitnessFunction);
                ga.generateSolution();

                if (ga.getBestIndividual().getFitness() == 0.0) {
                    if (Properties.PRINT_COVERED_GOALS) {
                        LoggingUtils.getEvoLogger().info("* Covered!");
                    }

                    logger.info("Found solution, adding to test suite at {}",
                            MaxStatementsStoppingCondition.getNumExecutedStatements());
                    TestChromosome best = ga.getBestIndividual();
                    best.getTestCase().addCoveredGoal(fitnessFunction);
                    suite.addTest(best);
                    // Calculate and keep track of overall fitness
                    for (TestSuiteFitnessFunction function : fitnessFunctions) {
                        function.getFitness(suite);
                    }

                    coveredGoals++;
                    covered.add(num);

                    // experiment:
                    if (Properties.SKIP_COVERED) {
                        Set<Integer> additionalCoveredNums = getAdditionallyCoveredGoals(goals,
                                covered,
                                best);
                        for (Integer coveredNum : additionalCoveredNums) {
                            coveredGoals++;
                            covered.add(coveredNum);
                        }
                    }

                } else {
                    logger.info("Found no solution for {} at {}", fitnessFunction,
                            MaxStatementsStoppingCondition.getNumExecutedStatements());
                }

                if (Properties.REUSE_BUDGET) {
                    currentBudget += stoppingCondition.getCurrentValue();
                } else {
                    currentBudget += budget + 1;
                }

                if (currentBudget > totalBudget) {
                    break;
                }
                num++;
            }
        }
        if (Properties.SHOW_PROGRESS) {
            LoggingUtils.getEvoLogger().info("");
        }

        // for testing purposes
        if (getGlobalTime().isFinished()) {
            LoggingUtils.getEvoLogger().info("! Timeout reached");
        }
        if (currentBudget >= totalBudget) {
            LoggingUtils.getEvoLogger().info("! Budget exceeded");
        } else {
            LoggingUtils.getEvoLogger().info("* Remaining budget: {}", (totalBudget - currentBudget));
        }

        int c = 0;
        int uncoveredGoals = totalGoals - coveredGoals;
        if (uncoveredGoals < 10) {
            for (TestFitnessFunction goal : goals) {
                if (!covered.contains(c)) {
                    LoggingUtils.getEvoLogger().info("! Unable to cover goal {} {}", c, goal);
                }
                c++;
            }
        } else {
            LoggingUtils.getEvoLogger().info("! #Goals that were not covered: {}", uncoveredGoals);
        }

        long endTime = System.currentTimeMillis() / 1000;
        LoggingUtils.getEvoLogger().info("* Search finished after {}s, {} statements, best individual has fitness {}",
                (endTime - startTime),
                currentBudget,
                suite.getFitness());
        // Search is finished, send statistics
        sendExecutionStatistics();

        LoggingUtils.getEvoLogger().info("* Covered {}/{} goals", coveredGoals, goals.size());
        logger.info("Resulting test suite: {} tests, length {}", suite.size(),
                suite.totalLengthOfTestCases());


        return suite;
    }

    private Set<Integer> getAdditionallyCoveredGoals(
            List<? extends TestFitnessFunction> goals, Set<Integer> covered,
            TestChromosome best) {

        Set<Integer> r = new HashSet<>();
        ExecutionResult result = best.getLastExecutionResult();
        assert (result != null);

        int num = -1;
        for (TestFitnessFunction goal : goals) {
            num++;
            if (covered.contains(num)) {
                continue;
            }
            if (goal.isCovered(best, result)) {
                r.add(num);
                if (Properties.PRINT_COVERED_GOALS) {
                    LoggingUtils.getEvoLogger().info("* Additionally covered: {}", goal);
                }
            }
        }
        return r;
    }

    private TestSuiteChromosome bootstrapRandomSuite(TestFitnessFactory<?> goals) {

        if (ArrayUtil.contains(Properties.CRITERION, Criterion.DEFUSE)
                || ArrayUtil.contains(Properties.CRITERION, Criterion.ALLDEFS)) {
            LoggingUtils.getEvoLogger().info("* Disabled random bootstraping for dataflow criterion");
            Properties.RANDOM_TESTS = 0;
        }

        if (Properties.RANDOM_TESTS > 0) {
            LoggingUtils.getEvoLogger().info("* Bootstrapping initial random test suite");
        }

        FixedSizeTestSuiteChromosomeFactory factory =
                new FixedSizeTestSuiteChromosomeFactory(Properties.RANDOM_TESTS);

        TestSuiteChromosome suite = factory.getChromosome();
        if (Properties.RANDOM_TESTS > 0) {
            TestSuiteMinimizer minimizer = new TestSuiteMinimizer(goals);
            minimizer.minimize(suite, true);
            LoggingUtils.getEvoLogger().info("* Initial test suite contains {} tests", suite.size());
        }

        return suite;
    }

}
