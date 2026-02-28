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

import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.execution.MethodCall;

import java.util.*;

/**
 * State Transition Frequency (STF) distance between two test chromosomes.
 * Measures the cosine distance between branch-transition frequency vectors.
 *
 * <p>A "branch transition" is a pair of consecutively covered branches (bigrams).
 * The frequency of each transition is counted and turned into a vector.
 * The distance is {@code 1 - cosine_similarity(v1, v2)}, yielding [0, 1].
 *
 * <p>This metric is <b>optional</b> and property-gated via
 * {@code Properties.STF_ENABLED}. It is not a default metric.
 */
public class STFDistance implements SpeciesDistance {

    private final double jaccardWeight;
    private final JaccardSpeciesDistance jaccardFallback;

    /**
     * Create a pure STF distance (no hybrid weighting).
     */
    public STFDistance() {
        this(0.0, null);
    }

    /**
     * Create a hybrid STF + Jaccard distance.
     *
     * @param jaccardWeight weight for the Jaccard component in [0, 1];
     *                      STF weight is {@code 1 - jaccardWeight}
     * @param jaccardDistance the Jaccard distance to use for the hybrid component
     */
    public STFDistance(double jaccardWeight, JaccardSpeciesDistance jaccardDistance) {
        this.jaccardWeight = Math.max(0.0, Math.min(1.0, jaccardWeight));
        this.jaccardFallback = jaccardDistance;
    }

    @Override
    public double distance(TestChromosome a, TestChromosome b) {
        double stfDist = stfCosineDistance(a, b);
        if (jaccardWeight > 0.0 && jaccardFallback != null) {
            double jDist = jaccardFallback.distance(a, b);
            return jaccardWeight * jDist + (1.0 - jaccardWeight) * stfDist;
        }
        return stfDist;
    }

    /**
     * Compute cosine distance between branch-transition frequency vectors.
     */
    static double stfCosineDistance(TestChromosome a, TestChromosome b) {
        Map<Long, Integer> freqA = getBranchTransitionFrequencies(a);
        Map<Long, Integer> freqB = getBranchTransitionFrequencies(b);

        if (freqA.isEmpty() && freqB.isEmpty()) {
            return 1.0; // no information → maximally uninformative
        }
        if (freqA.isEmpty() || freqB.isEmpty()) {
            return 1.0; // one has no transitions
        }

        // Compute cosine similarity
        Set<Long> allKeys = new HashSet<>(freqA.keySet());
        allKeys.addAll(freqB.keySet());

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (Long key : allKeys) {
            int va = freqA.getOrDefault(key, 0);
            int vb = freqB.getOrDefault(key, 0);
            dotProduct += (double) va * vb;
            normA += (double) va * va;
            normB += (double) vb * vb;
        }

        if (normA == 0.0 || normB == 0.0) {
            return 1.0;
        }

        double cosineSimilarity = dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
        return Math.max(0.0, Math.min(1.0, 1.0 - cosineSimilarity));
    }

    /**
     * Extract branch transition bigram frequencies from a test chromosome's execution trace.
     * A transition is encoded as {@code (prevBranch << 32) | currBranch}.
     */
    static Map<Long, Integer> getBranchTransitionFrequencies(TestChromosome tc) {
        ExecutionResult result = tc.getLastExecutionResult();
        if (result == null || result.getTrace() == null) {
            return Collections.emptyMap();
        }

        List<Integer> branchSequence = extractBranchSequence(result.getTrace());
        if (branchSequence.size() < 2) {
            return Collections.emptyMap();
        }

        Map<Long, Integer> freq = new HashMap<>();
        for (int i = 0; i < branchSequence.size() - 1; i++) {
            long key = ((long) branchSequence.get(i) << 32) | (branchSequence.get(i + 1) & 0xFFFFFFFFL);
            freq.merge(key, 1, Integer::sum);
        }
        return freq;
    }

    /**
     * Extract an ordered branch sequence from method calls in the execution trace.
     */
    private static List<Integer> extractBranchSequence(ExecutionTrace trace) {
        List<Integer> sequence = new ArrayList<>();
        for (MethodCall mc : trace.getMethodCalls()) {
            if (mc.branchTrace != null) {
                for (int branch : mc.branchTrace) {
                    sequence.add(branch);
                }
            }
        }
        return sequence;
    }
}
