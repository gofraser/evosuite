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
package org.evosuite.llm.search;

import org.evosuite.ga.diversity.JaccardSpeciesDistance;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;

import java.util.Set;

/**
 * Utility methods for computing disruption metrics between pre- and post-operator
 * chromosome states. Used by AbstractMOSA instrumentation to populate
 * {@link DisruptionEvent} fields.
 */
public class DisruptionHelper {

    private DisruptionHelper() {}

    /** Compute aggregate fitness sum, or NaN if fitness values are empty. */
    public static double aggregateFitness(TestChromosome tc) {
        if (tc.getFitnessValues().isEmpty()) return Double.NaN;
        return tc.getFitness();
    }

    /** Compute branch-set Jaccard distance between two evaluated chromosomes. */
    public static double branchJaccardDistance(TestChromosome pre, TestChromosome post) {
        Set<Integer> preSet = JaccardSpeciesDistance.getCoveredBranches(pre);
        Set<Integer> postSet = JaccardSpeciesDistance.getCoveredBranches(post);
        return JaccardSpeciesDistance.jaccardDistance(preSet, postSet);
    }

    /** Compute line-set Jaccard distance between two evaluated chromosomes. */
    public static double lineJaccardDistance(TestChromosome pre, TestChromosome post) {
        Set<Integer> preSet = JaccardSpeciesDistance.getCoveredLines(pre);
        Set<Integer> postSet = JaccardSpeciesDistance.getCoveredLines(post);
        return JaccardSpeciesDistance.jaccardDistance(preSet, postSet);
    }

    /** Compute goal-set Jaccard distance between two evaluated chromosomes. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static double goalJaccardDistance(TestChromosome pre, TestChromosome post) {
        Set preSet = JaccardSpeciesDistance.getCoveredGoals(pre);
        Set postSet = JaccardSpeciesDistance.getCoveredGoals(post);
        return JaccardSpeciesDistance.jaccardDistance(preSet, postSet);
    }

    /** Compute speciation-metric-aligned distance using configured metric. */
    public static double speciationDistance(TestChromosome pre, TestChromosome post) {
        JaccardSpeciesDistance dist = new JaccardSpeciesDistance();
        return dist.distance(pre, post);
    }

    /** Check if a chromosome has been evaluated (has execution results). */
    public static boolean isEvaluated(TestChromosome tc) {
        ExecutionResult result = tc.getLastExecutionResult();
        return result != null && result.getTrace() != null;
    }

    /** Get statement count from a chromosome's test case. */
    public static int statementCount(TestChromosome tc) {
        return tc.getTestCase() != null ? tc.getTestCase().size() : 0;
    }
}
