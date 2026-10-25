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
package org.evosuite.coverage.io.output;

import org.evosuite.runtime.Reflection;
import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.io.IOValueDescriptors;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.evosuite.setup.TestClusterUtils;
import org.evosuite.setup.TestUsageChecker;
import org.evosuite.testsuite.AbstractFitnessFactory;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Factory for creating output coverage goals.
 * Generates coverage goals for method return values based on their types.
 *
 * @author Jose Miguel Rojas
 */
public class OutputCoverageFactory extends AbstractFitnessFactory<OutputCoverageTestFitness> {

    private static final Logger logger = LoggerFactory.getLogger(OutputCoverageFactory.class);

    /*
     * (non-Javadoc)
     *
     * @see
     * org.evosuite.coverage.TestCoverageFactory#getCoverageGoals()
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public List<OutputCoverageTestFitness> getCoverageGoals() {
        List<OutputCoverageTestFitness> goals = new ArrayList<>();

        String targetClass = Properties.TARGET_CLASS;

        for (String className : BytecodeInstructionPool.getInstance(TestGenerationContext.getInstance()
                .getClassLoaderForSUT()).knownClasses()) {
            if (!(targetClass.equals("") || className.endsWith(targetClass))) {
                continue;
            }

            for (Method method : Reflection.inStableOrder(TestClusterUtils.getClass(className).getDeclaredMethods())) {
                String methodName = method.getName() + Type.getMethodDescriptor(method);
                if (!TestUsageChecker.canUse(method) || methodName.equals("hashCode()I")) {
                    continue;
                }
                logger.info("Adding goals for method " + className + "." + methodName);
                Type returnType = Type.getReturnType(method);

                for (String descriptor : IOValueDescriptors.staticDescriptors(returnType, method.getReturnType(),
                        false)) {
                    goals.add(createGoal(className, methodName, returnType, descriptor));
                }
            }
        }
        return goals;
    }

    /**
     * Creates an output coverage goal.
     *
     * @param className the class name.
     * @param methodName the method name.
     * @param returnType the return type of the method.
     * @param suffix the suffix for the goal (category).
     * @return the created test fitness object.
     */
    public static OutputCoverageTestFitness createGoal(String className, String methodName,
                                                       Type returnType, String suffix) {
        OutputCoverageGoal goal = new OutputCoverageGoal(className, methodName, returnType, suffix);
        logger.info("Created output coverage goal: {}", goal);
        return new OutputCoverageTestFitness(goal);
    }
}
