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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * A sidecar-only evaluation of an offspring's state at some point of the breeding loop (after
 * crossover, before mutation): a clone evaluated through {@link IsolatedEvaluation} so that the
 * archive, the goal manager and the search listeners never see it. Carries what the disruption
 * observer needs — the evaluated snapshot, its aggregate fitness and whether the evaluation
 * failed — so one probe can be taken once and handed to every event that records it.
 */
public final class IsolatedProbe {

    private static final Logger logger = LoggerFactory.getLogger(IsolatedProbe.class);

    /** No probe was taken. */
    public static final IsolatedProbe NONE = new IsolatedProbe(null, Double.NaN, false);

    private final TestChromosome snapshot;
    private final double fitness;
    private final boolean failure;

    private IsolatedProbe(TestChromosome snapshot, double fitness, boolean failure) {
        this.snapshot = snapshot;
        this.fitness = fitness;
        this.failure = failure;
    }

    /** Clones {@code offspring} and evaluates the clone against {@code goals} in isolation. */
    public static IsolatedProbe run(TestChromosome offspring, Collection<TestFitnessFunction> goals) {
        try {
            TestChromosome snapshot = offspring.clone();
            IsolatedEvaluation.evaluate(snapshot, goals);
            return of(snapshot);
        } catch (Exception e) {
            logger.debug("Isolated probe failed", e);
            return failed();
        }
    }

    /** A probe that was attempted but could not be evaluated. */
    public static IsolatedProbe failed() {
        return new IsolatedProbe(null, Double.NaN, true);
    }

    /** Wraps a snapshot that was already evaluated in isolation, so it is not run a second time. */
    public static IsolatedProbe of(TestChromosome evaluatedSnapshot) {
        if (evaluatedSnapshot == null) {
            return NONE;
        }
        return new IsolatedProbe(evaluatedSnapshot, DisruptionHelper.aggregateFitness(evaluatedSnapshot), false);
    }

    /** True when a probe was attempted, whether or not it succeeded. */
    public boolean isTaken() {
        return snapshot != null || failure;
    }

    public TestChromosome getSnapshot() {
        return snapshot;
    }

    public double getFitness() {
        return fitness;
    }

    public boolean isFailure() {
        return failure;
    }
}
