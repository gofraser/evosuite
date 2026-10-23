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
package org.evosuite.ga.metaheuristics;

import org.evosuite.testcase.TestChromosome;
import org.evosuite.testsuite.TestSuiteChromosome;

import java.util.Objects;

/**
 * Search Listener to redirect Notifications from an adaptee algorithm to an adapter algorithm.
 *
 * <p>Evaluation and mutation notifications are not forwarded, because the adapter throws an
 * exception when notified of them.
 */
public class AdapteeListener implements SearchListener<TestChromosome> {

    private final GeneticAlgorithm<TestSuiteChromosome> adapter;

    /**
     * Constructor.
     *
     * @param adapter the adapter algorithm
     */
    public AdapteeListener(GeneticAlgorithm<TestSuiteChromosome> adapter) {
        this.adapter = Objects.requireNonNull(adapter);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void searchStarted(GeneticAlgorithm<TestChromosome> algorithm) {
        adapter.notifySearchStarted();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void iteration(GeneticAlgorithm<TestChromosome> algorithm) {
        adapter.notifyIteration();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void searchFinished(GeneticAlgorithm<TestChromosome> algorithm) {
        adapter.notifySearchFinished();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void fitnessEvaluation(TestChromosome individual) {
        // not forwarded to the adapter
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void modification(TestChromosome individual) {
        // not forwarded to the adapter
    }
}
