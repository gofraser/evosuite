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
package org.evosuite.llm.search;

import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;

/**
 * Isolated view of one semantic (LLM) operator result for the disruption
 * sidecar: the offspring exactly as the operator left it, evaluated through
 * {@link IsolatedEvaluation} against a frozen goal set before any later
 * operator changes it, and — when {@link
 * org.evosuite.Properties#LLM_OPERATOR_PAIRED_PROBE} is on — the "twin": the
 * standard operator applied to a clone of the same parent (or the same
 * parent/partner pair), evaluated against the same goals, then discarded. A
 * standard mutation that changed nothing is still a valid twin: it says the
 * standard operator would have handed the parent back unchanged.
 */
public final class SemanticProbeResult {

    /** No isolated evaluation was taken. */
    public static final SemanticProbeResult NONE =
            new SemanticProbeResult(false, Double.NaN, Double.NaN, false, false, false, Double.NaN, Double.NaN);

    private final boolean semanticEvaluated;
    private final double semanticFitness;
    private final double semanticTargetDistance;
    private final boolean twinEvaluated;
    private final boolean twinFailure;
    private final boolean twinChanged;
    private final double twinFitness;
    private final double twinTargetDistance;

    private SemanticProbeResult(boolean semanticEvaluated, double semanticFitness, double semanticTargetDistance,
                                boolean twinEvaluated, boolean twinFailure, boolean twinChanged,
                                double twinFitness, double twinTargetDistance) {
        this.semanticEvaluated = semanticEvaluated;
        this.semanticFitness = semanticFitness;
        this.semanticTargetDistance = semanticTargetDistance;
        this.twinEvaluated = twinEvaluated;
        this.twinFailure = twinFailure;
        this.twinChanged = twinChanged;
        this.twinFitness = twinFitness;
        this.twinTargetDistance = twinTargetDistance;
    }

    /** Only the semantic result was evaluated in isolation (paired probe off). */
    public static SemanticProbeResult semanticOnly(TestChromosome semanticSnapshot, TestFitnessFunction goal) {
        return new SemanticProbeResult(true, DisruptionHelper.aggregateFitness(semanticSnapshot),
                IsolatedEvaluation.distance(semanticSnapshot, goal),
                false, false, false, Double.NaN, Double.NaN);
    }

    /** Both the semantic result and its standard-operator twin were evaluated against the same goals. */
    public static SemanticProbeResult withTwin(TestChromosome semanticSnapshot, TestChromosome evaluatedTwin,
                                               boolean twinChanged, TestFitnessFunction goal) {
        return new SemanticProbeResult(true, DisruptionHelper.aggregateFitness(semanticSnapshot),
                IsolatedEvaluation.distance(semanticSnapshot, goal),
                true, false, twinChanged, DisruptionHelper.aggregateFitness(evaluatedTwin),
                IsolatedEvaluation.distance(evaluatedTwin, goal));
    }

    /** The semantic result was evaluated but the twin could not be built or evaluated. */
    public static SemanticProbeResult twinFailed(TestChromosome semanticSnapshot, TestFitnessFunction goal) {
        return new SemanticProbeResult(true, DisruptionHelper.aggregateFitness(semanticSnapshot),
                IsolatedEvaluation.distance(semanticSnapshot, goal),
                false, true, false, Double.NaN, Double.NaN);
    }

    public boolean isSemanticEvaluated() {
        return semanticEvaluated;
    }

    public double getSemanticFitness() {
        return semanticFitness;
    }

    public double getSemanticTargetDistance() {
        return semanticTargetDistance;
    }

    public boolean isTwinEvaluated() {
        return twinEvaluated;
    }

    public boolean isTwinFailure() {
        return twinFailure;
    }

    public boolean isTwinChanged() {
        return twinChanged;
    }

    public double getTwinFitness() {
        return twinFitness;
    }

    public double getTwinTargetDistance() {
        return twinTargetDistance;
    }
}
