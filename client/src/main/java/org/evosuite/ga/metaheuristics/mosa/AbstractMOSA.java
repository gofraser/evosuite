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
import org.evosuite.Properties.SelectionFunction;
import org.evosuite.coverage.FitnessFunctions;
import org.evosuite.coverage.exception.ExceptionCoverageSuiteFitness;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.archive.Archive;
import org.evosuite.ga.comparators.DominanceComparator;
import org.evosuite.ga.comparators.OnlyCrowdingComparator;
import org.evosuite.ga.diversity.DefaultSpeciesAssigner;
import org.evosuite.ga.diversity.DefaultSpeciesPolicy;
import org.evosuite.ga.diversity.PopulationDiversityComputation;
import org.evosuite.ga.diversity.SpeciesAssigner;
import org.evosuite.ga.diversity.SpeciesPolicy;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.ga.operators.ranking.CrowdingDistance;
import org.evosuite.ga.operators.ranking.RankBasedPreferenceSorting;
import org.evosuite.llm.search.BreedingDisruptionObserver;
import org.evosuite.llm.search.DisruptionEvent;
import org.evosuite.llm.search.DisruptionEvent.OperatorOutcome;
import org.evosuite.llm.search.DisruptionEvent.OperatorSource;
import org.evosuite.llm.search.DisruptionHelper;
import org.evosuite.llm.search.DisruptionRecorder;
import org.evosuite.llm.search.LanguageModelCrossover;
import org.evosuite.llm.search.LanguageModelMutation;
import org.evosuite.llm.search.OperatorAttemptResult;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientNodeLocal;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.secondaryobjectives.TestCaseSecondaryObjective;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.evosuite.utils.ArrayUtil;
import org.evosuite.utils.BudgetConsumptionMonitor;
import org.evosuite.utils.LoggingUtils;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Abstract class for MOSA or variants of MOSA.
 *
 * @author Annibale Panichella, Fitsum M. Kifetew
 */
public abstract class AbstractMOSA extends GeneticAlgorithm<TestChromosome> {

    private static final long serialVersionUID = 146182080947267628L;

    private static final Logger logger = LoggerFactory.getLogger(AbstractMOSA.class);

    /**
     * A source of externally-produced candidate chromosomes that should be
     * injected into the MOSA union during {@code evolve()}.
     *
     * <p>Each source is drained once per generation. Returned lists may be empty
     * but must not be null.
     */
    @FunctionalInterface
    protected interface ExternalCandidateSource {
        List<TestChromosome> drain();
    }

    /**
     * Registered external candidate sources. Subclasses add sources during
     * initialization (e.g., island immigrants, LLM async producer,
     * LLM stagnation detector). All sources are drained uniformly
     * in {@link #collectExternalCandidates}.
     */
    protected final transient List<ExternalCandidateSource> externalCandidateSources =
            new ArrayList<>();

    // Explicitly declared with a more special type than the one used in GeneticAlgorithm.
    // This is required for the Archive, which currently only supports TestFitnessFunctions.
    protected final List<TestFitnessFunction> fitnessFunctions = new ArrayList<>();

    private MOSATestSuiteAdapter adapter = null;

    /**
     * Keep track of overall suite fitness functions and correspondent test fitness functions.
     */
    public final Map<TestSuiteFitnessFunction, Class<?>> suiteFitnessFunctions;

    /**
     * Object used to keep track of the execution time needed to reach the maximum coverage.
     */
    protected final BudgetConsumptionMonitor budgetMonitor;

    /**
     * LLM-based mutation operator. Initialized lazily when LLM operators are enabled.
     */
    protected transient LanguageModelMutation llmMutation;

    /**
     * LLM-based crossover operator. Initialized lazily when LLM operators are enabled.
     */
    protected transient LanguageModelCrossover llmCrossover;

    /**
     * Species map from the most recent generation's speciation assignment.
     * Used during breeding for optional intra-species mating restriction.
     * Null until the first speciation-enabled evolve() completes.
     */
    protected Map<Integer, List<TestChromosome>> currentSpeciesMap;

    /**
     * Tracks whether the last call to {@link #shouldApplyLocalSearch()} returned
     * true. Used by {@link #applyLocalSearch(TestSuiteChromosome)} to gate
     * re-evaluation: when the adapter delegates LS scheduling to this MOSA
     * instance, only re-evaluate suite tests if LS was actually applied.
     */
    private boolean lastLocalSearchScheduled;

    /**
     * Pending LS-produced tests to be injected into the next generation's
     * union via {@link #collectExternalCandidates}. Populated by
     * {@link #applyLocalSearch(TestSuiteChromosome)} with only the delta
     * (post-LS minus pre-LS suite tests) and drained during the subsequent
     * {@link #evolve()} call. This ensures only truly LS-introduced tests
     * compete in ranking and may become parents for future breeding,
     * without staging unchanged archive snapshot tests.
     */
    private final List<TestChromosome> pendingLsTests = new ArrayList<>();

    /**
     * Crowding distance measure to use.
     */
    protected CrowdingDistance<TestChromosome> distance = new CrowdingDistance<>();

    /** Whether speciation-based survival is active for this search. */
    protected final boolean speciationEnabled;

    /**
     * Speciation assigner. Always non-null; uses a no-op implementation when
     * speciation is disabled.
     */
    protected final SpeciesAssigner speciesAssigner;

    /**
     * Speciation policy. Always non-null; uses a no-op implementation when
     * speciation is disabled.
     */
    protected final SpeciesPolicy speciesPolicy;

