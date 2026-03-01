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

import org.evosuite.ClientProcess;
import org.evosuite.Properties;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.operators.selection.BestKSelection;
import org.evosuite.ga.operators.selection.RandomKSelection;
import org.evosuite.ga.operators.selection.RankSelection;
import org.evosuite.ga.operators.selection.SelectionFunction;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientNodeLocal;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.utils.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Implementation of the Many-Objective Sorting Algorithm (MOSA) described in the
 * paper "Reformulating branch coverage as a many-objective optimization problem".
 *
 * @author Annibale Panichella, Fitsum M. Kifetew
 */
public class MOSA extends AbstractMOSA {

    private static final long serialVersionUID = -7813895799847903992L;

    private static final Logger logger = LoggerFactory.getLogger(MOSA.class);

    /**
     * immigrant groups from neighbouring client.
     */
    private final ConcurrentLinkedQueue<List<TestChromosome>> immigrants =
            new ConcurrentLinkedQueue<>();

    private final SelectionFunction<TestChromosome> emigrantsSelection;

    /**
     * Constructor based on the abstract class. {@link AbstractMOSA}
     *
     * @param factory a {@link org.evosuite.ga.ChromosomeFactory} object
     */
    public MOSA(ChromosomeFactory<TestChromosome> factory) {
        super(factory);

        switch (Properties.EMIGRANT_SELECTION_FUNCTION) {
            case RANK:
                this.emigrantsSelection = new RankSelection<>();
                break;
            case RANDOMK:
                this.emigrantsSelection = new RandomKSelection<>();
                break;
            default:
                this.emigrantsSelection = new BestKSelection<>();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void evolve() {
        List<TestChromosome> offspringPopulation = this.breedNextGeneration();

        // Create the union of parents and offspring
        List<TestChromosome> union = new ArrayList<>();
        union.addAll(this.population);
        union.addAll(offspringPopulation);

        // Integrate all external candidates (immigrants, LLM async, LLM stagnation)
        collectExternalCandidates(union);

        Set<TestFitnessFunction> uncoveredGoals = this.getUncoveredGoals();

        logger.debug("Union Size = {}", union.size());

        // Ranking with species-aware tiebreaking
        setSpeciesContextOnRanking();
        this.rankingFunction.computeRankingAssignment(union, uncoveredGoals);
        clearSpeciesContextOnRanking();

        // Population-size target: dynamic based on current population
        final int baseTarget = this.population.size();
        int candidateLimit = speciationEnabled
                ? Math.min(union.size(), baseTarget * 2)
                : baseTarget;

        List<TestChromosome> rankedCandidates =
                selectByRankingAndCrowding(union, uncoveredGoals, candidateLimit);

        applySpeciationSurvival(rankedCandidates, baseTarget);

        // for parallel runs: collect best k individuals for migration
        if (Properties.NUM_PARALLEL_CLIENTS > 1 && Properties.MIGRANTS_ITERATION_FREQUENCY > 0) {
            if ((currentIteration + 1) % Properties.MIGRANTS_ITERATION_FREQUENCY == 0 && !this.population.isEmpty()) {
                HashSet<TestChromosome> emigrants = new HashSet<>(emigrantsSelection.select(this.population,
                        Properties.MIGRANTS_COMMUNICATION_RATE));
                ClientServices.<TestChromosome>getInstance().getClientNode().emigrate(emigrants);
            }
        }

        emitGenerationMetrics(this.getNumberOfUncoveredGoals(), this.getNumberOfCoveredGoals());

        this.currentIteration++;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void generateSolution() {
        logger.info("executing generateSolution function");

        // keep track of covered goals
        this.fitnessFunctions.forEach(this::addUncoveredGoal);

        // initialize population
        if (this.population.isEmpty()) {
            this.initializePopulation();
        }
        try {
            initializeLlmAssistance(this::getUncoveredGoals, false);
            registerExternalCandidateSources(
                    this::getNumberOfCoveredGoals, this::getUncoveredGoals);

            // Calculate dominance ranks and crowding distance
            this.rankingFunction.computeRankingAssignment(this.population, this.getUncoveredGoals());
            for (int i = 0; i < this.rankingFunction.getNumberOfSubfronts(); i++) {
                this.distance.fastEpsilonDominanceAssignment(
                        this.rankingFunction.getSubfront(i), this.getUncoveredGoals());
            }

            final ClientNodeLocal<TestChromosome> clientNode =
                    ClientServices.<TestChromosome>getInstance().getClientNode();

            Listener<Set<TestChromosome>> listener = null;
            if (Properties.NUM_PARALLEL_CLIENTS > 1) {
                listener = event -> immigrants.add(new LinkedList<>(event));
                clientNode.addListener(listener);
            }

            // TODO add here dynamic stopping condition
            while (!this.isFinished() && this.getNumberOfUncoveredGoals() > 0) {
                this.evolve();
                this.notifyIteration();

                // Apply local search at configured rate (gating is inside applyLocalSearch)
                this.applyLocalSearch(this.generateSuite());
            }

            if (Properties.NUM_PARALLEL_CLIENTS > 1) {
                clientNode.deleteListener(listener);

                if (ClientProcess.DEFAULT_CLIENT_NAME.equals(ClientProcess.getIdentifier())) {
                    //collect all end result test cases
                    Set<Set<TestChromosome>> collectedSolutions = clientNode.getBestSolutions();

                    logger.debug(ClientProcess.DEFAULT_CLIENT_NAME + ": Received " + collectedSolutions.size()
                            + " solution sets");
                    for (Set<TestChromosome> solution : collectedSolutions) {
                        for (TestChromosome t : solution) {
                            this.calculateFitness(t);
                        }
                    }
                } else {
                    //send end result test cases to Client-0
                    Set<TestChromosome> solutionsSet = new HashSet<>(getSolutions());
                    logger.debug(ClientProcess.getPrettyPrintIdentifier() + "Sending " + solutionsSet.size()
                            + " solutions to " + ClientProcess.DEFAULT_CLIENT_NAME);
                    clientNode.sendBestSolution(solutionsSet);
                }
            }

        } finally {
            // Emit stats in finally block so they are not lost on exception
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
                logger.debug("Failed to emit final stats", e);
            }
            shutdownLlmAssistance();
        }
        this.notifySearchFinished();
    }

    /**
     * Registers all external candidate sources for this MOSA instance:
     * island immigrants, LLM async producer, and LLM stagnation detector.
     */
    @Override
    protected void registerAdditionalCandidateSources() {
        // Island-model immigrants (MOSA-specific)
        if (Properties.NUM_PARALLEL_CLIENTS > 1) {
            externalCandidateSources.add(() -> {
                List<TestChromosome> batch = immigrants.poll();
                return batch != null ? batch : Collections.emptyList();
            });
        }
    }

}
