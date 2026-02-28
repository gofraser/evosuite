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
package org.evosuite.ga.diversity;

import org.evosuite.Properties;
import org.evosuite.Properties.SpeciationMetric;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.execution.MethodCall;

import java.util.*;

/**
 * Jaccard-distance-based species distance implementation supporting
 * all speciation metrics defined in {@link SpeciationMetric}.
 */
public class JaccardSpeciesDistance implements SpeciesDistance {

    private final SpeciationMetric metric;
    private final double phenotypicWeight;

    public JaccardSpeciesDistance() {
        this(Properties.SPECIATION_METRIC, Properties.SPECIATION_HYBRID_PHENOTYPIC_WEIGHT);
    }

    public JaccardSpeciesDistance(SpeciationMetric metric, double phenotypicWeight) {
        this.metric = metric;
        this.phenotypicWeight = phenotypicWeight;
    }

    @Override
    public double distance(TestChromosome a, TestChromosome b) {
        switch (metric) {
            case TRACE_BRANCH_JACCARD:
                return traceBranchJaccard(a, b);
            case TRACE_LINE_JACCARD:
                return traceLineJaccard(a, b);
            case GOAL_JACCARD:
                return goalJaccard(a, b);
            case METHOD_CALL_JACCARD:
                return methodCallJaccard(a, b);
            case HYBRID:
                double pheno = traceBranchJaccard(a, b);
                double geno = methodCallJaccard(a, b);
                return phenotypicWeight * pheno + (1.0 - phenotypicWeight) * geno;
            default:
                return traceBranchJaccard(a, b);
        }
    }

    private double traceBranchJaccard(TestChromosome a, TestChromosome b) {
        Set<Integer> setA = getCoveredBranches(a);
        Set<Integer> setB = getCoveredBranches(b);
        return jaccardDistance(setA, setB);
    }

    private double traceLineJaccard(TestChromosome a, TestChromosome b) {
        Set<Integer> setA = getCoveredLines(a);
        Set<Integer> setB = getCoveredLines(b);
        return jaccardDistance(setA, setB);
    }

    private double goalJaccard(TestChromosome a, TestChromosome b) {
        Set<FitnessFunction<?>> setA = getCoveredGoals(a);
        Set<FitnessFunction<?>> setB = getCoveredGoals(b);
        return jaccardDistance(setA, setB);
    }

    private double methodCallJaccard(TestChromosome a, TestChromosome b) {
        Set<String> setA = getMethodSignatures(a);
        Set<String> setB = getMethodSignatures(b);
        return jaccardDistance(setA, setB);
    }

    public static Set<Integer> getCoveredBranches(TestChromosome tc) {
        ExecutionResult result = tc.getLastExecutionResult();
        if (result == null || result.getTrace() == null) {
            return Collections.emptySet();
        }
        ExecutionTrace trace = result.getTrace();
        Set<Integer> branches = new HashSet<>();
        branches.addAll(trace.getCoveredTrueBranches());
        branches.addAll(trace.getCoveredFalseBranches());
        return branches;
    }

    public static Set<Integer> getCoveredLines(TestChromosome tc) {
        ExecutionResult result = tc.getLastExecutionResult();
        if (result == null || result.getTrace() == null) {
            return Collections.emptySet();
        }
        return new HashSet<>(result.getTrace().getCoveredLines());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Set<FitnessFunction<?>> getCoveredGoals(TestChromosome tc) {
        Map<FitnessFunction<TestChromosome>, Double> fitnessValues = tc.getFitnessValues();
        Set<FitnessFunction<?>> covered = new HashSet<>();
        for (Map.Entry<FitnessFunction<TestChromosome>, Double> entry : fitnessValues.entrySet()) {
            if (entry.getValue() == 0.0) {
                covered.add((FitnessFunction) entry.getKey());
            }
        }
        return covered;
    }

    public static Set<String> getMethodSignatures(TestChromosome tc) {
        ExecutionResult result = tc.getLastExecutionResult();
        if (result == null || result.getTrace() == null) {
            return Collections.emptySet();
        }
        Set<String> sigs = new HashSet<>();
        for (MethodCall mc : result.getTrace().getMethodCalls()) {
            sigs.add(mc.className + "." + mc.methodName);
        }
        return sigs;
    }

    /**
     * Compute Jaccard distance = 1 - |A ∩ B| / |A ∪ B|.
     * Returns 1.0 when both sets are empty (maximally uninformative).
     */
    public static <T> double jaccardDistance(Set<T> a, Set<T> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        int intersectionSize = 0;
        // iterate over the smaller set for efficiency
        Set<T> smaller = a.size() <= b.size() ? a : b;
        Set<T> larger = a.size() <= b.size() ? b : a;
        for (T elem : smaller) {
            if (larger.contains(elem)) {
                intersectionSize++;
            }
        }
        int unionSize = a.size() + b.size() - intersectionSize;
        return 1.0 - ((double) intersectionSize / unionSize);
    }
}
