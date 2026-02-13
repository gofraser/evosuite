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

import org.evosuite.coverage.mutation.Mutation;
import org.evosuite.coverage.mutation.MutationExecutionResult;
import org.evosuite.ga.Chromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testsuite.TestSuiteFitnessFunction;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.HashMap;
import java.util.Map;

public abstract class ExecutableChromosome<E extends ExecutableChromosome<E>> extends Chromosome<E> {

    private static final long serialVersionUID = 1L;

    protected transient ExecutionResult lastExecutionResult = null;

    protected transient Map<Mutation, MutationExecutionResult> lastMutationResult = new HashMap<>();

    /**
     * <p>Constructor for ExecutableChromosome.</p>
     */
    public ExecutableChromosome() {
        super();
    }

    /**
     * <p>Setter for the field <code>lastExecutionResult</code>.</p>
     *
     * @param lastExecutionResult a {@link org.evosuite.testcase.execution.ExecutionResult} object.
     */
    public void setLastExecutionResult(ExecutionResult lastExecutionResult) {
        this.lastExecutionResult = lastExecutionResult;
    }

    /**
     * <p>Getter for the field <code>lastExecutionResult</code>.</p>
     *
     * @return a {@link org.evosuite.testcase.execution.ExecutionResult} object.
     */
    public ExecutionResult getLastExecutionResult() {
        return lastExecutionResult;
    }

    /**
     * <p>Setter for the field <code>lastExecutionResult</code>.</p>
     *
     * @param lastExecutionResult a {@link org.evosuite.coverage.mutation.MutationExecutionResult} object.
     * @param mutation            a {@link org.evosuite.coverage.mutation.Mutation} object.
     */
    public void setLastExecutionResult(MutationExecutionResult lastExecutionResult,
                                       Mutation mutation) {
        this.lastMutationResult.put(mutation, lastExecutionResult);
    }

    /**
     * <p>Getter for the field <code>lastExecutionResult</code>.</p>
     *
     * @param mutation a {@link org.evosuite.coverage.mutation.Mutation} object.
     * @return a {@link org.evosuite.coverage.mutation.MutationExecutionResult} object.
     */
    public MutationExecutionResult getLastExecutionResult(Mutation mutation) {
        return lastMutationResult.get(mutation);
    }


    /**
     * Clears cached execution results.
     */
    public void clearCachedResults() {
        this.lastExecutionResult = null;
        lastMutationResult.clear();
    }

    /**
     * Clears cached mutation results.
     */
    public void clearCachedMutationResults() {
        lastMutationResult.clear();
    }

    /**
     * Copies cached results from another chromosome.
     *
     * @param other the other chromosome to copy from
     */
    protected abstract void copyCachedResults(E other);

    /**
     * Executes the chromosome for a specific fitness function.
     *
     * @param testSuiteFitnessFunction the fitness function to execute for
     * @return the execution result
     */
    public abstract ExecutionResult executeForFitnessFunction(
            TestSuiteFitnessFunction testSuiteFitnessFunction);

    private void readObject(ObjectInputStream ois) throws ClassNotFoundException,
            IOException {
        ois.defaultReadObject();
        lastExecutionResult = null;
        lastMutationResult = new HashMap<>();
    }
}
