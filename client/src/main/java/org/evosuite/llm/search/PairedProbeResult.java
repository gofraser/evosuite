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
 * What the SYNC paired probe (see
 * {@link org.evosuite.Properties#LLM_OPERATOR_PAIRED_PROBE}) measured for one
 * semantic operator attempt: the standard operator applied to a clone of the
 * same parent (the "twin"), the twin evaluated, then discarded.
 */
public final class PairedProbeResult {

    /** No twin was evaluated. */
    public static final PairedProbeResult NONE = new PairedProbeResult(false, false, Double.NaN, Double.NaN);

    private final boolean evaluated;
    private final boolean failure;
    private final double twinFitness;
    private final double twinTargetDistance;

    private PairedProbeResult(boolean evaluated, boolean failure, double twinFitness, double twinTargetDistance) {
        this.evaluated = evaluated;
        this.failure = failure;
        this.twinFitness = twinFitness;
        this.twinTargetDistance = twinTargetDistance;
    }

    /** The twin was evaluated; captures its aggregate fitness and distance to {@code targetGoal}. */
    public static PairedProbeResult of(TestChromosome evaluatedTwin, TestFitnessFunction targetGoal) {
        double fitness = DisruptionHelper.aggregateFitness(evaluatedTwin);
        Double distance = targetGoal == null ? null : evaluatedTwin.getFitnessValues().get(targetGoal);
        return new PairedProbeResult(true, false, fitness, distance == null ? Double.NaN : distance);
    }

    /** The twin could not be evaluated. */
    public static PairedProbeResult failed() {
        return new PairedProbeResult(false, true, Double.NaN, Double.NaN);
    }

    public boolean isEvaluated() {
        return evaluated;
    }

    public boolean isFailure() {
        return failure;
    }

    public double getTwinFitness() {
        return twinFitness;
    }

    public double getTwinTargetDistance() {
        return twinTargetDistance;
    }
}
