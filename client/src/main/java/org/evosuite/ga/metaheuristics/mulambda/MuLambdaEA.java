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
import org.evosuite.ga.FitnessFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.util.Collections.reverseOrder;

/**
 * (Mu, Lambda) EA.
 *
 * @author José Campos
 */
public class MuLambdaEA<T extends Chromosome<T>> extends AbstractMuLambda<T> {

    private static final long serialVersionUID = -1104094637643130537L;

    private static final Logger logger = LoggerFactory.getLogger(MuLambdaEA.class);

    /**
     * Constructor.
     *
     * @param factory the chromosome factory
     * @param mu the population size
     * @param lambda the offspring size
     */
    public MuLambdaEA(ChromosomeFactory<T> factory, int mu, int lambda) {
        super(factory, mu, lambda);
        if (lambda < mu) {
            throw new IllegalArgumentException("Lambda must be greater than or equal to Mu");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void evolveInternal() {

        List<T> offspring = new ArrayList<>(this.lambda);

        // create new offspring by mutating current population
        for (int i = 0; i < this.lambda; i++) {
            T parent = this.population.get(i % this.mu);
            T t = parent.clone();

            do {
                this.notifyMutation(t);
                t.mutate();
            } while (!t.isChanged());

            offspring.add(t);
        }

        // update fitness values of offspring
        for (T t : offspring) {
            for (FitnessFunction<T> fitnessFunction : this.fitnessFunctions) {
                fitnessFunction.getFitness(t);
                this.notifyEvaluation(t);
            }
        }

        if (this.getFitnessFunction().isMaximizationFunction()) {
            // this if condition assumes *all* fitness functions are either to be maximized or to be
            // minimized

            // sort offspring from the one with the highest fitness value to the one with the lowest
            // fitness value
            offspring.sort(reverseOrder());
        } else {
            // sort offspring from the one with the lowest fitness value to the one with the highest
            // fitness value
            Collections.sort(offspring);
        }

        // replace mu (i.e., population) out of lambda (i.e., offspring)
        this.population.clear();
        for (int i = 0; i < this.mu; i++) {
            T bestOffspring = offspring.get(i);
            logger.debug("New population individual " + i + ": " + bestOffspring.getFitness());
            this.population.add(bestOffspring);
        }
    }
}
