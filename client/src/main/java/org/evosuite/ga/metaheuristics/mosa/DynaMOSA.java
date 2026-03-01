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
package org.evosuite.ga.metaheuristics.mosa;

import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.metaheuristics.mosa.structural.MultiCriteriaManager;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientNodeLocal;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Implementation of the DynaMOSA (Many Objective Sorting Algorithm) described in the paper
 * "Automated Test Case Generation as a Many-Objective Optimisation Problem with Dynamic Selection
 * of the Targets".
 *
 * @author Annibale Panichella, Fitsum M. Kifetew, Paolo Tonella
 */
public class DynaMOSA extends AbstractMOSA {

    private static final long serialVersionUID = -4507648319927531484L;

    private static final Logger logger = LoggerFactory.getLogger(DynaMOSA.class);

    /**
     * Manager to determine the test goals to consider at each generation.
     */
    protected MultiCriteriaManager goalsManager = null;

    /**
     * Constructor based on the abstract class. {@link AbstractMOSA}.
     *
     * @param factory a {@link org.evosuite.ga.ChromosomeFactory} object
     */
    public DynaMOSA(ChromosomeFactory<TestChromosome> factory) {
        super(factory);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void evolve() {
        // Generate offspring, compute their fitness, update the archive and coverage goals.
        List<TestChromosome> offspringPopulation = this.breedNextGeneration();

        // Create the union of parents and offspring
        List<TestChromosome> union = new ArrayList<>(this.population.size() + offspringPopulation.size());
        union.addAll(this.population);
        union.addAll(offspringPopulation);

        // Integrate all external candidates (LLM async, LLM stagnation)
        collectExternalCandidates(union);

        logger.debug("Union Size = {}", union.size());

        // Ranking with species-aware tiebreaking
        setSpeciesContextOnRanking();
        this.rankingFunction.computeRankingAssignment(union, this.goalsManager.getCurrentGoals());
        clearSpeciesContextOnRanking();

        // Population-size target: fixed base with front-0 floor
        int populationSize = Math.max(Properties.POPULATION, this.rankingFunction.getSubfront(0).size());
        int candidateLimit = speciationEnabled
                ? Math.min(union.size(), populationSize * 2)
                : populationSize;

        List<TestChromosome> rankedCandidates =
                selectByRankingAndCrowding(union, this.goalsManager.getCurrentGoals(), candidateLimit);

        applySpeciationSurvival(rankedCandidates, populationSize);

        emitGenerationMetrics(goalsManager.getUncoveredGoals().size(),
                goalsManager.getCoveredGoals().size());

        this.currentIteration++;
        logger.debug("Covered goals = {}", goalsManager.getCoveredGoals().size());
        logger.debug("Current goals = {}", goalsManager.getCurrentGoals().size());
        logger.debug("Uncovered goals = {}", goalsManager.getUncoveredGoals().size());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void generateSolution() {
        logger.debug("executing generateSolution function");

        // Set up the targets to cover, which are initially free of any control dependencies.
        // We are trying to optimize for multiple targets at the same time.
        this.goalsManager = new MultiCriteriaManager(this.fitnessFunctions);
        try {
            initializeLlmAssistance(() -> goalsManager == null
                    ? Collections.emptySet()
                    : goalsManager.getUncoveredGoals(), false);
            registerExternalCandidateSources(
                    () -> goalsManager == null ? 0 : goalsManager.getCoveredGoals().size(),
                    () -> goalsManager == null ? Collections.emptySet() : goalsManager.getUncoveredGoals());

            LoggingUtils.getEvoLogger().info("* Initial Number of Goals in DynaMOSA = "
                    + this.goalsManager.getCurrentGoals().size() + " / " + this.getUncoveredGoals().size());

            logger.debug("Initial Number of Goals = " + this.goalsManager.getCurrentGoals().size());

            if (this.population.isEmpty()) {
                // Initialize the population by creating solutions at random.
                this.initializePopulation();
            }

            // Compute the fitness for each population member, update the coverage information and the
            // set of goals to cover. Finally, update the archive.
            // this.calculateFitness(); // Not required, already done by this.initializePopulation();

            // Calculate dominance ranks and crowding distance. This is required to decide which
            // individuals should be used for mutation and crossover in the first iteration of the main
            // search loop.
            this.rankingFunction.computeRankingAssignment(this.population, this.goalsManager.getCurrentGoals());
            for (int i = 0; i < this.rankingFunction.getNumberOfSubfronts(); i++) {
                this.distance.fastEpsilonDominanceAssignment(this.rankingFunction.getSubfront(i),
                        this.goalsManager.getCurrentGoals());
            }

            // Evolve the population generation by generation until all gaols have been covered or the
            // search budget has been consumed.
            while (!isFinished() && this.goalsManager.getUncoveredGoals().size() > 0) {
                this.evolve();
                // Stagnation injection now happens inside evolve() in the union/ranking path
                this.notifyIteration();

                // Apply local search at configured rate (gating is inside applyLocalSearch)
                this.applyLocalSearch(this.generateSuite());
            }
        } finally {
            // Emit operator and speciation stats
            try {
                ClientNodeLocal<?> clientNode =
                        ClientServices.getInstance().getClientNode();
                clientNode.trackOutputVariable(RuntimeVariable.Time2MaxCoverage,
                        this.budgetMonitor.getTime2MaxCoverage());
                clientNode.trackOutputVariable(RuntimeVariable.LLM_Parsed_Statement_Ratio,
                        computePopulationParsedRatio(this.population));
                emitOperatorStats(clientNode);
                emitDisruptionStats(clientNode);
            } catch (Exception e) {
                logger.debug("Failed to emit Phase 5 stats", e);
            }
            shutdownLlmAssistance();
        }
        this.notifySearchFinished();
    }

    /**
     * Calculates the fitness for the given individual. Also updates the list of targets to cover,
     * as well as the population of best solutions in the archive.
     *
     * @param c the chromosome whose fitness to compute
     */
    @Override
    protected void calculateFitness(TestChromosome c) {
        if (!isFinished()) {
            // this also updates the archive and the targets
            this.goalsManager.calculateFitness(c, this);
            this.notifyEvaluation(c);
        }
    }

    @Override
    public List<? extends FitnessFunction<TestChromosome>> getFitnessFunctions() {
        if (goalsManager == null) {
            return super.getFitnessFunctions();
        }
        List<TestFitnessFunction> testFitnessFunctions = new ArrayList<>(goalsManager.getCoveredGoals());
        testFitnessFunctions.addAll(goalsManager.getUncoveredGoals());
        return testFitnessFunctions;
    }

    /**
     * Registers all external candidate sources for this DynaMOSA instance:
     * LLM async producer and LLM stagnation detector.
     */
}
