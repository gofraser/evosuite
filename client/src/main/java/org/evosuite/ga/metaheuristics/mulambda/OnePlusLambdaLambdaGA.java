/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.ga.metaheuristics.mulambda;

import org.evosuite.ga.Chromosome;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.ConstructionFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 1+(lambda,lambda) GA
 *
 * @author Yan Ge
 */
public class OnePlusLambdaLambdaGA<T extends Chromosome<T>> extends AbstractMuLambda<T> {

    private static final long serialVersionUID = 529089847512798127L;

    private static final Logger logger = LoggerFactory.getLogger(OnePlusLambdaLambdaGA.class);

    public OnePlusLambdaLambdaGA(ChromosomeFactory<T> factory, int lambda) {
        super(factory, 1, lambda);
    }

    @Override
    protected void evolve() {

        T parent = population.get(0);

        // 1. Mutation Phase
        List<T> mutants = new ArrayList<>(this.lambda);
        while (mutants.size() < this.lambda) {
            // clone firstly offspring from parent
            T mutationOffspring = parent.clone();
            notifyMutation(mutationOffspring);

            // perform mutation operation with high probability
            mutationOffspring.mutate();
            mutants.add(mutationOffspring);
        }

        evaluate(mutants);
        sort(mutants);
        T bestMutantOffspring = mutants.get(0);

        // 2. Crossover Phase
        List<T> crossoverOffsprings = new ArrayList<>(this.lambda);

        while (crossoverOffsprings.size() < this.lambda) {
            try {
                T p1 = parent.clone();
                T p2 = bestMutantOffspring.clone();

                crossoverFunction.crossOver(p1, p2);

                crossoverOffsprings.add(p1);
                if (crossoverOffsprings.size() < this.lambda) {
                    crossoverOffsprings.add(p2);
                }
            } catch (ConstructionFailedException e) {
                logger.info("CrossOver failed.");
            }
        }

        evaluate(crossoverOffsprings);
        sort(crossoverOffsprings);
        T bestCrossoverOffspring = crossoverOffsprings.get(0);

        // 3. Selection
        T bestCandidate = isBetterOrEqual(bestCrossoverOffspring, parent) ? bestCrossoverOffspring : parent;
        T nextParent = isBetterOrEqual(bestCandidate, bestMutantOffspring) ? bestCandidate : bestMutantOffspring;

        this.population.clear();
        this.population.add(nextParent);

        updateFitnessFunctionsAndValues();

        currentIteration++;
    }

    private void evaluate(List<T> individuals) {
        for (T ind : individuals) {
            for (org.evosuite.ga.FitnessFunction<T> ff : this.fitnessFunctions) {
                ff.getFitness(ind);
                notifyEvaluation(ind);
            }
        }
    }

    private void sort(List<T> individuals) {
        if (isMaximizationFunction()) {
            individuals.sort(java.util.Collections.reverseOrder());
        } else {
            java.util.Collections.sort(individuals);
        }
    }
}