    /**
     * Constructor.
     *
     * @param factory a {@link org.evosuite.ga.ChromosomeFactory} object
     */
    public AbstractMOSA(ChromosomeFactory<TestChromosome> factory) {
        super(factory);

        this.suiteFitnessFunctions = new LinkedHashMap<>();
        for (Properties.Criterion criterion : Properties.CRITERION) {
            TestSuiteFitnessFunction suiteFit = FitnessFunctions.getFitnessFunction(criterion);
            Class<?> testFit = FitnessFunctions.getTestFitnessFunctionClass(criterion);
            this.suiteFitnessFunctions.put(suiteFit, testFit);
        }

        this.budgetMonitor = new BudgetConsumptionMonitor();

        // set the secondary objectives of test cases (useful when MOSA compares two test
        // cases to, for example, update the archive)
        TestCaseSecondaryObjective.setSecondaryObjectives();

        if (Properties.SELECTION_FUNCTION != SelectionFunction.RANK_CROWD_DISTANCE_TOURNAMENT) {
            LoggingUtils.getEvoLogger()
                    .warn("Originally, MOSA was implemented with a '"
                            + SelectionFunction.RANK_CROWD_DISTANCE_TOURNAMENT.name()
                            + "' selection function. You may want to consider using it.");
        }

        if (Properties.LLM_OPERATOR_ENABLED) {
            this.llmMutation = new LanguageModelMutation();
            this.llmCrossover = new LanguageModelCrossover();
        }

        if (Properties.SPECIATION_ENABLED) {
            this.speciationEnabled = true;
            this.speciesAssigner = new DefaultSpeciesAssigner();
            this.speciesPolicy = new DefaultSpeciesPolicy();
        } else {
            this.speciationEnabled = false;
            // No-op implementations: groupBySpecies puts all in one species,
            // policy methods return input unchanged.
            this.speciesAssigner = population -> {
                Map<Integer, List<TestChromosome>> single = new HashMap<>();
                single.put(0, new ArrayList<>(population));
                return single;
            };
            this.speciesPolicy = new SpeciesPolicy() {
                @Override
                public List<TestChromosome> applySurvivalCaps(
                        List<TestChromosome> rankedSurvivors,
                        Map<Integer, List<TestChromosome>> speciesMap,
                        int targetSize, double survivalCap) {
                    return rankedSurvivors.subList(0, Math.min(targetSize, rankedSurvivors.size()));
                }
                @Override
                public List<TestChromosome> balanceParentPool(
                        List<TestChromosome> pop,
                        Map<Integer, List<TestChromosome>> speciesMap) {
                    return pop;
                }
            };
        }
    }

    /**
     * Sets the adapter.
     *
     * @param adapter the adapter
     */
    public void setAdapter(final MOSATestSuiteAdapter adapter) {
        Objects.requireNonNull(adapter);
        if (this.adapter == null) {
            this.adapter = adapter;
        } else {
            throw new IllegalStateException("adapter has already been set");
        }
    }

    @Override
    public void addFitnessFunction(final FitnessFunction<TestChromosome> function) {
        if (function instanceof TestFitnessFunction) {
            fitnessFunctions.add((TestFitnessFunction) function);
        } else {
            throw new IllegalArgumentException("Only TestFitnessFunctions are supported");
        }
    }

    @Override
    public FitnessFunction<TestChromosome> getFitnessFunction() {
        return fitnessFunctions.get(0);
    }

    @Override
    public List<? extends FitnessFunction<TestChromosome>> getFitnessFunctions() {
        return fitnessFunctions;
    }

