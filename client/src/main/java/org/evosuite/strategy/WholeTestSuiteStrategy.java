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
import org.evosuite.TestSuiteGeneratorHelper;
import org.evosuite.coverage.TestFitnessFactory;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.ga.stoppingconditions.MaxStatementsStoppingCondition;
import org.evosuite.result.TestGenerationResultBuilder;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientState;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.evosuite.testsuite.similarity.DiversityObserver;
import org.evosuite.utils.ArrayUtil;
import org.evosuite.utils.LoggingUtils;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Regular whole test suite generation.
 *
 * @author gordon
 */
public class WholeTestSuiteStrategy extends TestGenerationStrategy {

    private static final Logger logger = LoggerFactory.getLogger(WholeTestSuiteStrategy.class);

    @Override
    public TestSuiteChromosome generateTests() {
        // Set up search algorithm
        LoggingUtils.getEvoLogger().info("* Setting up search algorithm for whole suite generation");
        PropertiesSuiteGAFactory algorithmFactory = new PropertiesSuiteGAFactory();
        GeneticAlgorithm<TestSuiteChromosome> algorithm = algorithmFactory.getSearchAlgorithm();

        if (Properties.SERIALIZE_GA || Properties.CLIENT_ON_THREAD) {
            TestGenerationResultBuilder.getInstance().setGeneticAlgorithm(algorithm);
        }

        long startTime = System.currentTimeMillis() / 1000;

        // What's the search target
        List<TestSuiteFitnessFunction> fitnessFunctions = getFitnessFunctions();

        algorithm.addFitnessFunctions(fitnessFunctions);

        // FIXME progressMonitor may cause client hang if EvoSuite is executed with -prefix!
        algorithm.addListener(getProgressMonitor());

        if (Properties.TRACK_DIVERSITY) {
            algorithm.addListener(new DiversityObserver());
        }

        if (ArrayUtil.contains(Properties.CRITERION, Criterion.DEFUSE)
                || ArrayUtil.contains(Properties.CRITERION, Criterion.ALLDEFS)
                || ArrayUtil.contains(Properties.CRITERION, Criterion.STATEMENT)
                || ArrayUtil.contains(Properties.CRITERION, Criterion.RHO)
                || ArrayUtil.contains(Properties.CRITERION, Criterion.AMBIGUITY)) {
            ExecutionTracer.enableTraceCalls();
        }

        algorithm.resetStoppingConditions();

        List<TestFitnessFunction> goals = getGoals(true);
        if (goals.isEmpty() && !ArrayUtil.contains(Properties.CRITERION, Criterion.EXCEPTION)) {
            LoggingUtils.getEvoLogger().info("* No coverage goals found for the target class {}",
                    Properties.TARGET_CLASS);
            ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Total_Goals, 0);
            return new TestSuiteChromosome();
        }

        if (!canGenerateTestsForSUT()) {
            LoggingUtils.getEvoLogger().info("* Found no testable methods in the target class {}",
                    Properties.TARGET_CLASS);
            ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Total_Goals, goals.size());

            return new TestSuiteChromosome();
        }
        TestSuiteChromosome testSuite;
        if (!(goals.isEmpty())
                || ArrayUtil.contains(Properties.CRITERION, Criterion.EXCEPTION)) {
            // Perform search
            LoggingUtils.getEvoLogger().info("* Using seed {}", Randomness.getSeed());
            LoggingUtils.getEvoLogger().info("* Starting evolution");
            ClientServices.getInstance().getClientNode().changeState(ClientState.SEARCH);

            algorithm.generateSolution();
            // TODO: Refactor MOO!
            testSuite = algorithm.getBestIndividual();
        } else {
            getZeroFitness().setFinished();
            testSuite = new TestSuiteChromosome();
            for (FitnessFunction<TestSuiteChromosome> ff : fitnessFunctions) {
                testSuite.setCoverage(ff, 1.0);
            }
        }

        long endTime = System.currentTimeMillis() / 1000;

        goals = getGoals(false); //recalculated now after the search, eg to handle exception fitness
        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Total_Goals, goals.size());

        // Newline after progress bar
        if (Properties.SHOW_PROGRESS) {
            LoggingUtils.getEvoLogger().info("");
        }

        // avoid printing time related info in system tests due to lack of determinism
        if (!Properties.IS_RUNNING_A_SYSTEM_TEST) {
            LoggingUtils.getEvoLogger().info(
                    "* Search finished after {}s and {} generations, {} statements, best individual has fitness: {}",
                    (endTime - startTime),
                    algorithm.getAge(),
                    MaxStatementsStoppingCondition.getNumExecutedStatements(),
                    testSuite.getFitness());
        }

        // Search is finished, send statistics
        sendExecutionStatistics();

        return testSuite;
    }

    private List<TestFitnessFunction> getGoals(boolean verbose) {
        List<TestFitnessFactory<? extends TestFitnessFunction>> goalFactories = getFitnessFactories();
        List<TestFitnessFunction> goals = new ArrayList<>();

        if (goalFactories.size() == 1) {
            TestFitnessFactory<? extends TestFitnessFunction> factory = goalFactories.iterator().next();
            goals.addAll(factory.getCoverageGoals());

            if (verbose) {
                LoggingUtils.getEvoLogger().info("* Total number of test goals: {}", factory.getCoverageGoals().size());
                if (Properties.PRINT_GOALS) {
                    for (TestFitnessFunction goal : factory.getCoverageGoals()) {
                        LoggingUtils.getEvoLogger().info("{}", goal);
                    }
                }
            }
        } else {
            if (verbose) {
                LoggingUtils.getEvoLogger().info("* Total number of test goals: ");
            }

            for (int i = 0; i < goalFactories.size(); i++) {
                TestFitnessFactory<? extends TestFitnessFunction> goalFactory = goalFactories.get(i);
                goals.addAll(goalFactory.getCoverageGoals());

                if (verbose) {
                    LoggingUtils.getEvoLogger().info("  - {} {}",
                            TestSuiteGeneratorHelper.getCriterionDisplayName(Properties.CRITERION[i]),
                            goalFactory.getCoverageGoals().size());
                    if (Properties.PRINT_GOALS) {
                        for (TestFitnessFunction goal : goalFactory.getCoverageGoals()) {
                            LoggingUtils.getEvoLogger().info("{}", goal);
                        }
                    }
                }
            }
        }
        return goals;
    }
}
