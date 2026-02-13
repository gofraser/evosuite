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
package org.evosuite.testcase;

import org.evosuite.Properties;
import org.evosuite.ga.bloatcontrol.BloatControlFunction;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.ga.metaheuristics.SearchListener;


/**
 * Bloat control that checks an individual against the best test case.
 *
 * @author Gordon Fraser
 */
public class RelativeTestLengthBloatControl<T extends ExecutableChromosome<T>>
        implements BloatControlFunction<T>, SearchListener<T> {

    private static final long serialVersionUID = -459141492060919204L;

    protected int currentMax;
    protected double bestFitness;

    /**
     * Default constructor.
     */
    public RelativeTestLengthBloatControl() {
        currentMax = 0;
        bestFitness = Double.MAX_VALUE; // FIXXME: Assuming
        // minimizing fitness!
    }

    public RelativeTestLengthBloatControl(final RelativeTestLengthBloatControl<?> that) {
        this.currentMax = that.currentMax;
        this.bestFitness = that.bestFitness;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isTooLong(T chromosome) {
        // Always accept if fitness is better
        if (chromosome.getFitness() < bestFitness) {
            return false;
        }

        // logger.debug("Current - max: "+((TestSuiteChromosome)chromosome).length()+" - "+currentMax);
        if (currentMax > 0) {
            // if(((TestSuiteChromosome)chromosome).length() > bloat_factor *
            // currentMax)
            // logger.debug("Bloat control: "+((TestSuiteChromosome)chromosome).length()
            // +" > "+ bloat_factor * currentMax);

            return chromosome.size() > Properties.BLOAT_FACTOR
                    * currentMax;
        } else {
            return false;
        } // Don't know max length so can't reject!
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void fitnessEvaluation(T result) {
        // TODO Auto-generated method stub

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void iteration(GeneticAlgorithm<T> algorithm) {
        currentMax = algorithm.getBestIndividual().size();
        bestFitness = algorithm.getBestIndividual().getFitness();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void searchFinished(GeneticAlgorithm<T> algorithm) {
        // TODO Auto-generated method stub

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void searchStarted(GeneticAlgorithm<T> algorithm) {
        // TODO Auto-generated method stub

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void modification(T individual) {
        // TODO Auto-generated method stub

    }
}
