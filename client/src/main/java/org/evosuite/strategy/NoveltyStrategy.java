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
package org.evosuite.strategy;

import org.evosuite.Properties;
import org.evosuite.coverage.FitnessFunctions;
import org.evosuite.coverage.FitnessFunctionsUtils;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.archive.Archive;
import org.evosuite.ga.metaheuristics.NoveltySearch;
import org.evosuite.novelty.BranchNoveltyFunction;
import org.evosuite.novelty.SuiteFitnessEvaluationListener;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientState;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.evosuite.utils.ArrayUtil;
import org.evosuite.utils.LoggingUtils;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Strategy for Novelty Search.
 */
public class NoveltyStrategy extends TestGenerationStrategy {

    private static final Logger logger = LoggerFactory.getLogger(NoveltyStrategy.class);


    @Override
    public TestSuiteChromosome generateTests() {
        // Set up search algorithm
        LoggingUtils.getEvoLogger().info("* Setting up search algorithm for novelty search");

        PropertiesNoveltySearchFactory algorithmFactory = new PropertiesNoveltySearchFactory();
        NoveltySearch algorithm = algorithmFactory.getSearchAlgorithm();
        //NoveltySearch<TestChromosome, TestSuiteChromosome> algorithm =
        // new NoveltySearch<TestChromosome, TestSuiteChromosome>(chromosomeFactory);

        registerForResult(algorithm);

        long startTime = System.currentTimeMillis() / 1000;

        // What's the search target
        List<TestSuiteFitnessFunction> fitnessFunctions = getFitnessFunctions();

        SuiteFitnessEvaluationListener listener = new SuiteFitnessEvaluationListener(fitnessFunctions);
        algorithm.addListener(listener);
        algorithm.setNoveltyFunction(new BranchNoveltyFunction());

        // if (Properties.SHOW_PROGRESS && !logger.isInfoEnabled())
        //algorithm.addListener(progressMonitor); // FIXME progressMonitor expects testsuitechromosomes

        if (Properties.TRACK_DIVERSITY) {
            // The DiversityObserver is only implemented for test suites, so we can't use it here
            // algorithm.addListener(new DiversityObserver());
            throw new RuntimeException("Tracking population diversity is not supported by novelty search");
        }
        // ===========================================================================================

        if (FitnessFunctions.requiresTraceCalls(Properties.CRITERION)) {
            ExecutionTracer.enableTraceCalls();
        }

        // TODO: why it was only if "analyzing"???
        // if (analyzing)
        algorithm.resetStoppingConditions();

        List<TestFitnessFunction> goals = FitnessFunctionsUtils.getFitnessFunctionsGoals(Properties.CRITERION, true);
        if (!canGenerateTestsForSUT()) {
            LoggingUtils.getEvoLogger().info("* Found no testable methods in the target class {}",
                    Properties.TARGET_CLASS);
            ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Total_Goals, goals.size());

            return new TestSuiteChromosome();
        }

        /*
         * Proceed with search if CRITERION=EXCEPTION, even if goals is empty
         */
        TestSuiteChromosome testSuite = null;
        if (!(Properties.STOP_ZERO && goals.isEmpty())
                || ArrayUtil.contains(Properties.CRITERION, Properties.Criterion.EXCEPTION)) {
            // Perform search
            LoggingUtils.getEvoLogger().info("* Using seed {}", Randomness.getSeed());
            LoggingUtils.getEvoLogger().info("* Starting evolution");
            ClientServices.getInstance().getClientNode().changeState(ClientState.SEARCH);

            algorithm.generateSolution();
            testSuite = Archive.getArchiveInstance().mergeArchiveAndSolution(new TestSuiteChromosome());
        } else {
            testSuite = new TestSuiteChromosome();
            for (FitnessFunction<TestSuiteChromosome> ff : fitnessFunctions) {
                testSuite.setCoverage(ff, 1.0);
            }
        }

        long endTime = System.currentTimeMillis() / 1000;

        goals = FitnessFunctionsUtils.getFitnessFunctionsGoals(Properties.CRITERION, false); //recalculated now after the search, eg to handle exception fitness
        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Total_Goals, goals.size());

        // Newline after progress bar
        if (Properties.SHOW_PROGRESS) {
            LoggingUtils.getEvoLogger().info("");
        }

        logSearchFinished(startTime, endTime, algorithm, testSuite);

        // Search is finished, send statistics
        sendExecutionStatistics();

        return testSuite;
    }
}
