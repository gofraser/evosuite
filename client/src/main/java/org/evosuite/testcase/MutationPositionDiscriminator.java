package org.evosuite.testcase;

import java.util.Arrays;
import java.util.Map;

import org.evosuite.Properties;
import org.evosuite.ga.Chromosome;

/**
 * Weights mutation positions during EvoObj legitimization.
 *
 * <p>This is the simplified, uniform-probability variant:
 * it keeps the public surface consumed by {@link org.evosuite.testcase.synthesizer.TestCaseLegitimizer}
 * (the frozen-iteration bookkeeping and the probability helpers) but does not carry the fork's
 * online-learning machinery, which relied on a per-statement change-relevance map that upstream
 * {@code Statement} does not have. All positions are therefore equally likely to be mutated.
 * The full learning variant can replace this class later without touching the legitimizer.
 */
public class MutationPositionDiscriminator {

    public static MutationPositionDiscriminator discriminator = new MutationPositionDiscriminator();

    public int frozenIteration = Properties.PRE_ONLINE_LEARNING_ITERATION;

    public MutationPositionDiscriminator() {
    }

    public void resetFrozenIteration() {
        frozenIteration = Properties.PRE_ONLINE_LEARNING_ITERATION;
    }

    public void decreaseFrozenIteration() {
        if (frozenIteration > 0) {
            frozenIteration--;
        }
    }

    public boolean isFrozen() {
        return frozenIteration > 0;
    }

    /**
     * Records the currently pursued goals. In this simplified variant the goals do not influence
     * position weighting, so this is a no-op kept for API compatibility with the legitimizer.
     */
    public void setPurpose(Map<?, ?> fitnessValues) {
        // no-op: uniform mutation probabilities are used
    }

    /**
     * In the full variant this compares the offspring/parent fitness to learn which positions
     * matter. The simplified variant does not learn, so this is a no-op.
     */
    public void identifyRelevantMutations(Chromosome<?> offspring, Chromosome<?> parent) {
        // no-op: no online learning in the simplified variant
    }

    /**
     * Uniform mutation probability over the mutatable statements (all but the last).
     */
    public static double[] calculateMutationProbability(TestChromosome test) {
        int size = test.size() - 1;
        if (size <= 0) {
            return new double[0];
        }
        double[] distribution = new double[size];
        Arrays.fill(distribution, 1d / size);
        return distribution;
    }

    /**
     * Soft-max style normalization, kept from the fork (pure, deterministic). For a uniform input
     * this returns a uniform output, but it is retained so a future learning variant slots in
     * without changing the legitimizer's call sequence.
     */
    public static double[] normalizeProbability(double[] mutationProbability) {
        if (mutationProbability.length == 0) {
            return mutationProbability;
        }

        double max = max(mutationProbability);
        if (max == 0) {
            return mutationProbability;
        }
        double ratio = 1 / max;

        double sum = 0d;
        for (int i = 0; i < mutationProbability.length; i++) {
            double v = Math.pow(Math.E, mutationProbability[i] * ratio * 5);
            mutationProbability[i] = v;
            sum += mutationProbability[i];
        }

        for (int i = 0; i < mutationProbability.length; i++) {
            mutationProbability[i] = mutationProbability[i] / sum;
        }

        double newMax = max(mutationProbability);
        if (newMax == 0) {
            return mutationProbability;
        }
        double newRatio = 1 / newMax;
        for (int i = 0; i < mutationProbability.length; i++) {
            mutationProbability[i] = mutationProbability[i] * newRatio;
        }

        return mutationProbability;
    }

    private static double max(double[] values) {
        double max = 0;
        for (double value : values) {
            if (max < value) {
                max = value;
            }
        }
        return max;
    }
}
