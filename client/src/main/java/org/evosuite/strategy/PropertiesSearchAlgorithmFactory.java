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
import org.evosuite.Properties.Criterion;
import org.evosuite.ShutdownTestWriter;
import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.branch.BranchPool;
import org.evosuite.ga.Chromosome;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.archive.ArchiveTestChromosomeFactory;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.ga.operators.crossover.CrossOverFunction;
import org.evosuite.ga.operators.crossover.SinglePointCrossOver;
import org.evosuite.ga.operators.crossover.SinglePointFixedCrossOver;
import org.evosuite.ga.operators.crossover.SinglePointRelativeCrossOver;
import org.evosuite.ga.operators.crossover.UniformCrossOver;
import org.evosuite.ga.operators.ranking.FastNonDominatedSorting;
import org.evosuite.ga.operators.ranking.RankBasedPreferenceSorting;
import org.evosuite.ga.operators.ranking.RankingFunction;
import org.evosuite.ga.operators.selection.BestKSelection;
import org.evosuite.ga.operators.selection.BinaryTournamentSelectionCrowdedComparison;
import org.evosuite.ga.operators.selection.FitnessProportionateSelection;
import org.evosuite.ga.operators.selection.RandomKSelection;
import org.evosuite.ga.operators.selection.RankSelection;
import org.evosuite.ga.operators.selection.SelectionFunction;
import org.evosuite.ga.operators.selection.TournamentSelection;
import org.evosuite.ga.operators.selection.TournamentSelectionRankAndCrowdingDistanceComparator;
import org.evosuite.ga.populationlimit.IndividualPopulationLimit;
import org.evosuite.ga.populationlimit.PopulationLimit;
import org.evosuite.ga.populationlimit.SizePopulationLimit;
import org.evosuite.ga.stoppingconditions.GlobalTimeStoppingCondition;
import org.evosuite.ga.stoppingconditions.MaxTimeStoppingCondition;
import org.evosuite.ga.stoppingconditions.RMIStoppingCondition;
import org.evosuite.ga.stoppingconditions.SocketStoppingCondition;
import org.evosuite.ga.stoppingconditions.StoppingCondition;
import org.evosuite.ga.stoppingconditions.StoppingConditionFactory;
import org.evosuite.ga.stoppingconditions.ZeroFitnessStoppingCondition;
import org.evosuite.llm.factory.LlmFactoryWrappers;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.factories.AllMethodsTestChromosomeFactory;
import org.evosuite.testcase.factories.JUnitTestCarvedChromosomeFactory;
import org.evosuite.testcase.factories.JUnitTestParsedChromosomeFactory;
import org.evosuite.testcase.factories.RandomLengthTestFactory;
import org.evosuite.utils.ArrayUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory class for GAs.
 *
 * @param <T> the {@code Chromosome} type used by the genetic algorithm
 * @author gordon
 */
public abstract class PropertiesSearchAlgorithmFactory<T extends Chromosome<T>> {

    protected static final Logger logger = LoggerFactory.getLogger(PropertiesSearchAlgorithmFactory.class);

    protected PopulationLimit<T> getPopulationLimit() {
        switch (Properties.POPULATION_LIMIT) {
            case INDIVIDUALS:
                return new IndividualPopulationLimit<>();
            case TESTS:
                return new SizePopulationLimit<>();
            default:
                throw new RuntimeException("Unsupported population limit");
        }
    }

    protected StoppingCondition<T> getStoppingCondition() {
        logger.info("Setting stopping condition: {}", Properties.STOPPING_CONDITION);
        return StoppingConditionFactory.getStoppingCondition(Properties.STOPPING_CONDITION);
    }

    /**
     * Installs the configured stopping condition on the algorithm, followed by the zero-fitness
     * condition if requested and the global time condition unless the configured one already is a
     * time limit.
     */
    protected void setUpStoppingConditions(GeneticAlgorithm<T> ga, StoppingCondition<T> stoppingCondition) {
        ga.setStoppingCondition(stoppingCondition);
        if (Properties.STOP_ZERO) {
            ga.addStoppingCondition(new ZeroFitnessStoppingCondition<>());
        }
        if (!(stoppingCondition instanceof MaxTimeStoppingCondition)) {
            ga.addStoppingCondition(new GlobalTimeStoppingCondition<>());
        }
    }

