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
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.llm.search.LanguageModelCrossover;
import org.evosuite.llm.search.LanguageModelMutation;
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
     * <p>
     * Each source is drained once per generation. Returned lists may be empty
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

        // Build identity-based reverse lookup for intra-species mating restriction.
        // Maps each individual to its species ID for O(1) lookup of parent1's species.
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
            // select best individuals

            /*
             * the same individual could be selected twice! Is this a problem for crossover?
             * Because crossing over an individual with itself will most certainly give you the
             * same individual again...
             */

            TestChromosome parent1 = this.selectionFunction.select(this.population);
            TestChromosome parent2 = selectParent2(parent1, individualToSpecies);
            TestChromosome offspring1 = parent1.clone();
            TestChromosome offspring2 = parent2.clone();
            // Try LLM crossover first, then fall back to standard crossover
            boolean llmCrossoverApplied = false;
            if (llmCrossover != null) {
                try {
                    llmCrossoverApplied = llmCrossover.tryCrossover(
                            offspring1, offspring2, getUncoveredGoals());
                } catch (Exception e) {
                    logger.debug("LLM crossover error; falling back to standard", e);
                }
            }
            if (!llmCrossoverApplied && Randomness.nextDouble() <= Properties.CROSSOVER_RATE) {
                try {
                    this.crossoverFunction.crossOver(offspring1, offspring2);
                } catch (ConstructionFailedException e) {
                    logger.debug("CrossOver failed.");
                    continue;
                }
            }

            this.removeUnusedVariables(offspring1);
            this.removeUnusedVariables(offspring2);

            // apply mutation on offspring1 (try LLM first)
            boolean llmMut1 = false;
            if (llmMutation != null) {
                try {
                    llmMut1 = llmMutation.tryMutate(offspring1, getUncoveredGoals());
                } catch (Exception e) {
                    logger.debug("LLM mutation error; falling back to standard", e);
                }
            }
            if (!llmMut1) {
                this.mutate(offspring1, parent1);
            }
            if (offspring1.isChanged()) {
                this.clearCachedResults(offspring1);
                offspring1.updateAge(this.currentIteration);
                this.calculateFitness(offspring1);
                offspringPopulation.add(offspring1);
            }

            // apply mutation on offspring2 (try LLM first)
            boolean llmMut2 = false;
            if (llmMutation != null) {
                try {
                    llmMut2 = llmMutation.tryMutate(offspring2, getUncoveredGoals());
                } catch (Exception e) {
                    logger.debug("LLM mutation error; falling back to standard", e);
                }
            }
            if (!llmMut2) {
                this.mutate(offspring2, parent2);
            }
            if (offspring2.isChanged()) {
                this.clearCachedResults(offspring2);
                offspring2.updateAge(this.currentIteration);
                this.calculateFitness(offspring2);
                offspringPopulation.add(offspring2);
            }
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
     * Drains all registered {@link #externalCandidateSources}, evaluates
     * fitness for each candidate, and appends them to the given union list.
     * <p>
     * This is the single integration point for all external candidates
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
        boolean flag = false;
        TestCase tc = test.getTestCase();
        for (Statement s : tc) {
            if (s instanceof MethodStatement) {
                MethodStatement ms = (MethodStatement) s;
                boolean isTargetMethod = ms.getDeclaringClassName().equals(Properties.TARGET_CLASS);
                if (isTargetMethod) {
                    return true;
                }
            }
            if (s instanceof ConstructorStatement) {
                ConstructorStatement ms = (ConstructorStatement) s;
                boolean isTargetMethod = ms.getDeclaringClassName().equals(Properties.TARGET_CLASS);
                if (isTargetMethod) {
                    return true;
                }
            }
        }
        return flag;
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
    protected void notifySearchStarted() {
        super.notifySearchStarted();
    }

    @Override
    protected void notifyIteration() {
        super.notifyIteration();
    }

    @Override
    protected void notifySearchFinished() {
        super.notifySearchFinished();
    }

    /**
     * {@inheritDoc}
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
     * <p>{@code testSuite} is a transient object built from
     * {@link #generateSuite()}. The adapter delegates to
     * {@link TestSuiteLocalSearch} which may modify or add test chromosomes
     * in-place via AVM, DSE, or LLM search. Suite-level fitness functions
     * called during that process already update the global {@link Archive}.
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
     * <p>Population injection is handled indirectly: LS-sourced tests are
     * staged in {@link #pendingLsTests} and drained into the next
     * generation's union by {@link #collectExternalCandidates}. Only
     * truly LS-introduced tests (post-LS minus pre-LS delta) are staged,
     * so unchanged archive snapshots are never injected.
     *
     * <h3>Conditional execution</h3>
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
}
