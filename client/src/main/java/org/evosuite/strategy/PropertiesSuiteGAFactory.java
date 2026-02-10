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
import org.evosuite.Properties.Strategy;
import org.evosuite.Properties.TheReplacementFunction;
import org.evosuite.ShutdownTestWriter;
import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.branch.BranchPool;
import org.evosuite.coverage.mutation.MutationTimeoutStoppingCondition;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.FitnessReplacementFunction;
import org.evosuite.ga.archive.ArchiveTestChromosomeFactory;
import org.evosuite.ga.metaheuristics.BreederGA;
import org.evosuite.ga.metaheuristics.CellularGA;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.ga.metaheuristics.LIPS;
import org.evosuite.ga.metaheuristics.LIPSTestSuiteAdapter;
import org.evosuite.ga.metaheuristics.MIO;
import org.evosuite.ga.metaheuristics.MIOTestSuiteAdapter;
import org.evosuite.ga.metaheuristics.MonotonicGA;
import org.evosuite.ga.metaheuristics.NSGAII;
import org.evosuite.ga.metaheuristics.RandomSearch;
import org.evosuite.ga.metaheuristics.SPEA2;
import org.evosuite.ga.metaheuristics.StandardChemicalReaction;
import org.evosuite.ga.metaheuristics.StandardGA;
import org.evosuite.ga.metaheuristics.SteadyStateGA;
import org.evosuite.ga.metaheuristics.mosa.DynaMOSA;
import org.evosuite.ga.metaheuristics.mosa.MOSA;
import org.evosuite.ga.metaheuristics.mosa.MOSATestSuiteAdapter;
import org.evosuite.ga.metaheuristics.mulambda.MuLambdaEA;
import org.evosuite.ga.metaheuristics.mulambda.MuPlusLambdaEA;
import org.evosuite.ga.metaheuristics.mulambda.OnePlusLambdaLambdaGA;
import org.evosuite.ga.metaheuristics.mulambda.OnePlusOneEA;
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
import org.evosuite.ga.populationlimit.PopulationLimit;
import org.evosuite.ga.stoppingconditions.GlobalTimeStoppingCondition;
import org.evosuite.ga.stoppingconditions.MaxTimeStoppingCondition;
import org.evosuite.ga.stoppingconditions.RMIStoppingCondition;
import org.evosuite.ga.stoppingconditions.SocketStoppingCondition;
import org.evosuite.ga.stoppingconditions.StoppingCondition;
import org.evosuite.ga.stoppingconditions.ZeroFitnessStoppingCondition;
import org.evosuite.statistics.StatisticsListener;
import org.evosuite.testcase.factories.AllMethodsTestChromosomeFactory;
import org.evosuite.testcase.factories.JUnitTestCarvedChromosomeFactory;
import org.evosuite.testcase.factories.RandomLengthTestFactory;
import org.evosuite.testcase.localsearch.BranchCoverageMap;
import org.evosuite.testsuite.RelativeSuiteLengthBloatControl;
import org.evosuite.testsuite.StatementsPopulationLimit;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteReplacementFunction;
import org.evosuite.testsuite.factories.SerializationSuiteChromosomeFactory;
import org.evosuite.testsuite.factories.TestSuiteChromosomeFactory;
import org.evosuite.testsuite.secondaryobjectives.TestSuiteSecondaryObjective;
import org.evosuite.utils.ArrayUtil;
import org.evosuite.utils.ResourceController;

/**
 * Factory for GA on test suites.
 *
 * @author gordon
 */