    /**
     * Whether the selected criteria include mutation testing.
     */
    protected static boolean usesMutationCriterion() {
        return ArrayUtil.contains(Properties.CRITERION, Criterion.MUTATION)
                || ArrayUtil.contains(Properties.CRITERION, Criterion.STRONGMUTATION);
    }

    /**
     * Scales the search budget by the size of the class under test and applies it to the stopping
     * condition. This rewrites {@code Properties.SEARCH_BUDGET}.
     */
    protected static void applyDynamicLimit(StoppingCondition<?> stoppingCondition) {
        BranchPool pool = BranchPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT());
        Properties.SEARCH_BUDGET = Properties.SEARCH_BUDGET
                * (pool.getNumBranchlessMethods(Properties.TARGET_CLASS)
                + pool.getBranchCountForClass(Properties.TARGET_CLASS) * 2);
        stoppingCondition.setLimit(Properties.SEARCH_BUDGET);
        logger.info("Setting dynamic length limit to {}", Properties.SEARCH_BUDGET);
    }

    /**
     * Adds the conditions that end the search when the process is asked to shut down: the
     * shutdown writer, the RMI stop request and, if a port is configured, the stopping socket.
     */
    protected void addShutdownStoppingConditions(GeneticAlgorithm<T> ga) {
        ShutdownTestWriter<T> writer = new ShutdownTestWriter<>();
        ga.addStoppingCondition(writer);
        ga.addStoppingCondition(RMIStoppingCondition.<T>getInstance());

        if (Properties.STOPPING_PORT != -1) {
            SocketStoppingCondition<T> socket = SocketStoppingCondition.getInstance();
            socket.accept();
            ga.addStoppingCondition(socket);
        }

        writer.registerAsSignalHandler();
    }

    /**
     * The test case factory selected by {@code Properties.TEST_FACTORY}, wrapped for LLM seeding.
     */
    protected static ChromosomeFactory<TestChromosome> testChromosomeFactory() {
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
                throw new RuntimeException("Unsupported test factory: "
                        + Properties.TEST_FACTORY);
        }
        return LlmFactoryWrappers.wrap(factory);
    }

    /**
     * The configured selection function. Best-k and random-k selection are only available to
     * algorithms that ask for them; the others use rank selection for those values.
     */
    protected SelectionFunction<T> selectionFunction(boolean supportsKSelection) {
        switch (Properties.SELECTION_FUNCTION) {
            case ROULETTEWHEEL:
                return new FitnessProportionateSelection<>();
            case TOURNAMENT:
                return new TournamentSelection<>();
            case BINARY_TOURNAMENT:
                return new BinaryTournamentSelectionCrowdedComparison<>();
            case RANK_CROWD_DISTANCE_TOURNAMENT:
                return new TournamentSelectionRankAndCrowdingDistanceComparator<>();
            case BESTK:
                if (supportsKSelection) {
                    return new BestKSelection<>();
                }
                return new RankSelection<>();
            case RANDOMK:
                if (supportsKSelection) {
                    return new RandomKSelection<>();
                }
                return new RankSelection<>();
            default:
                return new RankSelection<>();
        }
    }

    /**
     * The configured crossover function. Coverage crossover is test suite specific and handled by
     * the suite factory; uniform crossover is only available to algorithms that ask for it.
     */
    protected CrossOverFunction<T> crossoverFunction(boolean supportsUniform) {
        switch (Properties.CROSSOVER_FUNCTION) {
            case SINGLEPOINTFIXED:
                return new SinglePointFixedCrossOver<>();
            case SINGLEPOINTRELATIVE:
                return new SinglePointRelativeCrossOver<>();
            case SINGLEPOINT:
                return new SinglePointCrossOver<>();
            case COVERAGE:
                throw new RuntimeException(
                        "Coverage crossover not supported in test case mode");
            case UNIFORM:
                if (supportsUniform) {
                    return new UniformCrossOver<>();
                }
                throw new RuntimeException("Unknown crossover function: "
                        + Properties.CROSSOVER_FUNCTION);
            default:
                throw new RuntimeException("Unknown crossover function: "
                        + Properties.CROSSOVER_FUNCTION);
        }
    }

    protected RankingFunction<T> rankingFunction() {
        switch (Properties.RANKING_TYPE) {
            case FAST_NON_DOMINATED_SORTING:
                return new FastNonDominatedSorting<>();
            case PREFERENCE_SORTING:
            default:
                return new RankBasedPreferenceSorting<>();
        }
    }

    public abstract GeneticAlgorithm<T> getSearchAlgorithm();
}
