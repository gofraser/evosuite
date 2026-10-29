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

import org.evosuite.ga.Chromosome;

import java.io.Serializable;


/**
 * A listener that can be attached to the search.
 *
 * @author Gordon Fraser
 */
public interface SearchListener<T extends Chromosome<T>> extends Serializable {

    /**
     * Called when a new search is started.
     */
    default void searchStarted(GeneticAlgorithm<T> algorithm) {
    }

    /**
     * Called after each iteration of the search.
     */
    default void iteration(GeneticAlgorithm<T> algorithm) {
    }

    /**
     * Called after the last iteration.
     */
    default void searchFinished(GeneticAlgorithm<T> algorithm) {
    }

    /**
     * Called after every single fitness evaluation.
     */
    void fitnessEvaluation(T individual);

    /**
     * Called before a chromosome is mutated.
     */
    default void modification(T individual) {
    }

}
