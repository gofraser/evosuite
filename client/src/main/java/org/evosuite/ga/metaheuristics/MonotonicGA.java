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
package org.evosuite.ga.metaheuristics;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.TimeController;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageFactory;
import org.evosuite.coverage.branch.BranchCoverageGoal;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.testcase.synthesizer.ObjectRuleBranchSelector;
import org.evosuite.testcase.synthesizer.ObjectRuleTelemetry;
import org.evosuite.testcase.synthesizer.TemplateCache;
import org.evosuite.ga.*;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.factories.ObjectRuleSession;
import org.evosuite.testcase.synthesizer.TestCaseLegitimizer;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of steady state GA.
 *
 * @author Gordon Fraser
 */
public class MonotonicGA<T extends Chromosome<T>> extends GeneticAlgorithm<T> {

    private static final long serialVersionUID = 7846967347821123201L;

    protected ReplacementFunction<T> replacementFunction;

    private final Logger logger = LoggerFactory.getLogger(MonotonicGA.class);

    /**
     * Constructor.
     *
     * @param factory a {@link org.evosuite.ga.ChromosomeFactory} object.
     */
    public MonotonicGA(ChromosomeFactory<T> factory) {
        super(factory);

        setReplacementFunction(new FitnessReplacementFunction<>());
    }

