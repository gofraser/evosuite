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

import java.util.ArrayList;
import java.util.List;

/**
 * (Mu + Lambda) EA
 *
 * @author José Campos
 */
public class MuPlusLambdaEA<T extends Chromosome<T>> extends AbstractMuLambda<T> {

    private static final long serialVersionUID = -8685698059226067598L;

    public MuPlusLambdaEA(ChromosomeFactory<T> factory, int mu, int lambda) {
        super(factory, mu, lambda);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void evolve() {

        List<T> offsprings = new ArrayList<>(this.lambda);

        // create new offsprings by mutating current population
        for (int i = 0; i < this.lambda; i++) {
            T parent = this.population.get(i % this.mu);
            T offspring = parent.clone();
            this.notifyModification(offspring);

            do {
                offspring.mutate();
            } while (!offspring.isChanged());

            offsprings.add(offspring);
        }

        // update fitness values of offsprings
        for (T offspring : offsprings) {
            for (FitnessFunction<T> fitnessFunction : this.fitnessFunctions) {
                fitnessFunction.getFitness(offspring);
                this.notifyEvaluation(offspring);
            }
        }

        // Combine parents and offspring
        this.population.addAll(offsprings);

        // Sort by fitness
        this.sortPopulation();

        // Truncate to mu
        while (this.population.size() > this.mu) {
            this.population.remove(this.population.size() - 1);
        }

        // Update age
        for (T individual : this.population) {
            individual.updateAge(this.currentIteration);
        }

        this.currentIteration++;
    }
}
