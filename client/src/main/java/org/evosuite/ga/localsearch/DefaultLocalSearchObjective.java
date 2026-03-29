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
package org.evosuite.ga.localsearch;

import org.evosuite.ga.Chromosome;
import org.evosuite.ga.FitnessFunction;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default local search objective that evaluates fitness improvement by re-evaluating
 * the chromosome's fitness functions after each mutation and comparing with the
 * pre-mutation baseline. This is used when GAs operate directly on individual
 * chromosomes (e.g., MOSA with TestChromosomes) rather than through a test suite wrapper.
 *
 * @author Gordon Fraser
 */
public class DefaultLocalSearchObjective<T extends Chromosome<T>> implements LocalSearchObjective<T>,
        Serializable {

    private static final long serialVersionUID = -8640106627078837108L;

    private final List<FitnessFunction<T>> fitnessFunctions = new ArrayList<>();

    // TODO: This assumes we are not doing NSGA-II
    private boolean isMaximization = false;

    /**
     * Returns false since this objective has no stored chromosome reference
     * to check goal completion. The {@link LocalSearchBudget} controls termination.
     */
    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public boolean hasImproved(T chromosome) {
        return hasChanged(chromosome) < 0;
    }

    @Override
    public boolean hasNotWorsened(T chromosome) {
        return hasChanged(chromosome) < 1;
    }

    /**
     * Re-evaluates the chromosome's fitness and compares with the pre-mutation
     * baseline (the fitness values currently stored on the chromosome before
     * re-evaluation). Returns -1 if improved, 1 if worsened, 0 if unchanged.
     * On worsening, the chromosome's fitness values are restored to the baseline.
     */
    @Override
    public int hasChanged(T chromosome) {
        if (fitnessFunctions.isEmpty()) {
            return 0;
        }

        // Snapshot all fitness values before re-evaluation for potential restore.
        // The stored values reflect the pre-mutation state because the mutation
        // only changes the test case content, not the cached fitness values.
        Map<FitnessFunction<T>, Double> fullBaseline =
                new LinkedHashMap<>(chromosome.getFitnessValues());

        double baselineSum = 0.0;
        for (FitnessFunction<T> ff : fitnessFunctions) {
            Double value = fullBaseline.get(ff);
            if (value != null) {
                baselineSum += value;
            }
        }

        // Re-evaluate the mutated chromosome
        chromosome.setChanged(true);
        LocalSearchBudget.getInstance().countFitnessEvaluation();
        double newSum = 0.0;
        for (FitnessFunction<T> ff : fitnessFunctions) {
            newSum += ff.getFitness(chromosome);
        }

        if (isFitnessBetter(newSum, baselineSum)) {
            return -1;
        } else {
            // Restore pre-mutation fitness so subsequent calls use the correct baseline.
            // This is needed for both "worsened" and "unchanged" because ff.getFitness()
            // updates the chromosome's fitness cache as a side effect.
            chromosome.setFitnessValues(fullBaseline);
            return isFitnessWorse(newSum, baselineSum) ? 1 : 0;
        }
    }

    private boolean isFitnessBetter(double newFitness, double oldFitness) {
        return isMaximization ? newFitness > oldFitness : newFitness < oldFitness;
    }

    private boolean isFitnessWorse(double newFitness, double oldFitness) {
        return isMaximization ? newFitness < oldFitness : newFitness > oldFitness;
    }

    @Override
    public void addFitnessFunction(FitnessFunction<T> fitness) {
        for (FitnessFunction<T> ff : fitnessFunctions) {
            if (ff.isMaximizationFunction() != fitness.isMaximizationFunction()) {
                throw new RuntimeException("Local search only supports composition of multiple criteria");
            }
        }
        isMaximization = fitness.isMaximizationFunction();

        fitnessFunctions.add(fitness);
    }

    @Override
    public boolean isMaximizationObjective() {
        return isMaximization;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<FitnessFunction<T>> getFitnessFunctions() {
        return fitnessFunctions;
    }
}