    /**
     * Decides whether to keep the offspring.
     *
     * @param parent1    a {@link org.evosuite.ga.Chromosome} object.
     * @param parent2    a {@link org.evosuite.ga.Chromosome} object.
     * @param offspring1 a {@link org.evosuite.ga.Chromosome} object.
     * @param offspring2 a {@link org.evosuite.ga.Chromosome} object.
     * @return a boolean.
     */
    protected boolean keepOffspring(T parent1, T parent2, T offspring1,
                                    T offspring2) {
        return replacementFunction.keepOffspring(parent1, parent2, offspring1, offspring2);
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Override
    protected void evolve() {

        // EvoObj: pick the uncovered branch that object-construction templates should target this
        // generation. No-op unless apply_object_rule is enabled.
        if (Properties.APPLY_OBJECT_RULE) {
            selectObjectRuleWorkingBranch();
        }

        // Elitism
        logger.debug("Elitism");
        List<T> newGeneration = new ArrayList<>(elitism());

        // Add random elements
        // new_generation.addAll(randomism());

        while (!isNextPopulationFull(newGeneration) && !isFinished()) {
            logger.debug("Generating offspring");

            T parent1 = selectionFunction.select(population);
            T parent2;
            if (Properties.HEADLESS_CHICKEN_TEST) {
                parent2 = newRandomIndividual(); // crossover with new random
                // individual
            } else {
                parent2 = selectionFunction.select(population); // crossover
                // with existing
                // individual
            }

            T offspring1 = parent1.clone();
            T offspring2 = parent2.clone();

            try {
                // Crossover
                if (Randomness.nextDouble() <= Properties.CROSSOVER_RATE) {
                    crossoverFunction.crossOver(offspring1, offspring2);
                }

            } catch (ConstructionFailedException e) {
                logger.info("CrossOver failed");
                continue;
            }

            // Mutation
            notifyMutation(offspring1);
            offspring1.mutate();
            notifyMutation(offspring2);
            offspring2.mutate();

            if (offspring1.isChanged()) {
                offspring1.updateAge(currentIteration);
            }
            if (offspring2.isChanged()) {
                offspring2.updateAge(currentIteration);
            }

            // The two offspring replace the parents if and only if one of
            // the offspring is not worse than the best parent.
            for (FitnessFunction<T> fitnessFunction : fitnessFunctions) {
                fitnessFunction.getFitness(offspring1);
                notifyEvaluation(offspring1);
                fitnessFunction.getFitness(offspring2);
                notifyEvaluation(offspring2);
            }

            if (keepOffspring(parent1, parent2, offspring1, offspring2)) {
                logger.debug("Keeping offspring");

                // Reject offspring straight away if it's too long
                int rejected = 0;
                if (isTooLong(offspring1) || offspring1.size() == 0) {
                    rejected++;
                } else {
                    // if(Properties.ADAPTIVE_LOCAL_SEARCH ==
                    // AdaptiveLocalSearchTarget.ALL)
                    // applyAdaptiveLocalSearch(offspring1);
                    if (!isNextPopulationFull(newGeneration)) {
                        newGeneration.add(offspring1);
                    }
                }

                if (isTooLong(offspring2) || offspring2.size() == 0) {
                    rejected++;
                } else {
                    // if(Properties.ADAPTIVE_LOCAL_SEARCH ==
                    // AdaptiveLocalSearchTarget.ALL)
                    // applyAdaptiveLocalSearch(offspring2);
                    if (!isNextPopulationFull(newGeneration)) {
                        newGeneration.add(offspring2);
                    }
                }

                if (rejected == 1) {
                    if (!isNextPopulationFull(newGeneration)) {
                        newGeneration.add(Randomness.choice(parent1, parent2));
                    }
                } else if (rejected == 2) {
                    if (!isNextPopulationFull(newGeneration)) {
                        newGeneration.add(parent1);
                    }
                    if (!isNextPopulationFull(newGeneration)) {
                        newGeneration.add(parent2);
                    }
                }
            } else {
                logger.debug("Keeping parents");
                if (!isNextPopulationFull(newGeneration)) {
                    newGeneration.add(parent1);
                }
                if (!isNextPopulationFull(newGeneration)) {
                    newGeneration.add(parent2);
                }
            }

        }

        population = newGeneration;
        // archive
        updateFitnessFunctionsAndValues();
        recordObjectRuleTemplateSurvival();

        currentIteration++;
    }

    private T newRandomIndividual() {
        T randomChromosome = chromosomeFactory.getChromosome();
        for (FitnessFunction<T> fitnessFunction : this.fitnessFunctions) {
            randomChromosome.addFitness(fitnessFunction);
        }
        return randomChromosome;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initializePopulation() {
        currentIteration = 0;

        // Seed with LLM tests if enabled
        this.seedPopulation();

        notifySearchStarted();

        // Set up initial population
        generateInitialPopulation(Properties.POPULATION);
        logger.debug("Calculating fitness of initial population");
        calculateFitnessAndSortPopulation();

        this.notifyIteration();
    }

    private static final double DELTA = 0.000000001; // it seems there is some
    // rounding error in LS,
    // but hard to debug :(

    /**
     * EvoObj: choose the working branch for the next
     * generation from the uncovered branch goals (MonotonicGA hook). The choice is
     * delegated to {@link ObjectRuleBranchSelector}, which restricts it to branches with a
     * non-empty object graph and drops branches that repeatedly fail synthesis.
     * {@code null} when no eligible branch remains, which disables template synthesis for the
     * generation.
     */
    private void selectObjectRuleWorkingBranch() {
        ObjectRuleSession session =
                TestGenerationContext.getInstance().getObjectRuleSession();
        session.clearWorkingTarget();

        List<BranchCoverageTestFitness> branchGoals = new BranchCoverageFactory().getCoverageGoals();
        Set<BranchCoverageGoal> uncoveredGoals = getUncoveredBranches(getBestIndividual(), branchGoals);
        if (uncoveredGoals != null && !uncoveredGoals.isEmpty()) {
            List<Branch> uncoveredBranches = new ArrayList<>();
            Map<Branch, List<BranchCoverageTestFitness>> uncoveredGoalsByBranch = new LinkedHashMap<>();
            for (BranchCoverageGoal goal : uncoveredGoals) {
                if (goal.getBranch() != null) {
                    uncoveredBranches.add(goal.getBranch());
                }
            }
            for (BranchCoverageTestFitness fitness : branchGoals) {
                if (fitness.getBranch() != null && uncoveredGoals.contains(fitness.getBranchGoal())) {
                    uncoveredGoalsByBranch.computeIfAbsent(fitness.getBranch(), ignored -> new ArrayList<>())
                            .add(fitness);
                }
            }
            Branch workingBranch =
                    ObjectRuleBranchSelector.getInstance().selectWorkingBranch(uncoveredBranches);
            List<BranchCoverageTestFitness> workingGoals =
                    uncoveredGoalsByBranch.get(workingBranch);
            session.setWorkingTarget(this.currentIteration, workingBranch, workingGoals);
            // Evict cached templates for branches that are now covered.
            TemplateCache.getInstance().retainUncovered(uncoveredBranches);
        }
    }

    @SuppressWarnings("unchecked")
    private void recordObjectRuleTemplateSurvival() {
        if (Properties.APPLY_OBJECT_RULE && !population.isEmpty()
                && population.get(0) instanceof org.evosuite.testcase.TestChromosome) {
            ObjectRuleTelemetry.getInstance().recordPopulation(
                    (List<org.evosuite.testcase.TestChromosome>) population, this.currentIteration);
        }
    }

    /**
     * Collects the branch goals not yet covered by the current best suite (EvoObj helper). Returns
     * {@code null} when the best individual is not a {@link TestSuiteChromosome}.
     */
    private Set<BranchCoverageGoal> getUncoveredBranches(T bestIndividual,
                                                         List<BranchCoverageTestFitness> branchGoals) {
        if (!(bestIndividual instanceof TestSuiteChromosome)) {
            return null;
        }

        TestSuiteChromosome testsuite = (TestSuiteChromosome) bestIndividual;
        return computeUncoveredBranches(testsuite.getLastExecutionResults(), branchGoals);
    }

    /**
     * EvoObj helper: a branch goal is covered iff <em>any</em> execution result has branch distance
     * 0 for it. Returns every goal (with a non-null branch) that no result covers. Deciding
     * membership per result — as an earlier version did — makes a goal's status depend only on the
     * last result, so a branch covered by an early test but not the last one is misreported as
     * uncovered. Package-visible and static so it can be unit-tested with mocked traces.
     */
    static Set<BranchCoverageGoal> computeUncoveredBranches(List<ExecutionResult> results,
                                                            List<BranchCoverageTestFitness> branchGoals) {
        Set<BranchCoverageGoal> covered = new HashSet<>();
        for (ExecutionResult result : results) {
            if (result == null) {
                continue;
            }
            for (BranchCoverageTestFitness tf : branchGoals) {
                if (tf.getBranch() == null) {
                    continue;
                }
                int branchId = tf.getBranch().getActualBranchId();
                Double distance = tf.getValue()
                        ? result.getTrace().getTrueDistances().get(branchId)
                        : result.getTrace().getFalseDistances().get(branchId);
                if (distance != null && distance == 0) {
                    covered.add(tf.getBranchGoal());
                }
            }
        }

        Set<BranchCoverageGoal> uncoveredGoals = new HashSet<>();
        for (BranchCoverageTestFitness tf : branchGoals) {
            if (tf.getBranch() == null) {
                continue;
            }
            if (!covered.contains(tf.getBranchGoal())) {
                uncoveredGoals.add(tf.getBranchGoal());
            }
        }

        return uncoveredGoals;
    }

    /** {@inheritDoc} */
    @Override
    public void generateSolution() {
        if (Properties.APPLY_OBJECT_RULE) {
            // EvoObj: the total legitimization budget is measured from search start.
            TestCaseLegitimizer.startTime = System.currentTimeMillis();
        }
        if (Properties.ENABLE_SECONDARY_OBJECTIVE_AFTER > 0 || Properties.ENABLE_SECONDARY_OBJECTIVE_STARVATION) {
            disableFirstSecondaryCriterion();
        }

        if (population.isEmpty()) {
            initializePopulation();
            assert !population.isEmpty() : "Could not create any test";
        }
        try {
            initializeLlmAssistance(this::getUncoveredGoalsForSuiteChromosomes,
                    getFitnessFunction().isMaximizationFunction());

            logger.debug("Starting evolution");
            int starvationCounter = 0;
            double bestFitness = Double.MAX_VALUE;
            double lastBestFitness = Double.MAX_VALUE;
            if (getFitnessFunction().isMaximizationFunction()) {
                bestFitness = 0.0;
                lastBestFitness = 0.0;
            }

            while (!isFinished()) {

                logger.info("Population size before: " + population.size());
                // related to Properties.ENABLE_SECONDARY_OBJECTIVE_AFTER;
                // check the budget progress and activate a secondary criterion
                // according to the property value.

                {
                    double bestFitnessBeforeEvolution = getBestFitness();
                    evolve();
                    sortPopulation();
                    integrateAsyncTestsIntoPopulation();
                    maybeInjectOnStagnationByFitness(getBestFitness(), getUncoveredGoalsForSuiteChromosomes());
                    double bestFitnessAfterEvolution = getBestFitness();

                    if (getFitnessFunction().isMaximizationFunction()) {
                        assert (bestFitnessAfterEvolution >= (bestFitnessBeforeEvolution
                                - DELTA)) : "best fitness before evolve()/sortPopulation() was: "
                                + bestFitnessBeforeEvolution + ", now best fitness is " + bestFitnessAfterEvolution;
                    } else {
                        assert (bestFitnessAfterEvolution <= (bestFitnessBeforeEvolution
                                + DELTA)) : "best fitness before evolve()/sortPopulation() was: "
                                + bestFitnessBeforeEvolution + ", now best fitness is " + bestFitnessAfterEvolution;
                    }
                }

                {
                    double bestFitnessBeforeLocalSearch = getBestFitness();
                    applyLocalSearch();
                    double bestFitnessAfterLocalSearch = getBestFitness();

                    if (getFitnessFunction().isMaximizationFunction()) {
                        assert (bestFitnessAfterLocalSearch >= (bestFitnessBeforeLocalSearch
                                - DELTA)) : "best fitness before applyLocalSearch() was: "
                                + bestFitnessBeforeLocalSearch + ", now best fitness is " + bestFitnessAfterLocalSearch;
                    } else {
                        assert (bestFitnessAfterLocalSearch <= (bestFitnessBeforeLocalSearch
                                + DELTA)) : "best fitness before applyLocalSearch() was: "
                                + bestFitnessBeforeLocalSearch + ", now best fitness is " + bestFitnessAfterLocalSearch;
                    }
                }

                /*
                 * TODO: before explanation: due to static state handling, LS can
                 * worse individuals. so, need to re-sort.
                 *
                 * now: the system tests that were failing have no static state...
                 * so re-sorting does just hide the problem away, and reduce
                 * performance (likely significantly). it is definitively a bug
                 * somewhere...
                 */
                // sortPopulation();

                double newFitness = getBestFitness();

                if (getFitnessFunction().isMaximizationFunction()) {
                    assert (newFitness >= (bestFitness - DELTA)) : "best fitness was: " + bestFitness
                            + ", now best fitness is " + newFitness;
                } else {
                    assert (newFitness <= (bestFitness + DELTA)) : "best fitness was: " + bestFitness
                            + ", now best fitness is " + newFitness;
                }
                bestFitness = newFitness;

                if (Double.compare(bestFitness, lastBestFitness) == 0) {
                    starvationCounter++;
                } else {
                    logger.info("reset starvationCounter after " + starvationCounter + " iterations");
                    starvationCounter = 0;
                    lastBestFitness = bestFitness;

                }

                updateSecondaryCriterion(starvationCounter);

                logger.info("Current iteration: " + currentIteration);
                this.notifyIteration();

                logger.info("Population size: " + population.size());
                logger.info("Best individual has fitness: " + population.get(0).getFitness());
                logger.info("Worst individual has fitness: " + population.get(population.size() - 1).getFitness());

            }
            // archive
            TimeController.execute(this::updateBestIndividualFromArchive, "update from archive", 5_000);
        } finally {
            shutdownLlmAssistance();
        }
        notifySearchFinished();
    }

    private double getBestFitness() {
        return getBestIndividual().getFitness();
    }

    /**
     * Sets the replacement function.
     *
     * @param replacementFunction a {@link org.evosuite.ga.ReplacementFunction} object.
     */
    public void setReplacementFunction(ReplacementFunction<T> replacementFunction) {
        this.replacementFunction = replacementFunction;
    }

    /**
     * Gets the replacement function.
     *
     * @return a {@link org.evosuite.ga.ReplacementFunction} object.
     */
    public ReplacementFunction<T> getReplacementFunction() {
        return replacementFunction;
    }

}
