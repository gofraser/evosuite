/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * <p>
 * This file is part of EvoSuite.
 * </p>
 *
 * <p>
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 * </p>
 *
 * <p>
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 * </p>
 *
 * <p>
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 * </p>
 */
package org.evosuite.strategy;

import org.evosuite.Properties;
import org.evosuite.ShutdownTestWriter;
import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.branch.BranchPool;
import org.evosuite.coverage.mutation.MutationTimeoutStoppingCondition;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.archive.ArchiveTestChromosomeFactory;
import org.evosuite.ga.metaheuristics.mapelites.MAPElites;
import org.evosuite.ga.stoppingconditions.GlobalTimeStoppingCondition;
import org.evosuite.ga.stoppingconditions.MaxTimeStoppingCondition;
import org.evosuite.ga.stoppingconditions.RMIStoppingCondition;
import org.evosuite.ga.stoppingconditions.SocketStoppingCondition;
import org.evosuite.ga.stoppingconditions.StoppingCondition;
import org.evosuite.ga.stoppingconditions.ZeroFitnessStoppingCondition;
import org.evosuite.llm.factory.LlmSeededPopulationFactory;
import org.evosuite.llm.factory.LlmTestChromosomeFactory;
import org.evosuite.statistics.StatisticsListener;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.factories.AllMethodsTestChromosomeFactory;
import org.evosuite.testcase.factories.JUnitTestCarvedChromosomeFactory;
import org.evosuite.testcase.factories.JUnitTestParsedChromosomeFactory;
import org.evosuite.testcase.factories.RandomLengthTestFactory;
import org.evosuite.testcase.secondaryobjectives.TestCaseSecondaryObjective;
import org.evosuite.testsuite.RelativeSuiteLengthBloatControl;
import org.evosuite.utils.ArrayUtil;
import org.evosuite.utils.ResourceController;

/**
 * Factory for MAP Elites Search.
 */
public class PropertiesMapElitesSearchFactory
        extends PropertiesSearchAlgorithmFactory<TestChromosome> {

    private ChromosomeFactory<TestChromosome> getChromosomeFactory() {
        ChromosomeFactory<TestChromosome> factory;
        switch (Properties.TEST_FACTORY) {
            case ALLMETHODS:
                logger.info("Using all methods chromosome factory");
                factory = new AllMethodsTestChromosomeFactory();
                break;
            case RANDOM:
                logger.info("Using random chromosome factory");
                factory = new RandomLengthTestFactory();
                break;
            case ARCHIVE:
                logger.info("Using archive chromosome factory");
                factory = new ArchiveTestChromosomeFactory();
                break;
            case JUNIT:
                logger.info("Using seeding chromosome factory");
                factory = new JUnitTestCarvedChromosomeFactory(new RandomLengthTestFactory());
                break;
            case PARSED_JUNIT:
                logger.info("Using parsed JUnit seeding chromosome factory");
                factory = new JUnitTestParsedChromosomeFactory(new RandomLengthTestFactory());
                break;
            case SERIALIZATION:
                logger.info("Using serialization seeding chromosome factory");
                factory = new RandomLengthTestFactory();
                break;
            case LLM:
                logger.info("Using LLM chromosome factory with random fallback");
                factory = new RandomLengthTestFactory();
                break;
            default:
                throw new RuntimeException("Unsupported test factory: " + Properties.TEST_FACTORY);
        }
        if (Properties.LLM_SEED_INITIAL_POPULATION) {
            factory = new LlmSeededPopulationFactory(factory);
        }
        if (Properties.LLM_TEST_FACTORY || Properties.TEST_FACTORY == Properties.TestFactory.LLM) {
            factory = new LlmTestChromosomeFactory(factory);
        }
        return factory;
    }

    @Override
    public MAPElites getSearchAlgorithm() {
        ChromosomeFactory<TestChromosome> factory = getChromosomeFactory();
        MAPElites ga = new MAPElites(factory);

        if (Properties.NEW_STATISTICS) {
            ga.addListener(new StatisticsListener<>());
        }

        // When to stop the search
        StoppingCondition<TestChromosome> stoppingCondition = getStoppingCondition();
        ga.setStoppingCondition(stoppingCondition);

        if (Properties.STOP_ZERO) {
            ga.addStoppingCondition(new ZeroFitnessStoppingCondition<>());
        }

        if (!(stoppingCondition instanceof MaxTimeStoppingCondition)) {
            ga.addStoppingCondition(new GlobalTimeStoppingCondition<>());
        }

        if (ArrayUtil.contains(Properties.CRITERION, Properties.Criterion.MUTATION)
                || ArrayUtil.contains(Properties.CRITERION, Properties.Criterion.STRONGMUTATION)) {
            if (Properties.STRATEGY == Properties.Strategy.ONEBRANCH) {
                ga.addStoppingCondition(new MutationTimeoutStoppingCondition<>());
            } else {
                // ===========================================================================================
                // FIXME: The following line contains a type error.
                //  MutationTestPool is defined on TestSuiteChromosomes but the GA expects TestChromosomes.
                //  ga.addListener(new MutationTestPool());
                throw new RuntimeException("Broken code :(");
                // ===========================================================================================
            }
        }
        ga.resetStoppingConditions();

        if (Properties.CHECK_BEST_LENGTH) {
            RelativeSuiteLengthBloatControl<TestChromosome> bloatControl =
                    new RelativeSuiteLengthBloatControl<>();
            ga.addBloatControl(bloatControl);
            ga.addListener(bloatControl);
        }

        TestCaseSecondaryObjective.setSecondaryObjectives();

        if (Properties.DYNAMIC_LIMIT) {
            // max_s = GAProperties.generations * getBranches().size();
            // TODO: might want to make this dependent on the selected coverage
            // criterion
            // TODO also, question: is branchMap.size() really intended here?
            // I think BranchPool.getBranchCount() was intended
            Properties.SEARCH_BUDGET = Properties.SEARCH_BUDGET
                    * (BranchPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                    .getNumBranchlessMethods(Properties.TARGET_CLASS)
                    + BranchPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                    .getBranchCountForClass(Properties.TARGET_CLASS) * 2);
            stoppingCondition.setLimit(Properties.SEARCH_BUDGET);
            logger.info("Setting dynamic length limit to " + Properties.SEARCH_BUDGET);
        }

        if (Properties.LOCAL_SEARCH_RESTORE_COVERAGE) {
            // ===========================================================================================
            // FIXME: The following line contains a type error.
            //  BranchCoverageMap is defined on TestSuiteChromosomes but the GA expects TestChromosomes.
            //  SearchListener<TestChromosome> map = BranchCoverageMap.getInstance();
            //  ga.addListener(map);
            // Deliberately throwing an exception
            throw new RuntimeException("Broken code :(");
            // ===========================================================================================
        }

        if (Properties.SHUTDOWN_HOOK) {
            // ShutdownTestWriter writer = new
            // ShutdownTestWriter(Thread.currentThread());
            ShutdownTestWriter<TestChromosome> writer = new ShutdownTestWriter<>();
            ga.addStoppingCondition(writer);
            RMIStoppingCondition<TestChromosome> rmi = RMIStoppingCondition.getInstance();
            ga.addStoppingCondition(rmi);

            if (Properties.STOPPING_PORT != -1) {
                SocketStoppingCondition<TestChromosome> ss = SocketStoppingCondition.getInstance();
                ss.accept();
                ga.addStoppingCondition(ss);
            }

            writer.registerAsSignalHandler();
        }

        ga.addListener(new ResourceController<>());
        return ga;
    }
}
