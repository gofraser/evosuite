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
import org.evosuite.Properties.TheReplacementFunction;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.FitnessReplacementFunction;
import org.evosuite.ga.ReplacementFunction;
import org.evosuite.ga.metaheuristics.BreederGA;
import org.evosuite.ga.metaheuristics.CellularGA;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
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
import org.evosuite.ga.operators.crossover.CoverageCrossOver;
import org.evosuite.ga.operators.crossover.CrossOverFunction;
import org.evosuite.ga.operators.ranking.RankingFunction;
import org.evosuite.ga.operators.selection.SelectionFunction;
import org.evosuite.ga.populationlimit.PopulationLimit;
import org.evosuite.ga.stoppingconditions.StoppingCondition;
import org.evosuite.llm.factory.LlmFactoryWrappers;
import org.evosuite.llm.search.TestSuiteChromosomeInjectionAdapter;
import org.evosuite.statistics.StatisticsListener;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.factories.JUnitTestParsedChromosomeFactory;
import org.evosuite.testcase.factories.RandomLengthTestFactory;
import org.evosuite.testcase.factories.SerializedSeedTestFactory;
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
                ChromosomeFactory<TestChromosome> testFactory = testChromosomeFactory();
                return Properties.TEST_FACTORY == Properties.TestFactory.SERIALIZATION
                        ? new SerializationSuiteChromosomeFactory(testFactory)
                        : new TestSuiteChromosomeFactory(testFactory);
            case MOSUITE:
                // Tests continuous test generation kept from an earlier run, if any, seed the search.
                // Beyond that, the many-objective algorithms honour only parsed-source seeding:
                // every other TEST_FACTORY value, including the default ARCHIVE, keeps the
                // random-length factory so that the default search is unchanged.
                ChromosomeFactory<TestChromosome> moFactory =
                        SerializedSeedTestFactory.withCtgSeeds(new RandomLengthTestFactory());
                if (Properties.TEST_FACTORY == Properties.TestFactory.PARSED_JUNIT) {
                    logger.info("Using parsed JUnit seeding chromosome factory");
                    moFactory = new JUnitTestParsedChromosomeFactory(moFactory);
                }
                return new TestSuiteChromosomeFactory(LlmFactoryWrappers.wrap(moFactory));
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
                ga.setReplacementFunction(replacementFunction());
                return ga;
            }
            case CELLULAR_GA: {
                logger.info("Chosen search algorithm: CellularGA");
                CellularGA<TestSuiteChromosome> ga = new CellularGA<>(Properties.MODEL, factory);
                ga.setReplacementFunction(replacementFunction());
                return ga;
            }
            case STEADY_STATE_GA: {
                logger.info("Chosen search algorithm: Steady-StateGA");
                SteadyStateGA<TestSuiteChromosome> ga = new SteadyStateGA<>(factory);
                ga.setReplacementFunction(replacementFunction());
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
                return new MOSATestSuiteAdapter(new MOSA(testFactoryOf(factory, true)));
            case DYNAMOSA:
                if (!ArrayUtil.contains(Properties.CRITERION, Criterion.BRANCH)) {
                    logger.warn("DynaMOSA requires BRANCH in Properties.CRITERION for structural "
                            + "dependency tracking. Falling back to MOSA.");
                    return new MOSATestSuiteAdapter(new MOSA(testFactoryOf(factory, false)));
                }
                logger.info("Chosen search algorithm: DynaMOSA");
                return new MOSATestSuiteAdapter(new DynaMOSA(testFactoryOf(factory, true)));
            case ONE_PLUS_LAMBDA_LAMBDA_GA:
                logger.info("Chosen search algorithm: 1 + (lambda, lambda)GA");
                return new OnePlusLambdaLambdaGA<>(factory, Properties.LAMBDA);
            case MIO:
                logger.info("Chosen search algorithm: MIO");
                return new MIOTestSuiteAdapter(new MIO(testFactoryOf(factory, true)));
            case STANDARD_CHEMICAL_REACTION:
                logger.info("Chosen search algorithm: Standard Chemical Reaction Optimization");
                return new StandardChemicalReaction<>(factory);
            case MAP_ELITES:
                logger.info("Chosen search algorithm: MAP-Elites");
                throw new RuntimeException("MAPElites only works on TestChromosome, not on TestSuiteChromosome");
            default:
                logger.info("Chosen search algorithm: StandardGA");
                return new StandardGA<>(factory);
        }
    }

    /**
     * The configured replacement function: fitness replacement if explicitly requested, the test
     * suite replacement function otherwise.
     */
    private static ReplacementFunction<TestSuiteChromosome> replacementFunction() {
        if (Properties.REPLACEMENT_FUNCTION == TheReplacementFunction.FITNESSREPLACEMENT) {
            return new FitnessReplacementFunction<>();
        }
        return new TestSuiteReplacementFunction();
    }

    /**
     * The test case factory inside a suite factory, or a random-length factory if the suite
     * factory does not wrap one.
     *
     * @param logFallback whether to log the use of the fallback
     */
    private static ChromosomeFactory<TestChromosome> testFactoryOf(
            ChromosomeFactory<TestSuiteChromosome> factory, boolean logFallback) {
        if (factory instanceof TestSuiteChromosomeFactory) {
            return ((TestSuiteChromosomeFactory) factory).getTestChromosomeFactory();
        }
        if (logFallback) {
            logger.info("No specific factory for test cases given...");
            logger.info("Using a default factory that creates tests with variable length");
        }
        return new RandomLengthTestFactory();
    }

    protected CrossOverFunction<TestSuiteChromosome> getCrossoverFunction() {
        if (Properties.CROSSOVER_FUNCTION == Properties.CrossoverFunction.COVERAGE) {
            if (Properties.STRATEGY != Properties.Strategy.EVOSUITE) {
                throw new RuntimeException("Coverage crossover function requires test suite mode");
            }
            return new CoverageCrossOver();
        }
        return crossoverFunction(true);
    }

    @Override
    public GeneticAlgorithm<TestSuiteChromosome> getSearchAlgorithm() {
        ChromosomeFactory<TestSuiteChromosome> factory = getChromosomeFactory();

        GeneticAlgorithm<TestSuiteChromosome> ga = getGeneticAlgorithm(factory);
        ga.setLlmInjectionAdapter(new TestSuiteChromosomeInjectionAdapter());

        if (Properties.NEW_STATISTICS) {
            ga.addListener(new StatisticsListener<>());
        }

        SelectionFunction<TestSuiteChromosome> selectionFunction = selectionFunction(true);
        selectionFunction.setMaximize(false);
        ga.setSelectionFunction(selectionFunction);

        RankingFunction<TestSuiteChromosome> rankingFunction = rankingFunction();
        ga.setRankingFunction(rankingFunction);

        StoppingCondition<TestSuiteChromosome> stoppingCondition = getStoppingCondition();
        setUpStoppingConditions(ga, stoppingCondition);

        ga.resetStoppingConditions();
        ga.setPopulationLimit(getPopulationLimit());

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
            applyDynamicLimit(stoppingCondition);
        }

        if (Properties.LOCAL_SEARCH_RESTORE_COVERAGE) {
            ga.addListener(BranchCoverageMap.getInstance());
        }

        if (Properties.SHUTDOWN_HOOK) {
            addShutdownStoppingConditions(ga);
        }

        ga.addListener(new ResourceController<>());
        return ga;
    }


}
