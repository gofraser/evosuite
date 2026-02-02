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
package org.evosuite.ga.metaheuristics.mosa.structural;

import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.ga.archive.Archive;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A class for managing coverage targets based on structural dependencies. More specifically,
 * control dependence information of the UIT is used to derive the set of targets currently aimed
 * at. Also maintains an archive of the best chromosomes satisfying a given coverage goal.
 *
 * @author Annibale Panichella
 */
public abstract class StructuralGoalManager implements Serializable {

    private static final long serialVersionUID = -2577487057354286024L;

    private static final Logger logger = LoggerFactory.getLogger(StructuralGoalManager.class);

    protected BranchFitnessGraph graph;

    protected final Map<Integer, TestFitnessFunction> branchCoverageTrueMap = new LinkedHashMap<>();
    protected final Map<Integer, TestFitnessFunction> branchCoverageFalseMap = new LinkedHashMap<>();
    protected final Map<String, TestFitnessFunction> branchlessMethodCoverageMap = new LinkedHashMap<>();

    /**
     * Set of goals currently used as objectives.
     * <p>
     * The idea is to consider only those goals that are independent from any other targets. That
     * is, the goals that
     * <ol>
     *     <li>are free of control dependencies, or</li>
     *     <li>only have direct control dependencies to already covered goals.</li>
     * </ol>
     * <p>
     * Each goal is encoded by a corresponding fitness function, which returns an optimal fitness value if the goal has been reached by a given
     * chromosome. All functions are required to be either minimization or maximization functions,
     * not a mix of both.
     */
    protected Set<TestFitnessFunction> currentGoals;

    /**
     * Archive of tests and corresponding covered targets
     */
    protected Archive archive;

    /**
     * Creates a new {@code StructuralGoalManager} with the given list of targets.
     *
     * @param fitnessFunctions The targets to cover, with each individual target encoded as its own
     *                         fitness function.
     */
    protected StructuralGoalManager(List<TestFitnessFunction> fitnessFunctions) {
        currentGoals = new HashSet<>(fitnessFunctions.size());
        archive = Archive.getArchiveInstance();

        // initialize uncovered goals
        this.archive.addTargets(fitnessFunctions);
    }

    /**
     * Update the set of covered goals and the set of current goals (actual objectives)
     *
     * @param c a TestChromosome
     * @return covered goals along with the corresponding test case
     */
    public void calculateFitness(TestChromosome c, GeneticAlgorithm<TestChromosome> ga) {
        // Run the test and record the execution result.
        TestCase test = c.getTestCase();
        ExecutionResult result = TestCaseExecutor.runTest(test);
        c.setLastExecutionResult(result);
        c.setChanged(false);

        // If the test failed to execute properly, or if the test does not cover anything,
        // it means none of the current goals could be reached.
        if (result.hasTimeout() || result.hasTestException() || result.getTrace().getCoveredLines().isEmpty()) {
            currentGoals.forEach(f -> c.setFitness(f, Double.MAX_VALUE)); // assume minimization
            return;
        }

        Set<TestFitnessFunction> visitedTargets = new LinkedHashSet<>(getUncoveredGoals().size() * 2);

        /*
         * The processing list of current targets. If it turns out that any such target has been
         * reached, we also enqueue its structural and control-dependent children. This is to
         * determine which of those children are already reached by control flow. Only the missed
         * children will be part of the currentGoals for the next generation (together with the
         * missed goals of the currentGoals of the current generation).
         */
        LinkedList<TestFitnessFunction> targets = new LinkedList<>(this.currentGoals);

        // 1) We update the set of current goals.
        while (!targets.isEmpty() && !ga.isFinished()) {
            // We evaluate the given test case against all current targets.
            // (There might have been serendipitous coverage of other targets, though.)
            TestFitnessFunction target = targets.poll();

            if (!visitedTargets.add(target))
                continue;

            double fitness = target.getFitness(c);

            /*
             * Checks if the current test target has been reached and, in accordance, marks it as
             * covered or uncovered.
             */
            if (fitness == 0.0) { // assume minimization function
                updateCoveredGoals(target, c); // marks the current goal as covered

                for (TestFitnessFunction child : getDependencies(target)) {
                    targets.addLast(child);
                }
            } else {
                currentGoals.add(target); // marks the goal as uncovered
            }
        }

        // Removes all newly covered goals from the list of currently uncovered goals.
        currentGoals.removeAll(this.getCoveredGoals());

        // 2) We update the archive.
        updateCoveredGoalsFromTrace(result, c);

        // 3) Hook for subclass specific post-processing
        postCalculateFitness(c);
    }

