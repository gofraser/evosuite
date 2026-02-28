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
import org.evosuite.ga.comparators.OnlyCrowdingComparator;
import org.evosuite.ga.diversity.DefaultSpeciesAssigner;
import org.evosuite.ga.diversity.DefaultSpeciesPolicy;
import org.evosuite.ga.diversity.PopulationDiversityComputation;
import org.evosuite.ga.diversity.SpeciesAssigner;
import org.evosuite.ga.diversity.SpeciesPolicy;
import org.evosuite.ga.operators.ranking.CrowdingDistance;
import org.evosuite.ga.operators.ranking.RankBasedPreferenceSorting;
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

    private static final long serialVersionUID = 146182080947267628L;

    private static final Logger logger = LoggerFactory.getLogger(MOSA.class);

    /**
     * immigrant groups from neighbouring client.
     */
    private final ConcurrentLinkedQueue<List<TestChromosome>> immigrants =
            new ConcurrentLinkedQueue<>();

    private final SelectionFunction<TestChromosome> emigrantsSelection;

    /**
     * Crowding distance measure to use.
     */
    protected CrowdingDistance<TestChromosome> distance = new CrowdingDistance<>();

    /**
     * Speciation assigner (null when speciation is disabled).
     */
    private final SpeciesAssigner speciesAssigner;

    /**
     * Speciation policy (null when speciation is disabled).
     */
    private final SpeciesPolicy speciesPolicy;

    /**
     * Tracks species count timeline for runtime variable emission.
     */
    private final List<Integer> speciesCountTimeline = new ArrayList<>();

    /**
     * Tracks largest species share timeline for runtime variable emission.
     */
    private final List<Double> speciesLargestShareTimeline = new ArrayList<>();

    /** Per-generation LLM-parsed statement ratio for timeline emission. */
    private final List<Double> parsedRatioTimeline = new ArrayList<>();

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

        if (Properties.SPECIATION_ENABLED) {
            this.speciesAssigner = new DefaultSpeciesAssigner();
            this.speciesPolicy = new DefaultSpeciesPolicy();
        } else {
            this.speciesAssigner = null;
            this.speciesPolicy = null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void evolve() {
        List<TestChromosome> offspringPopulation = this.breedNextGeneration();

        // Create the union of parents and offSpring
        List<TestChromosome> union = new ArrayList<>();
        union.addAll(this.population);
        union.addAll(offspringPopulation);

        // Integrate all external candidates (immigrants, LLM async, LLM stagnation)
        collectExternalCandidates(union);

        Set<TestFitnessFunction> uncoveredGoals = this.getUncoveredGoals();

        // Ranking the union
        logger.debug("Union Size =" + union.size());

        // Set species context on ranking function for species-aware front-0 tiebreaking.
        // Uses the previous generation's species map (null on first generation → no effect).
        boolean speciationActive = speciesAssigner != null && speciesPolicy != null;
        if (speciationActive && this.currentSpeciesMap != null
                && this.rankingFunction instanceof RankBasedPreferenceSorting) {
            Map<TestChromosome, Integer> indToSpecies = new IdentityHashMap<>();
            for (Map.Entry<Integer, List<TestChromosome>> entry : this.currentSpeciesMap.entrySet()) {
                for (TestChromosome tc : entry.getValue()) {
                    indToSpecies.put(tc, entry.getKey());
                }
            }
            ((RankBasedPreferenceSorting<TestChromosome>) this.rankingFunction)
                    .setSpeciesContext(indToSpecies);
        }

        // Ranking the union using the best rank algorithm (modified version of the non dominated sorting algorithm)
        this.rankingFunction.computeRankingAssignment(union, uncoveredGoals);

        // Clear species context after ranking to avoid stale references
        if (this.rankingFunction instanceof RankBasedPreferenceSorting) {
            ((RankBasedPreferenceSorting<TestChromosome>) this.rankingFunction)
                    .setSpeciesContext(null);
        }

        // Base population-size target for this generation.
        final int baseTarget = this.population.size();
        // When speciation is active, collect a larger candidate pool from fronts so
        // that species caps can recover underrepresented species that would otherwise
        // be eliminated by pure ranking/crowding truncation.
        int candidateLimit = speciationActive
                ? Math.min(union.size(), baseTarget * 2)
                : baseTarget;

        int remain = candidateLimit;
        int index = 0;
        List<TestChromosome> rankedCandidates = new ArrayList<>(candidateLimit);

        // Obtain the next front
        List<TestChromosome> front = this.rankingFunction.getSubfront(index);

        while ((remain > 0) && (remain >= front.size()) && !front.isEmpty()) {
            // Assign crowding distance to individuals
            this.distance.fastEpsilonDominanceAssignment(front, uncoveredGoals);
            // Add the individuals of this front
            rankedCandidates.addAll(front);

            // Decrement remain
            remain = remain - front.size();

            // Obtain the next front
            index++;
            if (remain > 0) {
                front = this.rankingFunction.getSubfront(index);
            }
        }

        // Remain is less than front(index).size, insert only the best one
        if (remain > 0 && !front.isEmpty()) { // front contains individuals to insert
            this.distance.fastEpsilonDominanceAssignment(front, uncoveredGoals);
            front.sort(new OnlyCrowdingComparator<>());
            for (int k = 0; k < remain; k++) {
                rankedCandidates.add(front.get(k));
            }
        }

        this.population.clear();

        // Apply speciation-based survival caps if enabled (additive to ranking/crowding).
        // Front-0 (preference front) is exempt: each member is the best individual for
        // at least one uncovered goal and must not be evicted by species caps.
        // Species caps apply only to the remaining population slots.
        //
        // Population-size policy (aligned with DynaMOSA):
        //   When front-0 exceeds baseTarget, the generation's effective target grows
        //   to front-0's size so that all preference-front members are preserved.
        //   This naturally resolves as goals become covered and front-0 shrinks.
        if (speciationActive && !rankedCandidates.isEmpty()) {
            try {
                // Separate front-0 (protected) from lower fronts
                List<TestChromosome> front0 = this.rankingFunction.getSubfront(0);
                Set<TestChromosome> front0Set = Collections.newSetFromMap(new IdentityHashMap<>());
                front0Set.addAll(front0);

                List<TestChromosome> nonFront0 = new ArrayList<>();
                for (TestChromosome tc : rankedCandidates) {
                    if (!front0Set.contains(tc)) {
                        nonFront0.add(tc);
                    }
                }

                // Species map over all candidates for accurate species membership
                Map<Integer, List<TestChromosome>> speciesMap =
                        speciesAssigner.groupBySpecies(rankedCandidates);

                // Effective target: at least baseTarget, grows to fit front-0 if needed.
                final int effectiveTarget = Math.max(baseTarget, front0.size());

                // Front-0 always survives; caps apply to remaining slots
                int remainingSlots = effectiveTarget - front0.size();
                this.population.addAll(front0);

                if (front0.size() > baseTarget) {
                    logger.debug("Front-0 size ({}) exceeds base population target ({}); "
                            + "effective target for this generation is {}",
                            front0.size(), baseTarget, effectiveTarget);
                }

                if (remainingSlots > 0 && !nonFront0.isEmpty()) {
                    List<TestChromosome> capped = speciesPolicy.applySurvivalCaps(
                            nonFront0, speciesMap, remainingSlots,
                            Properties.SPECIES_SURVIVAL_CAP);
                    this.population.addAll(capped);
                }

                // Emit timeline variables
                emitSpeciesTimeline(speciesMap);

                // Optional parent-pool balancing for next generation's selection.
                if (Properties.SPECIES_BALANCE_PARENT_SELECTION) {
                    Map<Integer, List<TestChromosome>> survivingSpecies =
                            speciesAssigner.groupBySpecies(this.population);
                    List<TestChromosome> balanced =
                            speciesPolicy.balanceParentPool(this.population, survivingSpecies);
                    this.population.clear();
                    this.population.addAll(balanced);
                }

                // Store species map of the surviving population for mating restriction
                this.currentSpeciesMap = speciesAssigner.groupBySpecies(this.population);
            } catch (Exception e) {
                logger.debug("Speciation failed; using ranked fallback", e);
                this.population.addAll(rankedCandidates.subList(0,
                        Math.min(baseTarget, rankedCandidates.size())));
            }
        } else {
            this.population.addAll(rankedCandidates);
        }

        // for parallel runs: collect best k individuals for migration
        if (Properties.NUM_PARALLEL_CLIENTS > 1 && Properties.MIGRANTS_ITERATION_FREQUENCY > 0) {
            if ((currentIteration + 1) % Properties.MIGRANTS_ITERATION_FREQUENCY == 0 && !this.population.isEmpty()) {
                HashSet<TestChromosome> emigrants = new HashSet<>(emigrantsSelection.select(this.population,
                        Properties.MIGRANTS_COMMUNICATION_RATE));
                ClientServices.<TestChromosome>getInstance().getClientNode().emigrate(emigrants);
            }
        }

        // Record LLM parsed-statement ratio for this generation
        parsedRatioTimeline.add(computePopulationParsedRatio(this.population));

        // Emit diversity value for this generation (per-sample for DirectSequenceOutputVariableFactory)
        if (Properties.TRACK_DIVERSITY) {
            double diversity = PopulationDiversityComputation.computeDiversity(this.population);
            ClientServices.<TestChromosome>getInstance().getClientNode()
                    .trackOutputVariable(RuntimeVariable.DiversityTimeline, diversity);
        }

        // Emit fronts count, remaining goals, and covered goals for this generation
        {
            ClientNodeLocal<TestChromosome> cn =
                    ClientServices.<TestChromosome>getInstance().getClientNode();
            cn.trackOutputVariable(RuntimeVariable.Fronts_Count_Timeline,
                    this.rankingFunction.getNumberOfSubfronts());
            cn.trackOutputVariable(RuntimeVariable.Remaining_Goals_Timeline,
                    this.getNumberOfUncoveredGoals());
            cn.trackOutputVariable(RuntimeVariable.Covered_Goals_Timeline,
                    this.getNumberOfCoveredGoals());
        }

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
            registerExternalCandidateSources();

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

            // storing the time needed to reach the maximum coverage
            clientNode.trackOutputVariable(RuntimeVariable.Time2MaxCoverage,
                    this.budgetMonitor.getTime2MaxCoverage());

            // Emit LLM operator statistics
            emitOperatorStats(clientNode);

            // Emit species timeline variables
            emitSpeciesTimelineVariables(clientNode);

            // Emit LLM parsed-statement ratio and timeline
            emitParsedRatioVariables(clientNode);
        } finally {
            shutdownLlmAssistance();
        }
        this.notifySearchFinished();
    }

    /**
     * Registers all external candidate sources for this MOSA instance:
     * island immigrants, LLM async producer, and LLM stagnation detector.
     */
    private void registerExternalCandidateSources() {
        // Island-model immigrants
        if (Properties.NUM_PARALLEL_CLIENTS > 1) {
            externalCandidateSources.add(() -> {
                List<TestChromosome> batch = immigrants.poll();
                return batch != null ? batch : Collections.emptyList();
            });
        }
        // LLM async producer
        if (asyncProducer != null) {
            externalCandidateSources.add(() -> asyncProducer.drainAvailable());
        }
        // LLM stagnation detector
        if (stagnationDetector != null) {
            externalCandidateSources.add(() -> {
                if (!stagnationDetector.checkStagnation(getNumberOfCoveredGoals())) {
                    return Collections.emptyList();
                }
                List<TestChromosome> tests = stagnationDetector.requestHelp(
                        getUncoveredGoals(), new ArrayList<>(population));
                return tests != null ? tests : Collections.emptyList();
            });
        }
    }

    /**
     * Record species count and largest share for the current generation.
     */
    private void emitSpeciesTimeline(Map<Integer, List<TestChromosome>> speciesMap) {
        if (Properties.SPECIES_TIMELINE_ENABLED) {
            speciesCountTimeline.add(speciesMap.size());
        }
        if (Properties.SPECIES_LARGEST_SHARE_TIMELINE_ENABLED && !speciesMap.isEmpty()) {
            int maxSize = 0;
            int total = 0;
            for (List<TestChromosome> members : speciesMap.values()) {
                maxSize = Math.max(maxSize, members.size());
                total += members.size();
            }
            speciesLargestShareTimeline.add(total > 0 ? (double) maxSize / total : 0.0);
        }
    }

    /**
     * Emit LLM operator statistics to the client node.
     */
    private void emitOperatorStats(ClientNodeLocal<?> clientNode) {
        if (llmMutation != null) {
            clientNode.trackOutputVariable(RuntimeVariable.LLM_Semantic_Mutations,
                    llmMutation.getAppliedCount());
            clientNode.trackOutputVariable(RuntimeVariable.LLM_Semantic_Mutation_Fallbacks,
                    llmMutation.getFallbackCount());
        }
        if (llmCrossover != null) {
            clientNode.trackOutputVariable(RuntimeVariable.LLM_Semantic_Crossovers,
                    llmCrossover.getAppliedCount());
            clientNode.trackOutputVariable(RuntimeVariable.LLM_Semantic_Crossover_Fallbacks,
                    llmCrossover.getFallbackCount());
        }
    }

    /**
     * Emit species timeline runtime variables.
     */
    private void emitSpeciesTimelineVariables(ClientNodeLocal<?> clientNode) {
        if (!speciesCountTimeline.isEmpty()) {
            clientNode.trackOutputVariable(RuntimeVariable.Species_Count_Timeline,
                    speciesCountTimeline.toString());
        }
        if (!speciesLargestShareTimeline.isEmpty()) {
            clientNode.trackOutputVariable(RuntimeVariable.Species_Largest_Share_Timeline,
                    speciesLargestShareTimeline.toString());
        }
    }

    /**
     * Computes the ratio of LLM-parsed statements to total statements in a population.
     */
    private static double computePopulationParsedRatio(List<TestChromosome> population) {
        int total = 0;
        int parsed = 0;
        for (TestChromosome tc : population) {
            for (int i = 0; i < tc.getTestCase().size(); i++) {
                total++;
                if (tc.getTestCase().getStatement(i).isParsedFromLlm()) {
                    parsed++;
                }
            }
        }
        return total > 0 ? (double) parsed / total : 0.0;
    }

    /**
     * Emit LLM parsed-statement ratio and timeline runtime variables.
     */
    private void emitParsedRatioVariables(ClientNodeLocal<?> clientNode) {
        // Use population (not archive) to be consistent with per-generation timeline
        double finalRatio = computePopulationParsedRatio(this.population);
        clientNode.trackOutputVariable(RuntimeVariable.LLM_Parsed_Statement_Ratio, finalRatio);
        if (!parsedRatioTimeline.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parsedRatioTimeline.size(); i++) {
                if (i > 0) {
                    sb.append(";");
                }
                sb.append(String.format(Locale.ROOT, "%.4f", parsedRatioTimeline.get(i)));
            }
            clientNode.trackOutputVariable(RuntimeVariable.LLM_Parsed_Statement_Ratio_Timeline,
                    sb.toString());
        }
    }

}
