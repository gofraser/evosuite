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
package org.evosuite.coverage.branch;

import org.evosuite.Properties;
import org.evosuite.coverage.ControlFlowDistance;
import org.evosuite.ga.archive.Archive;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.utils.ArrayUtil;

import java.util.Objects;

/**
 * Fitness function for a single test on a single branch.
 *
 * @author Gordon Fraser
 */
public class BranchCoverageTestFitness extends TestFitnessFunction {

    private static final long serialVersionUID = -6310967747257242580L;

    /**
     * Target branch.
     */
    private final BranchCoverageGoal goal;

    /**
     * Constructor - fitness is specific to a branch.
     */
    public BranchCoverageTestFitness(BranchCoverageGoal goal) {
        this.goal = Objects.requireNonNull(goal, "goal cannot be null");
    }

    public Branch getBranch() {
        return goal.getBranch();
    }

    public boolean getValue() {
        return goal.getValue();
    }

    public BranchCoverageGoal getBranchGoal() {
        return goal;
    }

    public String getClassName() {
        return goal.getClassName();
    }

    public String getMethod() {
        return goal.getMethodName();
    }

    public boolean getBranchExpressionValue() {
        return goal.getValue();
    }


    /**
     * Approach level and branch distance of this goal in an execution, without recording it in
     * the individual, its covered goals or the archive.
     *
     * @param result the execution to measure
     * @return the distance, 0.0 if the goal is covered
     */
    public double distance(ExecutionResult result) {
        return goal.getDistance(result).getResultingBranchFitness();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Calculate approach level + branch distance.
     */
    @Override
    public double getFitness(TestChromosome individual, ExecutionResult result) {
        ControlFlowDistance distance = goal.getDistance(result);

        double fitness = distance.getResultingBranchFitness();

        if (logger.isDebugEnabled()) {
            logger.debug("Goal at line " + goal.getLineNumber() + ": approach level = " + distance.getApproachLevel()
                    + " / branch distance = " + distance.getBranchDistance() + ", fitness = " + fitness);
        }

        updateIndividual(individual, fitness);

        if (fitness == 0.0) {
            individual.getTestCase().addCoveredGoal(this);
        }

        if (Archive.isUpdating()) {
            updateArchive(individual, fitness);
        }

        return fitness;
    }


    /**
     * Offers the evaluated individual to the archive.
     */
    protected void updateArchive(TestChromosome individual, double fitness) {
        // the next if condition is needed for DynaMOSA when branch coverage is not
        // set as coverage criterion to optimize. However, branches are
        // the backbone for all other criteria and thus they are always used in DynaMOSA
        if (ArrayUtil.contains(Properties.CRITERION, Properties.Criterion.BRANCH)) {
            // Skip when this branch goal isn't a registered target, e.g. a root-branch goal
            // of a method that is not branchless.
            Archive archive = Archive.getArchiveInstance();
            if (archive.hasTarget(this)) {
                archive.updateArchive(this, individual, fitness);
            }
        }
    }

    @Override
    public String toString() {
        return goal.toString();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((goal == null) ? 0 : goal.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        BranchCoverageTestFitness other = (BranchCoverageTestFitness) obj;
        if (goal == null) {
            return other.goal == null;
        } else {
            return goal.equals(other.goal);
        }
    }

    @Override
    public int compareTo(TestFitnessFunction other) {
        if (other instanceof BranchCoverageTestFitness) {
            BranchCoverageTestFitness otherBranchFitness = (BranchCoverageTestFitness) other;
            return goal.compareTo(otherBranchFitness.goal);
        }
        return compareClassName(other);
    }

    @Override
    public String getTargetClass() {
        return getClassName();
    }

    @Override
    public String getTargetMethod() {
        return getMethod();
    }

}
