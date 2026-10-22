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
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
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

    public abstract GeneticAlgorithm<T> getSearchAlgorithm();
}