    /**
     * This method is used to generate new individuals (offspring) from
     * the current population. The offspring population has the same size as the parent population.
     *
     * @return offspring population
     */
    protected List<TestChromosome> breedNextGeneration() {
        List<TestChromosome> offspringPopulation = new ArrayList<>(Properties.POPULATION);
        final BreedingDisruptionObserver observer = BreedingDisruptionObserver.create();

        // Build identity-based reverse lookup for intra-species mating restriction.
        Map<TestChromosome, Integer> individualToSpecies = null;
        if (Properties.SPECIES_RESTRICT_MATING && currentSpeciesMap != null) {
            individualToSpecies = new IdentityHashMap<>();
            for (Map.Entry<Integer, List<TestChromosome>> entry : currentSpeciesMap.entrySet()) {
                for (TestChromosome tc : entry.getValue()) {
                    individualToSpecies.put(tc, entry.getKey());
                }
            }
        }

        // we apply only Properties.POPULATION/2 iterations since in each generation
        // we generate two offsprings
        for (int i = 0; i < Properties.POPULATION / 2 && !this.isFinished(); i++) {
            TestChromosome parent1 = this.selectionFunction.select(this.population);
            TestChromosome parent2 = selectParent2(parent1, individualToSpecies);
            TestChromosome offspring1 = parent1.clone();
            TestChromosome offspring2 = parent2.clone();

            // Capture pre-crossover state for disruption analysis
            int preCrossStmts1 = observer.isEnabled() ? DisruptionHelper.statementCount(offspring1) : 0;

            // Try LLM crossover first, then fall back to standard crossover
            OperatorAttemptResult crossoverResult = OperatorAttemptResult.standardOnly(
                    OperatorAttemptResult.SkipReason.NOT_CONFIGURED);
            boolean crossoverApplied = false;
            if (llmCrossover != null) {
                try {
                    crossoverResult = llmCrossover.tryCrossoverWithResult(
                            offspring1, offspring2, getUncoveredGoals());
                } catch (Exception e) {
                    logger.debug("LLM crossover error; falling back to standard", e);
                    crossoverResult = OperatorAttemptResult.semanticFallback();
                }
            }
            if (!crossoverResult.isAppliedSemantic() && Randomness.nextDouble() <= Properties.CROSSOVER_RATE) {
                try {
                    this.crossoverFunction.crossOver(offspring1, offspring2);
                    crossoverApplied = true;
                } catch (ConstructionFailedException e) {
                    logger.debug("CrossOver failed.");
                    continue;
                }
            } else if (crossoverResult.isAppliedSemantic()) {
                crossoverApplied = true;
            }

            // Record crossover disruption event
            if (observer.isEnabled() && (crossoverApplied || crossoverResult.isAttemptedSemantic())) {
                TestChromosome probeSnapshot = null;
                boolean probeFailure = false;
                double isolatedFitness = Double.NaN;
                if (observer.isIsolatedProbeEnabled() && crossoverApplied) {
                    probeSnapshot = offspring1.clone();
                    try {
                        this.clearCachedResults(probeSnapshot);
                        this.calculateFitness(probeSnapshot);
                        isolatedFitness = DisruptionHelper.aggregateFitness(probeSnapshot);
                    } catch (Exception e) {
                        logger.debug("Disruption isolated crossover probe failed", e);
                        probeFailure = true;
                        probeSnapshot = null;
                    }
                }
                observer.recordCrossover(offspring1, parent1, parent2,
                        preCrossStmts1, crossoverResult, crossoverApplied,
                        this.currentIteration, isolatedFitness, probeFailure, probeSnapshot);
            }

            this.removeUnusedVariables(offspring1);
            this.removeUnusedVariables(offspring2);

            processOffspringMutation(offspring1, parent1, offspringPopulation, observer);
            processOffspringMutation(offspring2, parent2, offspringPopulation, observer);
        }
        // Add new randomly generate tests
        for (int i = 0; i < Properties.POPULATION * Properties.P_TEST_INSERTION; i++) {
            final TestChromosome tch;
            if (this.getCoveredGoals().isEmpty() || Randomness.nextBoolean()) {
                tch = this.chromosomeFactory.getChromosome();
                tch.setChanged(true);
            } else {
                tch = Randomness.choice(this.getSolutions()).clone();
                tch.mutate();
            }
            if (tch.isChanged()) {
                tch.updateAge(this.currentIteration);
                this.calculateFitness(tch);
                offspringPopulation.add(tch);
            }
        }
        logger.debug("Number of offsprings = {}", offspringPopulation.size());
        return offspringPopulation;
    }

    /**
     * Apply mutation to offspring with optional disruption recording.
     * Extracts the repeated mutation logic for offspring1 and offspring2.
     * Records mutation event even when offspring is unchanged (fix: lost attempts).
     */
    private void processOffspringMutation(TestChromosome offspring, TestChromosome parent,
                                           List<TestChromosome> offspringPopulation,
                                           BreedingDisruptionObserver observer) {
        int preMutStmts = observer.isEnabled() ? DisruptionHelper.statementCount(offspring) : 0;

        // Isolated probe: evaluate post-crossover state before mutation
        TestChromosome postCrossoverSnapshot = null;
        boolean crossoverProbeFailure = false;
        double isolatedFitnessPostCrossover = Double.NaN;
        if (observer.isIsolatedProbeEnabled()) {
            postCrossoverSnapshot = offspring.clone();
            try {
                this.clearCachedResults(postCrossoverSnapshot);
                this.calculateFitness(postCrossoverSnapshot);
                isolatedFitnessPostCrossover = DisruptionHelper.aggregateFitness(postCrossoverSnapshot);
            } catch (Exception e) {
                logger.debug("Disruption isolated post-crossover probe failed", e);
                crossoverProbeFailure = true;
                postCrossoverSnapshot = null;
            }
        }

        OperatorAttemptResult mutResult = OperatorAttemptResult.standardOnly(
                OperatorAttemptResult.SkipReason.NOT_CONFIGURED);
        if (llmMutation != null) {
            try {
                mutResult = llmMutation.tryMutateWithResult(offspring, getUncoveredGoals());
            } catch (Exception e) {
                logger.debug("LLM mutation error; falling back to standard", e);
                mutResult = OperatorAttemptResult.semanticFallback();
            }
        }
        if (!mutResult.isAppliedSemantic()) {
            this.mutate(offspring, parent);
        }
        if (offspring.isChanged()) {
            this.clearCachedResults(offspring);
            offspring.updateAge(this.currentIteration);
            this.calculateFitness(offspring);

            if (observer.isEnabled()) {
                observer.recordMutation(offspring, parent, preMutStmts, mutResult,
                        true, this.currentIteration, postCrossoverSnapshot,
                        isolatedFitnessPostCrossover, crossoverProbeFailure);
            }

            offspringPopulation.add(offspring);
        } else {
            if (observer.isEnabled()) {
                observer.recordMutation(offspring, parent, preMutStmts, mutResult,
                        false, this.currentIteration, postCrossoverSnapshot,
                        isolatedFitnessPostCrossover, crossoverProbeFailure);
            }
        }
    }

    /**
     * Select the second parent for crossover. When intra-species mating restriction
     * is enabled and species information is available, parent2 is selected from the
     * same species as parent1. Falls back to unrestricted selection if the species
     * has fewer than 2 members or if parent1's species is unknown.
     */
    private TestChromosome selectParent2(TestChromosome parent1,
                                          Map<TestChromosome, Integer> individualToSpecies) {
        if (individualToSpecies != null) {
            Integer speciesId = individualToSpecies.get(parent1);
            if (speciesId != null && currentSpeciesMap != null) {
                List<TestChromosome> speciesMembers = currentSpeciesMap.get(speciesId);
                if (speciesMembers != null && speciesMembers.size() >= 2) {
                    return this.selectionFunction.select(speciesMembers);
                }
            }
            logger.debug("Intra-species mating fallback: parent1 species too small or unknown");
        }
        return this.selectionFunction.select(this.population);
    }

