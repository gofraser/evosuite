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
package org.evosuite.coverage.io.input;

import org.evosuite.runtime.Reflection;
import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.io.IOValueDescriptors;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.evosuite.setup.TargetClasses;
import org.evosuite.setup.TestClusterUtils;
import org.evosuite.setup.TestUsageChecker;
import org.evosuite.testsuite.AbstractFitnessFactory;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Factory for creating input coverage goals.
 * Generates coverage goals for method parameters based on their types.
 *
 * @author Jose Miguel Rojas
 */
public class InputCoverageFactory extends AbstractFitnessFactory<InputCoverageTestFitness> {

    private static final Logger logger = LoggerFactory.getLogger(InputCoverageFactory.class);

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
    public List<InputCoverageTestFitness> getCoverageGoals() {
        List<InputCoverageTestFitness> goals = new ArrayList<>();

        for (String className : BytecodeInstructionPool.getInstance(TestGenerationContext.getInstance()
                .getClassLoaderForSUT()).knownClasses()) {
            if (!TargetClasses.isTopLevelCutOrUnset(className)) {
                continue;
            }

            Class<?> clazz = TestClusterUtils.getClass(className);
            if (clazz == null) {
                logger.debug("Class {} cannot be loaded, no goals added", className);
                continue;
            }

            for (Method method : Reflection.inStableOrder(clazz.getDeclaredMethods())) {
                String methodName = method.getName() + Type.getMethodDescriptor(method);
                if (!TestUsageChecker.canUse(method)) {
                    continue;
                }
                logger.info("Adding input goals for method " + className + "." + methodName);

                Type[] argumentTypes = Type.getArgumentTypes(method);
                Class<?>[] argumentClasses = method.getParameterTypes();
                for (int i = 0; i < argumentTypes.length; i++) {
                    for (String descriptor : IOValueDescriptors.staticDescriptors(argumentTypes[i],
                            argumentClasses[i], true)) {
                        goals.add(createGoal(className, methodName, i, argumentTypes[i], descriptor));
                    }
                }
            }
        }
        return goals;
    }

    public static InputCoverageTestFitness createGoal(String className, String methodName, int argIndex,
                                                      Type argType, String descriptor) {
        return new InputCoverageTestFitness(new InputCoverageGoal(className, methodName, argIndex, argType,
                descriptor));
    }
}
