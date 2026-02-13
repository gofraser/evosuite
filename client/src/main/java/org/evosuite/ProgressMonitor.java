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
package org.evosuite;

import org.evosuite.ga.Chromosome;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.ga.metaheuristics.SearchListener;
import org.evosuite.ga.stoppingconditions.StoppingCondition;
import org.evosuite.rmi.ClientServices;
import org.evosuite.rmi.service.ClientState;
import org.evosuite.rmi.service.ClientStateInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;

/**
 * Monitor the progress of the search and notify the client node about state changes.
 *
 * @author gordon
 * @param <T> The type of Chromosome being evolved.
 */
public class ProgressMonitor<T extends Chromosome<T>> implements SearchListener<T>, Serializable {

    private static final long serialVersionUID = -8518559681906649686L;
    private static final Logger logger = LoggerFactory.getLogger(ProgressMonitor.class);

    private transient GeneticAlgorithm<T> algorithm;
    private int currentCoverage;

    protected int lastCoverage;
    protected int lastProgress;
    protected int iteration;
    protected ClientState state;

    /**
     * Default constructor.
     */
    public ProgressMonitor() {
        currentCoverage = 0;
        lastCoverage = 0;
        lastProgress = 0;
        iteration = 0;
        state = ClientState.INITIALIZATION;
    }

    /**
     * Copy constructor.
     *
     * @param that the ProgressMonitor to copy from.
     */
    public ProgressMonitor(ProgressMonitor<T> that) {
        this.currentCoverage = that.currentCoverage;
        this.lastCoverage = that.lastCoverage;
        this.lastProgress = that.lastProgress;
        this.iteration = that.iteration;
        this.state = that.state;
    }

    /**
     * Compute the current search progress as a percentage (0-100) by querying
     * the algorithm's stopping conditions directly. This avoids holding a
     * potentially stale/disconnected copy of a stopping condition, which is
     * important when MOSA's {@code TestSuiteAdapter} is in use because
     * {@code getStoppingConditions()} returns fresh snapshots each time.
     */
    private int getProgress(GeneticAlgorithm<T> algorithm) {
        for (StoppingCondition<T> cond : algorithm.getStoppingConditions()) {
            long limit = cond.getLimit();
            if (limit <= 0) {
                continue;
            }
            return (int) (100 * cond.getCurrentValue() / limit);
        }
        return 0;
    }

    /**
     * Update the progress status and notify the client node.
     *
     * @param percent the current progress as a percentage.
     */
    public void updateStatus(int percent) {
        ClientState state = ClientState.SEARCH;
        ClientStateInformation information = new ClientStateInformation(state);
        information.setCoverage(currentCoverage);
        information.setProgress(percent);
        information.setIteration(iteration);
        // LoggingUtils.getEvoLogger().info("Setting to: "+state.getNumPhase()+": "
        // +information.getCoverage()+"/"+information.getProgress());
        ClientServices.getInstance().getClientNode().changeState(state, information);
        lastProgress = percent;
        lastCoverage = currentCoverage;
        //out.writeInt(percent);
        //out.writeInt(currentPhase);
        //out.writeInt(phases);
        //out.writeInt(currentCoverage);
        //out.writeObject(currentTask);
    }

    /* (non-Javadoc)
     * @see org.evosuite.ga.SearchListener#searchStarted(org.evosuite.ga.GeneticAlgorithm)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void searchStarted(GeneticAlgorithm<T> algorithm) {
        this.algorithm = algorithm;
    }

    /* (non-Javadoc)
     * @see org.evosuite.ga.SearchListener#iteration(org.evosuite.ga.GeneticAlgorithm)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void iteration(GeneticAlgorithm<T> algorithm) {
        T best = algorithm.getBestIndividual();
        double rawCoverage = best.getCoverage();
        currentCoverage = (int) Math.floor(rawCoverage * 100);

        if (logger.isDebugEnabled()) {
            logger.debug("iteration {}: algorithm={}, bestIndividual={}, "
                            + "rawCoverage={}, coverageValues={}, currentCoverage={}",
                    iteration, algorithm.getClass().getSimpleName(),
                    best.getClass().getSimpleName(), rawCoverage,
                    best.getCoverageValues(), currentCoverage);
        }

        updateStatus(getProgress(algorithm));
        iteration++;
    }

    /* (non-Javadoc)
     * @see org.evosuite.ga.SearchListener#searchFinished(org.evosuite.ga.GeneticAlgorithm)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void searchFinished(GeneticAlgorithm<T> algorithm) {
        currentCoverage = (int) Math.floor(algorithm.getBestIndividual().getCoverage() * 100);
        if (currentCoverage > lastCoverage) {
            updateStatus(getProgress(algorithm));
        }
    }

    /* (non-Javadoc)
     * @see org.evosuite.ga.SearchListener#fitnessEvaluation(org.evosuite.ga.Chromosome)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void fitnessEvaluation(T individual) {
        if (algorithm == null) {
            return;
        }
        int current = getProgress(algorithm);
        currentCoverage = (int) Math.floor(individual.getCoverage() * 100);
        if (currentCoverage > lastCoverage || current > lastProgress) {
            updateStatus(current);
        }
    }

    /* (non-Javadoc)
     * @see org.evosuite.ga.SearchListener#modification(org.evosuite.ga.Chromosome)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void modification(T individual) {
        // TODO Auto-generated method stub

    }


}
