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
            new SemanticProbeResult(IsolatedProbe.NONE, IsolatedProbe.NONE, false, null);

    private final IsolatedProbe semantic;
    private final IsolatedProbe twin;
    private final boolean twinChanged;
    /** Distances to the target goal, read at construction: the snapshots may be re-evaluated later. */
    private final double semanticTargetDistance;
    private final double twinTargetDistance;

    private SemanticProbeResult(IsolatedProbe semantic, IsolatedProbe twin, boolean twinChanged,
                                TestFitnessFunction goal) {
        this.semantic = semantic;
        this.twin = twin;
        this.twinChanged = twinChanged;
        this.semanticTargetDistance = IsolatedEvaluation.distance(semantic.getSnapshot(), goal);
        this.twinTargetDistance = IsolatedEvaluation.distance(twin.getSnapshot(), goal);
    }

    /** Only the semantic result was evaluated in isolation (paired probe off). */
    public static SemanticProbeResult semanticOnly(TestChromosome semanticSnapshot, TestFitnessFunction goal) {
        return new SemanticProbeResult(IsolatedProbe.of(semanticSnapshot), IsolatedProbe.NONE, false, goal);
    }

    /** Both the semantic result and its standard-operator twin were evaluated against the same goals. */
    public static SemanticProbeResult withTwin(TestChromosome semanticSnapshot, TestChromosome evaluatedTwin,
                                               boolean twinChanged, TestFitnessFunction goal) {
        return new SemanticProbeResult(IsolatedProbe.of(semanticSnapshot), IsolatedProbe.of(evaluatedTwin),
                twinChanged, goal);
    }

    /** The semantic result was evaluated but the twin could not be built or evaluated. */
    public static SemanticProbeResult twinFailed(TestChromosome semanticSnapshot, TestFitnessFunction goal) {
        return new SemanticProbeResult(IsolatedProbe.of(semanticSnapshot), IsolatedProbe.failed(), false, goal);
    }

    /** The semantic offspring as evaluated in isolation, or null when none was taken; reusable as the post-crossover probe. */
    public TestChromosome getSemanticSnapshot() {
        return semantic.getSnapshot();
    }

    public boolean isSemanticEvaluated() {
        return semantic.getSnapshot() != null;
    }

    public double getSemanticFitness() {
        return semantic.getFitness();
    }

    public double getSemanticTargetDistance() {
        return semanticTargetDistance;
    }

    public boolean isTwinEvaluated() {
        return twin.getSnapshot() != null;
    }

    public boolean isTwinFailure() {
        return twin.isFailure();
    }

    public boolean isTwinChanged() {
        return twinChanged;
    }

    public double getTwinFitness() {
        return twin.getFitness();
    }

    public double getTwinTargetDistance() {
        return twinTargetDistance;
    }
}