    /**
     * Sets the previous generation's species map as context on the ranking function
     * so that species-aware front-0 tiebreaking can be applied during ranking.
     * Must be paired with {@link #clearSpeciesContextOnRanking()}.
     */
    protected void setSpeciesContextOnRanking() {
        if (speciationEnabled && this.currentSpeciesMap != null
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
    }

    /** Clears species context from the ranking function to avoid stale references. */
    protected void clearSpeciesContextOnRanking() {
        if (this.rankingFunction instanceof RankBasedPreferenceSorting) {
            ((RankBasedPreferenceSorting<TestChromosome>) this.rankingFunction)
                    .setSpeciesContext(null);
        }
    }

    /**
     * Fills a list of candidates from successive Pareto fronts, using crowding
     * distance to break ties when the last front is too large.
     *
     * @param union    the combined parent+offspring pool
     * @param goals    the fitness goals used for crowding distance
     * @param capacity maximum number of candidates to select
     * @return the ranked candidates (at most {@code capacity} individuals)
     */
    protected List<TestChromosome> selectByRankingAndCrowding(
            List<TestChromosome> union,
            Set<? extends FitnessFunction<TestChromosome>> goals,
            int capacity) {

        int remain = capacity;
        int index = 0;
        List<TestChromosome> rankedCandidates = new ArrayList<>(capacity);

        List<TestChromosome> front = this.rankingFunction.getSubfront(index);

        while ((remain > 0) && (remain >= front.size()) && !front.isEmpty()) {
            this.distance.fastEpsilonDominanceAssignment(front, goals);
            rankedCandidates.addAll(front);
            remain -= front.size();
            index++;
            if (remain > 0) {
                front = this.rankingFunction.getSubfront(index);
            }
        }

        if (remain > 0 && !front.isEmpty()) {
            this.distance.fastEpsilonDominanceAssignment(front, goals);
            front.sort(new OnlyCrowdingComparator<>());
            for (int k = 0; k < remain; k++) {
                rankedCandidates.add(front.get(k));
            }
        }

        return rankedCandidates;
    }

    /**
     * Applies speciation-based survival selection. Front-0 members are always
     * preserved; species caps are applied only to lower-front individuals.
     *
     * <p>On failure, falls back to filling the population from {@code rankedCandidates}
     * in rank order.
     *
     * @param rankedCandidates the ranked candidate pool
     * @param populationSize   desired population size
     */
    protected void applySpeciationSurvival(List<TestChromosome> rankedCandidates,
                                           int populationSize) {
        this.population.clear();
        List<TestChromosome> front0 = this.rankingFunction.getSubfront(0);
        int effectiveTarget = Math.max(populationSize, front0.size());

        if (speciationEnabled && !rankedCandidates.isEmpty()) {
            try {
                Set<TestChromosome> front0Set = Collections.newSetFromMap(new IdentityHashMap<>());
                front0Set.addAll(front0);

                List<TestChromosome> nonFront0 = new ArrayList<>();
                for (TestChromosome tc : rankedCandidates) {
                    if (!front0Set.contains(tc)) {
                        nonFront0.add(tc);
                    }
                }

                Map<Integer, List<TestChromosome>> speciesMap =
                        speciesAssigner.groupBySpecies(rankedCandidates);

                int remainingSlots = effectiveTarget - front0.size();
                this.population.addAll(front0);

                if (front0.size() > populationSize) {
                    logger.debug("Front-0 size ({}) exceeds population target ({}); "
                            + "effective target is {}", front0.size(), populationSize, effectiveTarget);
                }

                if (remainingSlots > 0 && !nonFront0.isEmpty()) {
                    List<TestChromosome> capped = speciesPolicy.applySurvivalCaps(
                            nonFront0, speciesMap, remainingSlots,
                            Properties.SPECIES_SURVIVAL_CAP);
                    this.population.addAll(capped);
                }

                emitSpeciesTimeline(speciesMap);

                if (Properties.SPECIES_BALANCE_PARENT_SELECTION) {
                    Map<Integer, List<TestChromosome>> survivingSpecies =
                            speciesAssigner.groupBySpecies(this.population);
                    List<TestChromosome> balanced =
                            speciesPolicy.balanceParentPool(this.population, survivingSpecies);
                    this.population.clear();
                    this.population.addAll(balanced);
                }

                ensureFront0Preserved(front0, effectiveTarget);
                this.currentSpeciesMap = speciesAssigner.groupBySpecies(this.population);
            } catch (Exception e) {
                logger.debug("Speciation failed; using ranked fallback", e);
                this.population.clear();
                this.population.addAll(front0);
                Set<TestChromosome> selected = Collections.newSetFromMap(new IdentityHashMap<>());
                selected.addAll(front0);
                for (TestChromosome tc : rankedCandidates) {
                    if (selected.size() >= effectiveTarget) {
                        break;
                    }
                    if (!selected.contains(tc)) {
                        this.population.add(tc);
                        selected.add(tc);
                    }
                }
                ensureFront0Preserved(front0, effectiveTarget);
            }
        } else {
            this.population.addAll(rankedCandidates);
        }
    }

    /**
     * Ensures that all preference-front members are present in the current
     * population, trimming only non-front-0 individuals when target size
     * constraints are exceeded.
     */
    private void ensureFront0Preserved(List<TestChromosome> front0, int targetSize) {
        if (front0.isEmpty()) {
            return;
        }

        Set<TestChromosome> front0Set = Collections.newSetFromMap(new IdentityHashMap<>());
        front0Set.addAll(front0);

        Set<TestChromosome> present = Collections.newSetFromMap(new IdentityHashMap<>());
        present.addAll(this.population);

        for (TestChromosome tc : front0) {
            if (!present.contains(tc)) {
                this.population.add(tc);
                present.add(tc);
            }
        }

        int overflow = this.population.size() - targetSize;
        if (overflow <= 0) {
            return;
        }

        for (int i = this.population.size() - 1; i >= 0 && overflow > 0; i--) {
            TestChromosome tc = this.population.get(i);
            if (!front0Set.contains(tc)) {
                this.population.remove(i);
                overflow--;
            }
        }
    }

    /**
     * Emits per-generation metrics: parsed ratio, diversity, fronts count,
     * and remaining/covered goal counts.
     *
     * @param uncoveredGoalCount number of uncovered goals
     * @param coveredGoalCount   number of covered goals
     */
    protected void emitGenerationMetrics(int uncoveredGoalCount, int coveredGoalCount) {
        ClientNodeLocal<TestChromosome> cn =
                ClientServices.<TestChromosome>getInstance().getClientNode();
                
        double parsedRatio = computePopulationParsedRatio(this.population);
        cn.trackOutputVariable(RuntimeVariable.LLM_Parsed_Statement_Ratio_Timeline, parsedRatio);

        if (Properties.TRACK_DIVERSITY) {
            double diversity = PopulationDiversityComputation.computeDiversity(this.population);
            cn.trackOutputVariable(RuntimeVariable.DiversityTimeline, diversity);
        }

        cn.trackOutputVariable(RuntimeVariable.Fronts_Count_Timeline,
                this.rankingFunction.getNumberOfSubfronts());
        cn.trackOutputVariable(RuntimeVariable.Remaining_Goals_Timeline,
                uncoveredGoalCount);
        cn.trackOutputVariable(RuntimeVariable.Covered_Goals_Timeline,
                coveredGoalCount);
    }

    /**
     * Drains all registered {@link #externalCandidateSources}, evaluates
     * fitness for each candidate, and appends them to the given union list.
     *
     * <p>This is the single integration point for all external candidates
     * (island immigrants, LLM async producer, LLM stagnation, etc.).
     * It also drains any pending LS-improved tests staged by
     * {@link #applyLocalSearch(TestSuiteChromosome)}.
     *
     * @param union the parent+offspring union to extend
     */
    protected void collectExternalCandidates(List<TestChromosome> union) {
        // Drain LS-improved tests staged by applyLocalSearch.
        // These have already been evaluated through calculateFitness,
        // so we add them directly to the union without re-evaluation.
        if (!pendingLsTests.isEmpty()) {
            union.addAll(pendingLsTests);
            logger.debug("Injected {} LS-improved tests into union", pendingLsTests.size());
            pendingLsTests.clear();
        }

        for (ExternalCandidateSource source : externalCandidateSources) {
            try {
                List<TestChromosome> candidates = source.drain();
                if (candidates != null) {
                    for (TestChromosome candidate : candidates) {
                        this.calculateFitness(candidate);
                        union.add(candidate);
                    }
                }
            } catch (Exception e) {
                logger.debug("External candidate source failed; skipping", e);
            }
        }
    }

    /**
     * Registers external candidate sources shared by all MOSA variants
     * (async producer, stagnation detector). Subclasses should override
     * {@link #registerAdditionalCandidateSources()} to add variant-specific
     * sources (e.g., island immigrants for MOSA).
     *
     * @param coveredGoalCountSupplier  supplies the current covered goal count
     * @param uncoveredGoalsSupplier    supplies the current uncovered goals
     */
    protected void registerExternalCandidateSources(
            java.util.function.IntSupplier coveredGoalCountSupplier,
            java.util.function.Supplier<Set<TestFitnessFunction>> uncoveredGoalsSupplier) {
        if (asyncProducer != null) {
            externalCandidateSources.add(() -> asyncProducer.drainAvailable());
        }
        if (stagnationDetector != null) {
            externalCandidateSources.add(() -> {
                if (!stagnationDetector.checkStagnation(coveredGoalCountSupplier.getAsInt())) {
                    return Collections.emptyList();
                }
                List<TestChromosome> tests = stagnationDetector.requestHelp(
                        uncoveredGoalsSupplier.get(), new ArrayList<>(population));
                return tests != null ? tests : Collections.emptyList();
            });
        }
        registerAdditionalCandidateSources();
    }

    /**
     * Hook for subclasses to register additional candidate sources beyond
     * the shared LLM sources. Default is a no-op.
     */
    protected void registerAdditionalCandidateSources() {
        // No-op by default
    }

    /**
     * Method used to mutate an offspring.
     *
     * @param offspring the offspring chromosome
     * @param parent    the parent chromosome that {@code offspring} was created from
     */
    private void mutate(TestChromosome offspring, TestChromosome parent) {
        offspring.mutate();
        if (!offspring.isChanged()) {
            // if offspring is not changed, we try to mutate it once again.
            // This acts as a retry mechanism to force exploration.
            offspring.mutate();
        }
        if (!this.hasMethodCall(offspring)) {
            offspring.setTestCase(parent.getTestCase().clone());
            boolean changed = offspring.mutationInsert();
            if (changed) {
                offspring.getTestCase().forEach(Statement::isValid);
            }
            offspring.setChanged(changed);
        }
        this.notifyMutation(offspring);
    }

    /**
     * This method checks whether the test has only primitive type statements. Indeed,
     * crossover and mutation can lead to tests with no method calls (methods or constructors
     * call), thus, when executed they will never cover something in the class under test.
     *
     * @param test to check
     * @return true if the test has at least one method or constructor call (i.e., the test may
     *     cover something when executed; false otherwise
     */
    private boolean hasMethodCall(TestChromosome test) {
        TestCase tc = test.getTestCase();
        for (Statement s : tc) {
            if (s instanceof MethodStatement) {
                MethodStatement ms = (MethodStatement) s;
                if (ms.getDeclaringClassName().equals(Properties.TARGET_CLASS)) {
                    return true;
                }
            }
            if (s instanceof ConstructorStatement) {
                ConstructorStatement ms = (ConstructorStatement) s;
                if (ms.getDeclaringClassName().equals(Properties.TARGET_CLASS)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * This method clears the cached results for a specific chromosome (e.g., fitness function
     * values computed in previous generations). Since a test case is changed via crossover
     * and/or mutation, previous data must be recomputed.
     *
     * @param chromosome TestChromosome to clean
     */
    private void clearCachedResults(TestChromosome chromosome) {
        chromosome.clearCachedMutationResults();
        chromosome.clearCachedResults();
        chromosome.getFitnessValues().clear();
    }

    /**
     * When a test case is changed via crossover and/or mutation, it can contains some
     * primitive variables that are not used as input (or to store the output) of method calls.
     * Thus, this method removes all these "trash" statements.
     *
     * @param chromosome a {@link org.evosuite.testcase.TestChromosome} object.
     * @return true or false depending on whether "unused variables" are removed
     */
    private boolean removeUnusedVariables(TestChromosome chromosome) {
        final int sizeBefore = chromosome.size();
        final TestCase t = chromosome.getTestCase();
        final List<Integer> toDelete = new ArrayList<>(chromosome.size());
        boolean hasDeleted = false;

        int num = 0;
        for (Statement s : t) {
            final VariableReference var = s.getReturnValue();
            final boolean delete = s instanceof PrimitiveStatement || s instanceof ArrayStatement;
            if (!t.hasReferences(var) && delete) {
                toDelete.add(num);
                hasDeleted = true;
            }
            num++;
        }
        toDelete.sort(Collections.reverseOrder());
        for (int position : toDelete) {
            t.remove(position);
        }
        final int sizeAfter = chromosome.size();
        if (hasDeleted) {
            logger.debug("Removed {} unused statements", (sizeBefore - sizeAfter));
        }
        return hasDeleted;
    }

    /**
     * This method extracts non-dominated solutions (tests) according to all covered goal
     * (e.g., branches).
     *
     * @param solutions list of test cases to analyze with the "dominance" relationship
     * @return the non-dominated set of test cases
     */
    public List<TestChromosome> getNonDominatedSolutions(List<TestChromosome> solutions) {
        final DominanceComparator<TestChromosome> comparator =
                new DominanceComparator<>(this.getCoveredGoals());
        final List<TestChromosome> nextFront = new ArrayList<>(solutions.size());
        boolean isDominated;
        for (TestChromosome p : solutions) {
            isDominated = false;
            List<TestChromosome> dominatedSolutions = new ArrayList<>(solutions.size());
            for (TestChromosome best : nextFront) {
                final int flag = comparator.compare(p, best);
                if (flag < 0) {
                    dominatedSolutions.add(best);
                }
                if (flag > 0) {
                    isDominated = true;
                }
            }
            if (isDominated) {
                continue;
            }

            nextFront.add(p);
            nextFront.removeAll(dominatedSolutions);
        }
        return nextFront;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initializePopulation() {
        logger.info("executing initializePopulation function");

        this.notifySearchStarted();
        this.currentIteration = 0;

        // Create a random parent population P0
        this.generateInitialPopulation(Properties.POPULATION);

        // Determine fitness
        this.calculateFitness();
        this.notifyIteration();
    }

    /**
     * Returns the goals that have been covered by the test cases stored in the archive.
     *
     * @return a {@link java.util.Set} object.
     */
    protected Set<TestFitnessFunction> getCoveredGoals() {
        return new LinkedHashSet<>(Archive.getArchiveInstance().getCoveredTargets());
    }

    /**
     * Returns the number of goals that have been covered by the test cases stored in the archive.
     *
     * @return a int.
     */
    protected int getNumberOfCoveredGoals() {
        return Archive.getArchiveInstance().getNumberOfCoveredTargets();
    }

    /**
     * Adds an uncovered goal to the archive.
     *
     * @param goal the goal
     */
    protected void addUncoveredGoal(TestFitnessFunction goal) {
        Archive.getArchiveInstance().addTarget(goal);
    }

    /**
     * Returns the goals that have not been covered by the test cases stored in the archive.
     *
     * @return a {@link java.util.Set} object.
     */
    protected Set<TestFitnessFunction> getUncoveredGoals() {
        return new LinkedHashSet<>(Archive.getArchiveInstance().getUncoveredTargets());
    }

    /**
     * Returns the goals that have not been covered by the test cases stored in the archive.
     *
     * @return a int.
     */
    protected int getNumberOfUncoveredGoals() {
        return Archive.getArchiveInstance().getNumberOfUncoveredTargets();
    }

    /**
     * Returns the total number of goals, i.e., number of covered goals + number of uncovered goals.
     *
     * @return a int.
     */
    protected int getTotalNumberOfGoals() {
        return Archive.getArchiveInstance().getNumberOfTargets();
    }

    /**
     * Return the test cases in the archive as a list.
     *
     * @return a {@link java.util.List} object.
     */
    protected List<TestChromosome> getSolutions() {
        return new ArrayList<>(Archive.getArchiveInstance().getSolutions());
    }

    /**
     * Generates a {@link org.evosuite.testsuite.TestSuiteChromosome} object with all test cases
     * in the archive.
     *
     * @return a {@link org.evosuite.testsuite.TestSuiteChromosome} object.
     */
    public TestSuiteChromosome generateSuite() {
        TestSuiteChromosome suite = new TestSuiteChromosome();
        Archive.getArchiveInstance().getSolutions().forEach(suite::addTest);
        return suite;
    }

    ///// ----------------------

    /**
     * Some methods of the super class (i.e., {@link org.evosuite.ga.metaheuristics.GeneticAlgorithm}
     * class) require a {@link org.evosuite.testsuite.TestSuiteChromosome} object. However, MOSA
     * evolves {@link TestChromosome} objects. Therefore, we must override
     * those methods and create a {@link org.evosuite.testsuite.TestSuiteChromosome} object with all
     * the evolved {@link TestChromosome} objects (either in the population or
     * in the {@link org.evosuite.ga.archive.Archive}.
     */

    @Override
    protected void notifySearchFinished() {
        // Flush disruption analysis sidecar before search listeners fire
        if (DisruptionRecorder.isEnabled()) {
            try {
                DisruptionRecorder.getInstance().flush();
            } catch (Exception e) {
                logger.warn("Failed to flush disruption recorder", e);
            }
        }
        super.notifySearchFinished();
    }

    /**
     * Emit disruption analysis runtime variables to statistics.csv.
     * Safe to call when disruption analysis is disabled (emits zeros/empty).
     * Uses resolveSidecarPath() so the path is deterministic before flush.
     */
    protected void emitDisruptionStats(ClientNodeLocal<?> clientNode) {
        if (DisruptionRecorder.isEnabled()) {
            DisruptionRecorder rec = DisruptionRecorder.getInstance();
            clientNode.trackOutputVariable(RuntimeVariable.Disruption_Events_Total,
                    rec.getTotalEvents());
            clientNode.trackOutputVariable(RuntimeVariable.Disruption_Standard_Mutations,
                    rec.getStandardMutations());
            clientNode.trackOutputVariable(RuntimeVariable.Disruption_Semantic_Mutations,
                    rec.getSemanticMutations());
            clientNode.trackOutputVariable(RuntimeVariable.Disruption_Standard_Crossovers,
                    rec.getStandardCrossovers());
            clientNode.trackOutputVariable(RuntimeVariable.Disruption_Semantic_Crossovers,
                    rec.getSemanticCrossovers());
            clientNode.trackOutputVariable(RuntimeVariable.Disruption_Semantic_Fallbacks,
                    rec.getSemanticFallbacks());
            clientNode.trackOutputVariable(RuntimeVariable.Disruption_Sidecar_Path,
                    rec.getTotalEvents() > 0 ? rec.resolveSidecarPath() : "");
        } else {
            clientNode.trackOutputVariable(RuntimeVariable.Disruption_Events_Total, 0);
            clientNode.trackOutputVariable(RuntimeVariable.Disruption_Standard_Mutations, 0);
            clientNode.trackOutputVariable(RuntimeVariable.Disruption_Semantic_Mutations, 0);
            clientNode.trackOutputVariable(RuntimeVariable.Disruption_Standard_Crossovers, 0);
            clientNode.trackOutputVariable(RuntimeVariable.Disruption_Semantic_Crossovers, 0);
            clientNode.trackOutputVariable(RuntimeVariable.Disruption_Semantic_Fallbacks, 0);
            clientNode.trackOutputVariable(RuntimeVariable.Disruption_Sidecar_Path, "");
        }
    }

    /**
     * Evaluates all fitness functions on the given chromosome and updates the
     * archive's coverage tracking.
     *
     * <h3>Subclass contract</h3>
     * <p>This default implementation evaluates <em>every</em> fitness function
     * registered via {@link #addFitnessFunction} against the chromosome.
     * {@code MOSA} inherits this behavior (O(n × m) where n=chromosomes,
     * m=all goals).
     *
     * <p>{@code DynaMOSA} <strong>overrides</strong> this method to delegate
     * to {@link org.evosuite.ga.metaheuristics.mosa.structural.MultiCriteriaManager#calculateFitness},
     * which evaluates only the structurally reachable goals for the current
     * generation. This is a key performance optimization for DynaMOSA.
     *
     * <p>Callers in shared code (e.g., {@code breedNextGeneration()},
     * {@code collectExternalCandidates()}) should be aware that the set of
     * evaluated goals varies by subclass.
     *
     * @param c the chromosome to evaluate
     */
    @Override
    protected void calculateFitness(TestChromosome c) {
        this.fitnessFunctions.forEach(fitnessFunction -> fitnessFunction.getFitness(c));

        // if one of the coverage criterion is Criterion.EXCEPTION, then we have to analyse the results
        // of the execution to look for generated exceptions
        if (ArrayUtil.contains(Properties.CRITERION, Properties.Criterion.EXCEPTION)) {
            ExceptionCoverageSuiteFitness.calculateExceptionInfo(
                    Collections.singletonList(c.getLastExecutionResult()),
                    new HashMap<>(), new HashMap<>(), new HashMap<>(), new ExceptionCoverageSuiteFitness());
        }

        this.notifyEvaluation(c);
        // update the time needed to reach the max coverage
        this.budgetMonitor.checkMaxCoverage(this.getNumberOfCoveredGoals());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<TestChromosome> getBestIndividuals() {
        return this.getNonDominatedSolutions(this.population);
    }

    /**
     * Applies local search on a snapshot of archive solutions and persists
     * improvements back into the MOSA search state.
     *
     * <h3>Persistence semantics</h3>
     *
     * <p>{@code testSuite} is a transient object built from
     * {@link #generateSuite()}. The adapter delegates to
     * {@link TestSuiteLocalSearch} which may modify or add test chromosomes
     * in-place via AVM, DSE, or LLM search. Suite-level fitness functions
     * called during that process already update the global {@link Archive}.
     *
     * <p>However, MOSA/DynaMOSA also maintain per-goal fitness bookkeeping
     * (e.g., {@code MultiCriteriaManager} in DynaMOSA) that is only updated
     * through {@link #calculateFitness(TestChromosome)}. Therefore, after
     * local search completes, we re-evaluate the suite's test chromosomes
     * through the MOSA-specific fitness path so that:
     * <ul>
     *   <li>The archive is confirmed up-to-date via test-level fitness.</li>
     *   <li>DynaMOSA's goal manager unlocks structurally dependent goals.</li>
     *   <li>The budget monitor records the coverage high-water mark.</li>
     * </ul>
     *
     * <p>Population injection is handled indirectly: LS-sourced tests are
     * staged in {@link #pendingLsTests} and drained into the next
     * generation's union by {@link #collectExternalCandidates}. Only
     * truly LS-introduced tests (post-LS minus pre-LS delta) are staged,
     * so unchanged archive snapshots are never injected.
     *
     * <h3>Conditional execution</h3>
     *
     * <p>Re-evaluation only runs when {@link #shouldApplyLocalSearch()}
     * returned true during the adapter's delegation (tracked via
     * {@link #lastLocalSearchScheduled}). This avoids unnecessary fitness
     * evaluations and budget consumption when LS is skipped due to
     * rate/probability gating.
     *
     * @param testSuite the test suite (typically from {@link #generateSuite()})
     */
    protected void applyLocalSearch(final TestSuiteChromosome testSuite) {
        lastLocalSearchScheduled = false;

        // Snapshot pre-LS suite tests by identity so we can compute the
        // delta afterwards (only truly LS-produced tests should be staged).
        Set<TestChromosome> preLsTests = Collections.newSetFromMap(new IdentityHashMap<>());
        preLsTests.addAll(testSuite.getTestChromosomes());

        adapter.applyLocalSearch(testSuite);

        // Re-evaluate LS-improved tests through the MOSA-specific fitness
        // path only when local search actually executed. Skipping re-evaluation
        // when LS was not applied avoids unnecessary budget consumption.
        if (lastLocalSearchScheduled) {
            for (TestChromosome tc : testSuite.getTestChromosomes()) {
                if (!isFinished()) {
                    this.calculateFitness(tc);
                }
            }
            // Stage only LS-introduced tests (post minus pre) for injection
            // into the next generation's ranking union.
            stageLsTestsForPersistence(testSuite.getTestChromosomes(), preLsTests);
        }
    }

    /**
     * Stages LS-produced tests for injection into the next generation's union.
     * Only tests that are new in the post-LS suite (not present in the pre-LS
     * snapshot) and not already in the current population are staged.
     * This prevents unchanged archive snapshot tests from being incorrectly
     * treated as LS outputs.
     */
    private void stageLsTestsForPersistence(List<TestChromosome> postLsTests,
                                            Set<TestChromosome> preLsTests) {
        pendingLsTests.clear();
        Set<TestChromosome> existing = Collections.newSetFromMap(new IdentityHashMap<>());
        existing.addAll(this.population);
        for (TestChromosome tc : postLsTests) {
            if (!preLsTests.contains(tc) && !existing.contains(tc)) {
                pendingLsTests.add(tc);
            }
        }
        if (!pendingLsTests.isEmpty()) {
            logger.debug("Staged {} LS-improved tests for next generation",
                    pendingLsTests.size());
        }
    }

    /**
     * Overrides the gating check to track whether local search was scheduled.
     * The adapter delegates LS scheduling to this MOSA instance; the flag
     * is read by {@link #applyLocalSearch(TestSuiteChromosome)} to decide
     * whether post-LS re-evaluation is needed.
     */
    @Override
    protected boolean shouldApplyLocalSearch() {
        boolean should = super.shouldApplyLocalSearch();
        if (should) {
            lastLocalSearchScheduled = true;
        }
        return should;
    }

    /**
     * Record species count and largest share for the current generation.
     */
    protected void emitSpeciesTimeline(Map<Integer, List<TestChromosome>> speciesMap) {
        if (Properties.SPECIES_TIMELINE_ENABLED) {
            ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.Species_Count_Timeline, speciesMap.size());
        }
        if (Properties.SPECIES_LARGEST_SHARE_TIMELINE_ENABLED && !speciesMap.isEmpty()) {
            int maxSize = 0;
            int total = 0;
            for (List<TestChromosome> members : speciesMap.values()) {
                maxSize = Math.max(maxSize, members.size());
                total += members.size();
            }
            double share = total > 0 ? (double) maxSize / total : 0.0;
            ClientServices.getInstance().getClientNode()
                .trackOutputVariable(RuntimeVariable.Species_Largest_Share_Timeline, share);
        }
    }

    /**
     * Emit LLM operator statistics to the client node.
     */
    protected void emitOperatorStats(ClientNodeLocal<?> clientNode) {
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
     * Computes the ratio of LLM-parsed statements to total statements in a population.
     */
    protected static double computePopulationParsedRatio(List<TestChromosome> population) {
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
}