    protected abstract Set<TestFitnessFunction> getDependencies(TestFitnessFunction fitnessFunction);

    protected void postCalculateFitness(TestChromosome c) {
        // Default implementation does nothing
    }

    protected void initializeMaps(Set<TestFitnessFunction> goals) {
        for (TestFitnessFunction ff : goals) {
            if (!(ff instanceof BranchCoverageTestFitness)) continue;

            BranchCoverageTestFitness goal = (BranchCoverageTestFitness) ff;

            // Skip instrumented branches - we only want real branches
            if (goal.getBranch() != null && goal.getBranch().isInstrumented()) {
                continue;
            }

            if (goal.getBranch() == null) { // the goal is to call the method at hand
                branchlessMethodCoverageMap.put(goal.getClassName() + "." + goal.getMethod(), ff);
            } else if (goal.getBranchExpressionValue()) { // we want to take the given branch
                branchCoverageTrueMap.put(goal.getBranch().getActualBranchId(), ff);
            } else { // we don't want to take the given branch
                branchCoverageFalseMap.put(goal.getBranch().getActualBranchId(), ff);
            }
        }
    }

    protected void updateCoveredGoalsFromTrace(ExecutionResult result, TestChromosome c) {
        ExecutionTrace trace = result.getTrace();
        for (Integer branchID : trace.getCoveredFalseBranches()) {
            TestFitnessFunction branch = this.branchCoverageFalseMap.get(branchID);
            if (branch == null)
                continue;
            updateCoveredGoals(branch, c);
        }
        for (Integer branchID : trace.getCoveredTrueBranches()) {
            TestFitnessFunction branch = this.branchCoverageTrueMap.get(branchID);
            if (branch == null)
                continue;
            updateCoveredGoals(branch, c);
        }
        for (String method : trace.getCoveredBranchlessMethods()) {
            TestFitnessFunction branch = this.branchlessMethodCoverageMap.get(method);
            if (branch == null)
                continue;
            updateCoveredGoals(branch, c);
        }
    }

    /**
     * Returns the set of yet uncovered goals.
     *
     * @return uncovered goals
     */
    public Set<TestFitnessFunction> getUncoveredGoals() {
        return this.archive.getUncoveredTargets();
    }

    /**
     * Returns the subset of uncovered goals that are currently targeted. Each such goal has a
     * direct control dependency to one of the already covered goals.
     *
     * @return all currently targeted goals
     */
    public Set<TestFitnessFunction> getCurrentGoals() {
        return currentGoals;
    }

    /**
     * Returns the set of already covered goals.
     *
     * @return the covered goals
     */
    public Set<TestFitnessFunction> getCoveredGoals() {
        return this.archive.getCoveredTargets();
    }

    /**
     * Tells whether an individual covering the given target is already present in the archive.
     *
     * @param target the goal to be covered
     * @return {@code true} if the archive contains a chromosome that covers the target
     */
    protected boolean isAlreadyCovered(TestFitnessFunction target) {
        return this.archive.getCoveredTargets().contains(target);
    }

    /**
     * Records that the given coverage goal is satisfied by the given chromosome.
     *
     * @param f  the coverage goal to be satisfied
     * @param tc the chromosome satisfying the goal
     */
    protected void updateCoveredGoals(TestFitnessFunction f, TestChromosome tc) {
        // the next two lines are needed since that coverage information are used
        // during EvoSuite post-processing
        tc.getTestCase().getCoveredGoals().add(f);

        // update covered targets
        this.archive.updateArchive(f, tc, tc.getFitness(f));
    }

    public BranchFitnessGraph getGraph() {
        return graph;
    }
}
