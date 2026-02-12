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
package org.evosuite.ga.stoppingconditions;

import org.evosuite.ga.Chromosome;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;

/**
 * Stopping condition that can be triggered via RMI.
 *
 * @author Gordon Fraser
 */
public class RMIStoppingCondition<T extends Chromosome<T>> implements StoppingCondition<T> {

    private static final long serialVersionUID = 3073266508021896691L;

    private static RMIStoppingCondition<?> instance = null;

    private boolean isStopped = false;

    private RMIStoppingCondition() {
        // empty default constructor
    }

    /**
     * Returns the singleton instance of RMIStoppingCondition.
     *
     * @param <T> the type of chromosome
     * @return the singleton instance
     */
    @SuppressWarnings("unchecked")
    public static <T extends Chromosome<T>> RMIStoppingCondition<T> getInstance() {
        if (instance == null) {
            instance = new RMIStoppingCondition<>();
        }

        // Cast always succeeds because RMIStoppingCondition doesn't actually do anything with a
        // `T` instance.
        return (RMIStoppingCondition<T>) instance;
    }

    /**
     * Always throws an {@code UnsupportedOperationException} when called. Singletons cannot be
     * cloned.
     *
     * @return never returns, always fails
     * @throws UnsupportedOperationException always
     */
    @Override
    public StoppingCondition<T> clone() throws UnsupportedOperationException {
        throw new UnsupportedOperationException("cannot clone singleton");
    }

    /**
     * Stops the search.
     */
    public void stop() {
        isStopped = true;
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
    public void iteration(GeneticAlgorithm<T> algorithm) {
        // TODO Auto-generated method stub

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
    public void fitnessEvaluation(T individual) {
        // TODO Auto-generated method stub

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void modification(T individual) {
        // TODO Auto-generated method stub

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void forceCurrentValue(long value) {
        // TODO Auto-generated method stub

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getCurrentValue() {
        // TODO Auto-generated method stub
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getLimit() {
        // TODO Auto-generated method stub
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isFinished() {
        return isStopped;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reset() {
        isStopped = false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setLimit(long limit) {
        // TODO Auto-generated method stub

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "RMIStoppingCondition";
    }
}
