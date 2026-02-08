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
package org.evosuite.coverage.cbranch;

import org.evosuite.Properties;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageGoal;
import org.evosuite.ga.archive.Archive;
import org.evosuite.setup.CallContext;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

/**
 * @author Gordon Fraser, mattia
 */
public class CBranchTestFitness extends TestFitnessFunction {

    private static final long serialVersionUID = -1399396770125054561L;

    private final BranchCoverageGoal branchGoal;

    private final CallContext context;

    public CBranchTestFitness(BranchCoverageGoal branch, CallContext context) {
        this.branchGoal = branch;
        this.context = context;
    }

    public Branch getBranch() {
        return branchGoal.getBranch();
    }

    public boolean getValue() {
        return branchGoal.getValue();
    }

    public CallContext getContext() {
        return context;
    }

    public BranchCoverageGoal getBranchGoal() {
        return branchGoal;
    }

    public int getGenericContextBranchIdentifier() {
        return Objects.hash(branchGoal != null ? branchGoal.hashCodeWithoutValue() : 0, context);
    }

    private double getMethodCallDistance(ExecutionResult result) {
        String key = branchGoal.getClassName() + "." + branchGoal.getMethodName();
        if (!result.getTrace().getMethodContextCount().containsKey(key)) {
            return Double.MAX_VALUE;
        }
        for (Entry<CallContext, Integer> value : result.getTrace().getMethodContextCount().get(key).entrySet()) {

            if (context.matches(value.getKey())) {
                return value.getValue() > 0 ? 0.0 : 1.0;
            }
        }
        return Double.MAX_VALUE;
    }

    private double getPredicateDistance(Map<Integer, Map<CallContext, Double>> distanceMap) {

        if (!distanceMap.containsKey(branchGoal.getBranch().getActualBranchId())) {
            return Double.MAX_VALUE;
        }

        Map<CallContext, Double> distances = distanceMap.get(branchGoal.getBranch().getActualBranchId());

        for (Entry<CallContext, Double> value : distances.entrySet()) {
            if (context.matches(value.getKey())) {
                return value.getValue();
            }
        }

        return Double.MAX_VALUE;
    }

    /* (non-Javadoc)
     * @see org.evosuite.testcase.TestFitnessFunction#getFitness(org.evosuite.testcase.TestChromosome, org.evosuite.testcase.ExecutionResult)
     */
    @Override
    public double getFitness(TestChromosome individual, ExecutionResult result) {
        double fitness = 0.0;

        if (branchGoal.getBranch() == null) {
            fitness = getMethodCallDistance(result);
        } else if (branchGoal.getValue()) {
            fitness = getPredicateDistance(result.getTrace().getTrueDistancesContext());
        } else {
            fitness = getPredicateDistance(result.getTrace().getFalseDistancesContext());
        }

        updateIndividual(individual, fitness);

        if (fitness == 0.0) {
            individual.getTestCase().addCoveredGoal(this);
        }

        if (Properties.TEST_ARCHIVE) {
            Archive.getArchiveInstance().updateArchive(this, individual, fitness);
        }

        return fitness;
    }


    /* (non-Javadoc)
     * @see org.evosuite.testcase.TestFitnessFunction#compareTo(org.evosuite.testcase.TestFitnessFunction)
     */
    @Override
    public int compareTo(TestFitnessFunction other) {
        if (other instanceof CBranchTestFitness) {
            CBranchTestFitness otherBranchFitness = (CBranchTestFitness) other;
            int branchComparison = branchGoal.compareTo(otherBranchFitness.branchGoal);
            if (branchComparison != 0) {
                return branchComparison;
            }
            if (context == null && otherBranchFitness.context == null) {
                return 0;
            }
            if (context == null) {
                return -1;
            }
            if (otherBranchFitness.context == null) {
                return 1;
            }
            // CallContext does not implement Comparable, so we use string representation
            // to ensure deterministic ordering
            return context.toString().compareTo(otherBranchFitness.context.toString());
        }

        return compareClassName(other);
    }

    /* (non-Javadoc)
     * @see org.evosuite.testcase.TestFitnessFunction#getTargetClass()
     */
    @Override
    public String getTargetClass() {
        return branchGoal.getClassName();
    }

    /* (non-Javadoc)
     * @see org.evosuite.testcase.TestFitnessFunction#getTargetMethod()
     */
    @Override
    public String getTargetMethod() {
        return branchGoal.getMethodName();
    }

    @Override
    public String toString() {
        return "Branch " + branchGoal + " in context: " + context.toString();
    }

    public String toStringContext() {
        return context.toString() + ":" + branchGoal;
    }


    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return Objects.hash(branchGoal, context);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
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
        CBranchTestFitness other = (CBranchTestFitness) obj;
        return Objects.equals(branchGoal, other.branchGoal)
               && Objects.equals(context, other.context);
    }

}
