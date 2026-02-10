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
package org.evosuite.coverage.method;

import org.evosuite.graphs.cfg.BytecodeInstruction;

/**
 * <p>
 * MethodTraceCoverageFactory class.
 * </p>
 *
 * <p>Measures coverage of methods by analysing execution traces,
 * that is, the method can be covered by indirect calls, not
 * necessarily be an statement in a test case.
 *
 * @author Gordon Fraser, Andre Mis, Jose Miguel Rojas
 */
public class MethodTraceCoverageFactory extends AbstractMethodCoverageFactory<MethodTraceCoverageTestFitness> {

    @Override
    protected MethodTraceCoverageTestFitness createGoal(String className, String methodName) {
        return new MethodTraceCoverageTestFitness(className, methodName);
    }

    /**
     * Create a fitness function for branch coverage aimed at covering the root
     * branch of the given method in the given class. Covering a root branch
     * means entering the method.
     *
     * @param className a {@link java.lang.String} object.
     * @param method    a {@link java.lang.String} object.
     * @return a {@link org.evosuite.coverage.branch.BranchCoverageTestFitness}
     *     object.
     */
    public static MethodTraceCoverageTestFitness createMethodTestFitness(
            String className, String method) {

        return new MethodTraceCoverageTestFitness(className,
                method.substring(method.lastIndexOf(".") + 1));
    }

    /**
     * Convenience method calling createMethodTestFitness(class,method) with
     * the respective class and method of the given BytecodeInstruction.
     *
     * @param instruction a {@link org.evosuite.graphs.cfg.BytecodeInstruction} object.
     * @return a {@link org.evosuite.coverage.branch.BranchCoverageTestFitness}
     *     object.
     */
    public static MethodTraceCoverageTestFitness createMethodTestFitness(
            BytecodeInstruction instruction) {
        if (instruction == null) {
            throw new IllegalArgumentException("null given");
        }

        return createMethodTestFitness(instruction.getClassName(),
                instruction.getMethodName());
    }
}
