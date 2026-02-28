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
import org.evosuite.ga.comparators.OnlyCrowdingComparator;
import org.evosuite.ga.diversity.DefaultSpeciesAssigner;
import org.evosuite.ga.diversity.DefaultSpeciesPolicy;
import org.evosuite.ga.diversity.PopulationDiversityComputation;
import org.evosuite.ga.diversity.SpeciesAssigner;
import org.evosuite.ga.diversity.SpeciesPolicy;
import org.evosuite.ga.metaheuristics.mosa.structural.MultiCriteriaManager;
import org.evosuite.ga.operators.ranking.CrowdingDistance;
import org.evosuite.ga.operators.ranking.RankBasedPreferenceSorting;
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

    private static final long serialVersionUID = 146182080947267628L;

    private static final Logger logger = LoggerFactory.getLogger(DynaMOSA.class);

    /**
     * Manager to determine the test goals to consider at each generation.
     */
    protected MultiCriteriaManager goalsManager = null;

    protected CrowdingDistance<TestChromosome> distance = new CrowdingDistance<>();

    /** Speciation assigner (null when speciation is disabled). */
    private final SpeciesAssigner speciesAssigner;
    /** Speciation policy (null when speciation is disabled). */
    private final SpeciesPolicy speciesPolicy;
    private final List<Integer> speciesCountTimeline = new ArrayList<>();
    private final List<Double> speciesLargestShareTimeline = new ArrayList<>();

    /** Per-generation LLM-parsed statement ratio for timeline emission. */
    private final List<Double> parsedRatioTimeline = new ArrayList<>();

    /**
     * Constructor based on the abstract class. {@link AbstractMOSA}.
     *
     * @param factory a {@link org.evosuite.ga.ChromosomeFactory} object
     */
    public DynaMOSA(ChromosomeFactory<TestChromosome> factory) {
        super(factory);

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
        // Generate offspring, compute their fitness, update the archive and coverage goals.
        List<TestChromosome> offspringPopulation = this.breedNextGeneration();

        // Create the union of parents and offspring
        List<TestChromosome> union = new ArrayList<>(this.population.size() + offspringPopulation.size());
        union.addAll(this.population);
        union.addAll(offspringPopulation);

        // Integrate all external candidates (LLM async, LLM stagnation)
        collectExternalCandidates(union);

        // Ranking the union
        logger.debug("Union Size = {}", union.size());

        // Set species context on ranking function for species-aware front-0 tiebreaking.
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

        // Ranking the union using the best rank algorithm (modified version of the non dominated
        // sorting algorithm)
        this.rankingFunction.computeRankingAssignment(union, this.goalsManager.getCurrentGoals());

        // Clear species context after ranking
        if (this.rankingFunction instanceof RankBasedPreferenceSorting) {
            ((RankBasedPreferenceSorting<TestChromosome>) this.rankingFunction)
                    .setSpeciesContext(null);
        }

        // let's form the next population using "preference sorting and non-dominated sorting" on the
        // updated set of goals
        int populationSize = Math.max(Properties.POPULATION, this.rankingFunction.getSubfront(0).size());
        // When speciation is active, collect a larger candidate pool from fronts so
        // that species caps can recover underrepresented species that would otherwise
        // be eliminated by pure ranking/crowding truncation.
        int candidateLimit = speciationActive
                ? Math.min(union.size(), populationSize * 2)
                : populationSize;

        int remain = candidateLimit;
        int index = 0;
        List<TestChromosome> rankedCandidates = new ArrayList<>(candidateLimit);

        // Obtain the first front
        List<TestChromosome> front = this.rankingFunction.getSubfront(index);

        // Successively iterate through the fronts (starting with the first non-dominated front)
        // and insert their members into the ranked candidate list. This is done until
        // all fronts have been processed or we hit a front that is too big to fit into the
        // candidate limit as a whole.
        while ((remain > 0) && (remain >= front.size()) && !front.isEmpty()) {
            // Assign crowding distance to individuals
            this.distance.fastEpsilonDominanceAssignment(front, this.goalsManager.getCurrentGoals());

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

        // In case the candidate list has not been filled up completely yet,
        // we insert the best individuals from the current front (the one that was too big to fit
        // entirely). To promote diversity, we consider those with a higher crowding distance first.
        if (remain > 0 && !front.isEmpty()) { // front contains individuals to insert
            this.distance.fastEpsilonDominanceAssignment(front, this.goalsManager.getCurrentGoals());
            front.sort(new OnlyCrowdingComparator<>());
            for (int k = 0; k < remain; k++) {
                rankedCandidates.add(front.get(k));
            }
        }

        this.population.clear();

        // Apply speciation-based survival caps if enabled.
        // Front-0 (preference front) is exempt: each member is the best individual for
        // at least one uncovered goal and must not be evicted by species caps.
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

                // Front-0 always survives; caps apply to remaining slots
                int remainingSlots = Math.max(0, populationSize - front0.size());
                this.population.addAll(front0);

                if (remainingSlots > 0 && !nonFront0.isEmpty()) {
                    List<TestChromosome> capped = speciesPolicy.applySurvivalCaps(
                            nonFront0, speciesMap, remainingSlots,
                            Properties.SPECIES_SURVIVAL_CAP);
                    this.population.addAll(capped);
                }

                emitSpeciesTimeline(speciesMap);

                // Re-group on surviving population for balancing
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
                        Math.min(populationSize, rankedCandidates.size())));
            }
        } else {
            this.population.addAll(rankedCandidates);
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
                    goalsManager.getUncoveredGoals().size());
            cn.trackOutputVariable(RuntimeVariable.Covered_Goals_Timeline,
                    goalsManager.getCoveredGoals().size());
        }

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
            registerExternalCandidateSources();

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
                emitOperatorStats(clientNode);
                emitSpeciesTimelineVariables(clientNode);
                emitParsedRatioVariables(clientNode);
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
        List<TestFitnessFunction> testFitnessFunctions = new ArrayList<>(goalsManager.getCoveredGoals());
        testFitnessFunctions.addAll(goalsManager.getUncoveredGoals());
        return testFitnessFunctions;
    }

    /**
     * Registers all external candidate sources for this DynaMOSA instance:
     * LLM async producer and LLM stagnation detector.
     */
    private void registerExternalCandidateSources() {
        // LLM async producer
        if (asyncProducer != null) {
            externalCandidateSources.add(() -> asyncProducer.drainAvailable());
        }
        // LLM stagnation detector (uses goalsManager for coverage tracking)
        if (stagnationDetector != null) {
            externalCandidateSources.add(() -> {
                if (goalsManager == null
                        || !stagnationDetector.checkStagnation(goalsManager.getCoveredGoals().size())) {
                    return Collections.emptyList();
                }
                List<TestChromosome> tests = stagnationDetector.requestHelp(
                        goalsManager.getUncoveredGoals(), new ArrayList<>(population));
                return tests != null ? tests : Collections.emptyList();
            });
        }
    }

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
