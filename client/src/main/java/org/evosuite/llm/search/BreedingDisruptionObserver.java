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

import org.evosuite.testcase.TestChromosome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encapsulates disruption analysis recording during the MOSA breeding loop.
 *
 * <p>This observer separates the purely diagnostic disruption tracking from
 * the core GA breeding logic in {@code AbstractMOSA.breedNextGeneration()}.
 * When disruption analysis is disabled, use {@link #NO_OP} which does nothing.
 */
public class BreedingDisruptionObserver {

    private static final Logger logger = LoggerFactory.getLogger(BreedingDisruptionObserver.class);

    /** Singleton no-op observer for when disruption analysis is disabled. */
    public static final BreedingDisruptionObserver NO_OP = new BreedingDisruptionObserver(null, false);

    private final DisruptionRecorder recorder;
    private final boolean isolatedProbes;

    public BreedingDisruptionObserver(DisruptionRecorder recorder, boolean isolatedProbes) {
        this.recorder = recorder;
        this.isolatedProbes = isolatedProbes;
    }

    /** Returns true if this observer is actually recording events. */
    public boolean isEnabled() {
        return recorder != null;
    }

    /** Returns true if isolated probe metrics should be collected. */
    public boolean isIsolatedProbeEnabled() {
        return isolatedProbes;
    }

    /**
     * Record a crossover disruption event.
     *
     * @param offspring       the offspring after crossover
     * @param parent1         the first parent
     * @param parent2         the second parent
     * @param preStmtCount    statement count before crossover
     * @param attemptResult   LLM operator attempt result
     * @param crossoverApplied whether any crossover was applied
     * @param generation      current generation number
     * @param isolatedFitness isolated probe fitness (NaN if not probed)
     * @param probeFailure    true if isolated probe evaluation failed
     * @param probeSnapshot   the post-crossover snapshot (null if not probed)
     */
    public void recordCrossover(TestChromosome offspring,
                                TestChromosome parent1,
                                TestChromosome parent2,
                                int preStmtCount,
                                OperatorAttemptResult attemptResult,
                                boolean crossoverApplied,
                                int generation,
                                double isolatedFitness,
                                boolean probeFailure,
                                TestChromosome probeSnapshot) {
        if (recorder == null) return;
        try {
            int postStmts = DisruptionHelper.statementCount(offspring);
            int delta = postStmts - preStmtCount;

            double fitnessPre = DisruptionHelper.aggregateFitness(parent1);

            DisruptionEvent.Builder builder = DisruptionEvent.builder()
                    .generation(generation)
                    .eventIndex(recorder.nextEventIndex())
                    .operatorKind(DisruptionEvent.OperatorKind.CROSSOVER)
                    .operatorSource(attemptResult.toOperatorSource())
                    .outcome(attemptResult.toOperatorOutcome())
                    .parent1Hash(System.identityHashCode(parent1))
                    .parent2Hash(System.identityHashCode(parent2))
                    .offspringHash(System.identityHashCode(offspring))
                    .fitnessPreOperator(fitnessPre)
                    .statementCountBefore(preStmtCount)
                    .statementCountAfter(postStmts)
                    .statementCountDelta(delta)
                    .editsAdded(Math.max(delta, 0))
                    .editsRemoved(Math.max(-delta, 0));

            if (!crossoverApplied) {
                builder.acceptedIntoOffspring(false);
            }

            if (isolatedProbes && crossoverApplied && probeSnapshot != null) {
                builder.isolatedProbe(true);
                builder.isolatedFitnessPostCrossover(isolatedFitness);
                if (probeFailure) {
                    builder.probeFailure(true);
                }
                if (!probeFailure && DisruptionHelper.isEvaluated(probeSnapshot)
                        && DisruptionHelper.isEvaluated(parent1)) {
                    builder.branchJaccardDistance(
                            DisruptionHelper.branchJaccardDistance(parent1, probeSnapshot));
                    builder.lineJaccardDistance(
                            DisruptionHelper.lineJaccardDistance(parent1, probeSnapshot));
                    builder.goalJaccardDistance(
                            DisruptionHelper.goalJaccardDistance(parent1, probeSnapshot));
                    builder.speciationMetricDistance(
                            DisruptionHelper.speciationDistance(parent1, probeSnapshot));
                }
            }

            recorder.record(builder.build());
        } catch (Exception e) {
            logger.debug("Failed to record crossover disruption event", e);
        }
    }

    /**
     * Record a mutation disruption event.
     *
     * @param offspring                    the offspring after mutation
     * @param parent                       the parent
     * @param preStmtCount                 statement count before mutation
     * @param attemptResult                LLM operator attempt result
     * @param accepted                     true if offspring was changed and added to population
     * @param generation                   current generation number
     * @param postCrossoverSnapshot        post-crossover snapshot for isolated probe (null if not probed)
     * @param isolatedFitnessPostCrossover isolated fitness after crossover (NaN if not probed)
     * @param crossoverProbeFailure        true if crossover probe failed
     */
    public void recordMutation(TestChromosome offspring,
                               TestChromosome parent,
                               int preStmtCount,
                               OperatorAttemptResult attemptResult,
                               boolean accepted,
                               int generation,
                               TestChromosome postCrossoverSnapshot,
                               double isolatedFitnessPostCrossover,
                               boolean crossoverProbeFailure) {
        if (recorder == null) return;
        try {
            int postStmts = DisruptionHelper.statementCount(offspring);
            int delta = postStmts - preStmtCount;

            double fitnessPre = DisruptionHelper.aggregateFitness(parent);
            double fitnessPost = accepted ? DisruptionHelper.aggregateFitness(offspring) : Double.NaN;
            double fitnessDelta = Double.isNaN(fitnessPre) || Double.isNaN(fitnessPost)
                    ? Double.NaN : fitnessPost - fitnessPre;

            DisruptionEvent.Builder builder = DisruptionEvent.builder()
                    .generation(generation)
                    .eventIndex(recorder.nextEventIndex())
                    .operatorKind(DisruptionEvent.OperatorKind.MUTATION)
                    .operatorSource(attemptResult.toOperatorSource())
                    .outcome(attemptResult.toOperatorOutcome())
                    .parent1Hash(System.identityHashCode(parent))
                    .offspringHash(System.identityHashCode(offspring))
                    .fitnessPreOperator(fitnessPre)
                    .fitnessPostOperator(fitnessPost)
                    .fitnessDelta(fitnessDelta)
                    .statementCountBefore(preStmtCount)
                    .statementCountAfter(postStmts)
                    .statementCountDelta(delta)
                    .editsAdded(Math.max(delta, 0))
                    .editsRemoved(Math.max(-delta, 0))
                    .acceptedIntoOffspring(accepted);

            if (accepted && DisruptionHelper.isEvaluated(offspring) && DisruptionHelper.isEvaluated(parent)) {
                builder.branchJaccardDistance(DisruptionHelper.branchJaccardDistance(parent, offspring));
                builder.lineJaccardDistance(DisruptionHelper.lineJaccardDistance(parent, offspring));
                builder.goalJaccardDistance(DisruptionHelper.goalJaccardDistance(parent, offspring));
                builder.speciationMetricDistance(DisruptionHelper.speciationDistance(parent, offspring));
            }

            if (postCrossoverSnapshot != null || crossoverProbeFailure) {
                builder.isolatedProbe(true);
                builder.isolatedFitnessPostCrossover(isolatedFitnessPostCrossover);

                if (accepted) {
                    double isolatedFitnessPostMutation = fitnessPost;
                    builder.isolatedFitnessPostMutation(isolatedFitnessPostMutation);

                    if (!Double.isNaN(isolatedFitnessPostCrossover) && !Double.isNaN(isolatedFitnessPostMutation)) {
                        builder.isolatedMutationDelta(isolatedFitnessPostMutation - isolatedFitnessPostCrossover);
                    }
                }

                if (crossoverProbeFailure) {
                    builder.probeFailure(true);
                }
            }

            recorder.record(builder.build());
        } catch (Exception e) {
            logger.debug("Failed to record mutation disruption event", e);
        }
    }

    /**
     * Creates the appropriate observer based on current configuration.
     */
    public static BreedingDisruptionObserver create() {
        if (!DisruptionRecorder.isEnabled()) {
            return NO_OP;
        }
        return new BreedingDisruptionObserver(
                DisruptionRecorder.getInstance(),
                DisruptionRecorder.isIsolatedProbeEnabled());
    }
}