public class PropertiesSuiteGAFactory
        extends PropertiesSearchAlgorithmFactory<TestSuiteChromosome> {

    protected ChromosomeFactory<TestSuiteChromosome> getChromosomeFactory() {
        switch (Properties.STRATEGY) {
            case EVOSUITE:
                switch (Properties.TEST_FACTORY) {
                    case ALLMETHODS:
                        logger.info("Using all methods chromosome factory");
                        return new TestSuiteChromosomeFactory(
                                new AllMethodsTestChromosomeFactory());
                    case RANDOM:
                        logger.info("Using random chromosome factory");
                        return new TestSuiteChromosomeFactory(new RandomLengthTestFactory());
                    case ARCHIVE:
                        logger.info("Using archive chromosome factory");
                        return new TestSuiteChromosomeFactory(new ArchiveTestChromosomeFactory());
                    case JUNIT:
                        logger.info("Using seeding chromosome factory");
                        JUnitTestCarvedChromosomeFactory factory = new JUnitTestCarvedChromosomeFactory(
                                new RandomLengthTestFactory());
                        return new TestSuiteChromosomeFactory(factory);
                    case SERIALIZATION:
                        logger.info("Using serialization seeding chromosome factory");
                        return new SerializationSuiteChromosomeFactory(
                                new RandomLengthTestFactory());
                    default:
                        throw new RuntimeException("Unsupported test factory: "
                                + Properties.TEST_FACTORY);
                }
            case MOSUITE:
                return new TestSuiteChromosomeFactory(new RandomLengthTestFactory());
            default:
                throw new RuntimeException("Unsupported test factory: "
                        + Properties.TEST_FACTORY);
        }
    }

    @Override
    protected PopulationLimit<TestSuiteChromosome> getPopulationLimit() {
        return Properties.POPULATION_LIMIT == Properties.PopulationLimit.STATEMENTS
                ? new StatementsPopulationLimit<>()
                : super.getPopulationLimit();
    }

    protected GeneticAlgorithm<TestSuiteChromosome> getGeneticAlgorithm(
            ChromosomeFactory<TestSuiteChromosome> factory) {
        switch (Properties.ALGORITHM) {
            case ONE_PLUS_ONE_EA:
                logger.info("Chosen search algorithm: (1+1)EA");
                return new OnePlusOneEA<>(factory);
            case MU_PLUS_LAMBDA_EA:
                logger.info("Chosen search algorithm: (Mu+Lambda)EA");
                return new MuPlusLambdaEA<>(factory, Properties.MU, Properties.LAMBDA);
            case MU_LAMBDA_EA:
                logger.info("Chosen search algorithm: (Mu,Lambda)EA");
                return new MuLambdaEA<>(factory, Properties.MU,
                        Properties.LAMBDA);
            case MONOTONIC_GA: {
                logger.info("Chosen search algorithm: MonotonicGA");
                MonotonicGA<TestSuiteChromosome> ga = new MonotonicGA<>(factory);
                if (Properties.REPLACEMENT_FUNCTION
                        == TheReplacementFunction.FITNESSREPLACEMENT) {
                    // user has explicitly asked for this replacement function
                    ga.setReplacementFunction(new FitnessReplacementFunction<>());
                } else {
                    // use default
                    ga.setReplacementFunction(new TestSuiteReplacementFunction());
                }
                return ga;
            }
            case CELLULAR_GA: {
                logger.info("Chosen search algorithm: CellularGA");
                CellularGA<TestSuiteChromosome> ga = new CellularGA<>(Properties.MODEL, factory);
                if (Properties.REPLACEMENT_FUNCTION == TheReplacementFunction.FITNESSREPLACEMENT) {
                    // user has explicitly asked for this replacement function
                    ga.setReplacementFunction(new FitnessReplacementFunction<>());
                } else {
                    // use default
                    ga.setReplacementFunction(new TestSuiteReplacementFunction());
                }
                return ga;
            }
            case STEADY_STATE_GA: {
                logger.info("Chosen search algorithm: Steady-StateGA");
                SteadyStateGA<TestSuiteChromosome> ga = new SteadyStateGA<>(factory);
                if (Properties.REPLACEMENT_FUNCTION == TheReplacementFunction.FITNESSREPLACEMENT) {
                    // user has explicitly asked for this replacement function
                    ga.setReplacementFunction(new FitnessReplacementFunction<>());
                } else {
                    // use default
                    ga.setReplacementFunction(new TestSuiteReplacementFunction());
                }
                return ga;
            }
            case BREEDER_GA:
                logger.info("Chosen search algorithm: BreederGA");
                return new BreederGA<>(factory);
            case RANDOM_SEARCH:
                logger.info("Chosen search algorithm: Random");
                return new RandomSearch<>(factory);
            case NSGAII:
                logger.info("Chosen search algorithm: NSGAII");
                return new NSGAII<>(factory);
            case SPEA2:
                logger.info("Chosen search algorithm: SPEA2");
                return new SPEA2<>(factory);
            case MOSA:
                logger.info("Chosen search algorithm: MOSA");
                if (factory instanceof TestSuiteChromosomeFactory) {
                    final TestSuiteChromosomeFactory tscf = (TestSuiteChromosomeFactory) factory;
                    return new MOSATestSuiteAdapter(new MOSA(tscf.getTestChromosomeFactory()));
                } else {
                    logger.info("No specific factory for test cases given...");
                    logger.info("Using a default factory that creates tests with variable length");
                    return new MOSATestSuiteAdapter(new MOSA(new RandomLengthTestFactory()));
                }
            case DYNAMOSA:
                if (!ArrayUtil.contains(Properties.CRITERION, Criterion.BRANCH)) {
                    logger.warn("DynaMOSA requires BRANCH in Properties.CRITERION for structural "
                            + "dependency tracking. Falling back to MOSA.");
                    if (factory instanceof TestSuiteChromosomeFactory) {
                        final TestSuiteChromosomeFactory tscf = (TestSuiteChromosomeFactory) factory;
                        return new MOSATestSuiteAdapter(new MOSA(tscf.getTestChromosomeFactory()));
                    } else {
                        return new MOSATestSuiteAdapter(new MOSA(new RandomLengthTestFactory()));
                    }
                }
                logger.info("Chosen search algorithm: DynaMOSA");
                if (factory instanceof TestSuiteChromosomeFactory) {
                    final TestSuiteChromosomeFactory tscf = (TestSuiteChromosomeFactory) factory;
                    return new MOSATestSuiteAdapter(new DynaMOSA(tscf.getTestChromosomeFactory()));
                } else {
                    logger.info("No specific factory for test cases given...");
                    logger.info("Using a default factory that creates tests with variable length");
                    return new MOSATestSuiteAdapter(new DynaMOSA(new RandomLengthTestFactory()));
                }
            case ONE_PLUS_LAMBDA_LAMBDA_GA:
                logger.info("Chosen search algorithm: 1 + (lambda, lambda)GA");
                return new OnePlusLambdaLambdaGA<>(factory, Properties.LAMBDA);
            case MIO:
                logger.info("Chosen search algorithm: MIO");
                if (factory instanceof TestSuiteChromosomeFactory) {
                    final TestSuiteChromosomeFactory tscf = (TestSuiteChromosomeFactory) factory;
                    return new MIOTestSuiteAdapter(new MIO(tscf.getTestChromosomeFactory()));
                } else {
                    logger.info("No specific factory for test cases given...");
                    logger.info("Using a default factory that creates tests with variable length");
                    return new MIOTestSuiteAdapter(new MIO(new RandomLengthTestFactory()));
                }
            case STANDARD_CHEMICAL_REACTION:
                logger.info("Chosen search algorithm: Standard Chemical Reaction Optimization");
                return new StandardChemicalReaction<>(factory);
            case MAP_ELITES:
                logger.info("Chosen search algorithm: MAP-Elites");
                throw new RuntimeException("MAPElites only works on TestChromosome, not on TestSuiteChromosome");
            case LIPS:
                logger.info("Chosen search algorithm: LIPS");
                if (factory instanceof TestSuiteChromosomeFactory) {
                    final TestSuiteChromosomeFactory tscf = (TestSuiteChromosomeFactory) factory;
                    return new LIPSTestSuiteAdapter(new LIPS(tscf.getTestChromosomeFactory()));
                } else {
                    logger.info("No specific factory for test cases given...");
                    logger.info("Using a default factory that creates tests with variable length");
                    return new LIPSTestSuiteAdapter(new LIPS(new RandomLengthTestFactory()));
                }
            default:
                logger.info("Chosen search algorithm: StandardGA");
                return new StandardGA<>(factory);
        }
    }

    protected SelectionFunction<TestSuiteChromosome> getSelectionFunction() {
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
                return new BestKSelection<>();
            case RANDOMK:
                return new RandomKSelection<>();
            default:
                return new RankSelection<>();
        }
    }

    protected CrossOverFunction<TestSuiteChromosome> getCrossoverFunction() {
        switch (Properties.CROSSOVER_FUNCTION) {
            case SINGLEPOINTFIXED:
                return new SinglePointFixedCrossOver<>();
            case SINGLEPOINTRELATIVE:
                return new SinglePointRelativeCrossOver<>();
            case SINGLEPOINT:
                return new SinglePointCrossOver<>();
            case COVERAGE:
                if (Properties.STRATEGY != Properties.Strategy.EVOSUITE) {
                    throw new RuntimeException("Coverage crossover function requires test suite mode");
                }
                return new org.evosuite.ga.operators.crossover.CoverageCrossOver();
            case UNIFORM:
                return new UniformCrossOver<>();
            default:
                throw new RuntimeException("Unknown crossover function: "
                        + Properties.CROSSOVER_FUNCTION);
        }
    }

    private RankingFunction<TestSuiteChromosome> getRankingFunction() {
        switch (Properties.RANKING_TYPE) {
            case FAST_NON_DOMINATED_SORTING:
                return new FastNonDominatedSorting<>();
            case PREFERENCE_SORTING:
            default:
                return new RankBasedPreferenceSorting<>();
        }
    }

    @Override
    public GeneticAlgorithm<TestSuiteChromosome> getSearchAlgorithm() {
        ChromosomeFactory<TestSuiteChromosome> factory = getChromosomeFactory();

        GeneticAlgorithm<TestSuiteChromosome> ga = getGeneticAlgorithm(factory);

        if (Properties.NEW_STATISTICS) {
            ga.addListener(new StatisticsListener<>());
        }

        // How to select candidates for reproduction
        SelectionFunction<TestSuiteChromosome> selectionFunction = getSelectionFunction();
        selectionFunction.setMaximize(false);
        ga.setSelectionFunction(selectionFunction);

        RankingFunction<TestSuiteChromosome> rankingFunction = getRankingFunction();
        ga.setRankingFunction(rankingFunction);

        // When to stop the search
        StoppingCondition<TestSuiteChromosome> stoppingCondition = getStoppingCondition();
        ga.setStoppingCondition(stoppingCondition);

        if (Properties.STOP_ZERO) {
            ga.addStoppingCondition(new ZeroFitnessStoppingCondition<>());
        }

        if (!(stoppingCondition instanceof MaxTimeStoppingCondition)) {
            ga.addStoppingCondition(new GlobalTimeStoppingCondition<>());
        }

        if (ArrayUtil.contains(Properties.CRITERION, Criterion.MUTATION)
                || ArrayUtil.contains(Properties.CRITERION, Criterion.STRONGMUTATION)) {
            if (Properties.STRATEGY == Strategy.ONEBRANCH) {
                ga.addStoppingCondition(new MutationTimeoutStoppingCondition<>());
            }
        }
        ga.resetStoppingConditions();
        ga.setPopulationLimit(getPopulationLimit());

        // How to cross over
        CrossOverFunction<TestSuiteChromosome> crossoverFunction = getCrossoverFunction();
        ga.setCrossOverFunction(crossoverFunction);

        if (Properties.CHECK_BEST_LENGTH) {
            RelativeSuiteLengthBloatControl<TestSuiteChromosome> bloatControl =
                    new RelativeSuiteLengthBloatControl<>();
            ga.addBloatControl(bloatControl);
            ga.addListener(bloatControl);
        }

        TestSuiteSecondaryObjective.setSecondaryObjectives();

        if (Properties.DYNAMIC_LIMIT) {
            // max_s = GAProperties.generations * getBranches().size();
            // TODO: might want to make this dependent on the selected coverage criterion
            // TODO also, question: is branchMap.size() really intended here?
            // I think BranchPool.getBranchCount() was intended
            Properties.SEARCH_BUDGET = Properties.SEARCH_BUDGET
                    * (BranchPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                    .getNumBranchlessMethods(Properties.TARGET_CLASS)
                    + BranchPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                    .getBranchCountForClass(Properties.TARGET_CLASS) * 2);
            stoppingCondition.setLimit(Properties.SEARCH_BUDGET);
            logger.info("Setting dynamic length limit to {}", Properties.SEARCH_BUDGET);
        }

        if (Properties.LOCAL_SEARCH_RESTORE_COVERAGE) {
            ga.addListener(BranchCoverageMap.getInstance());
        }

        if (Properties.SHUTDOWN_HOOK) {
            // ShutdownTestWriter writer = new
            // ShutdownTestWriter(Thread.currentThread());
            ShutdownTestWriter<TestSuiteChromosome> writer = new ShutdownTestWriter<>();
            ga.addStoppingCondition(writer);
            RMIStoppingCondition<TestSuiteChromosome> rmi = RMIStoppingCondition.getInstance();
            ga.addStoppingCondition(rmi);

            if (Properties.STOPPING_PORT != -1) {
                SocketStoppingCondition<TestSuiteChromosome> ss =
                        SocketStoppingCondition.getInstance();
                ss.accept();
                ga.addStoppingCondition(ss);
            }

            // Runtime.getRuntime().addShutdownHook(writer);
            writer.registerAsSignalHandler();
        }

        ga.addListener(new ResourceController<>());
        return ga;
    }


}
