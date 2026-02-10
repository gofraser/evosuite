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

import org.evosuite.Properties;
import org.evosuite.coverage.MethodNameMatcher;
import org.evosuite.setup.TestUsageChecker;
import org.evosuite.testsuite.AbstractFitnessFactory;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Abstract factory for method coverage goals.
 *
 * @param <T> the type of fitness function
 */
public abstract class AbstractMethodCoverageFactory<T extends AbstractMethodTestFitness>
        extends AbstractFitnessFactory<T> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractMethodCoverageFactory.class);
    protected final MethodNameMatcher matcher = new MethodNameMatcher();

    @Override
    public List<T> getCoverageGoals() {
        List<T> goals = new ArrayList<>();

        long start = System.currentTimeMillis();

        String className = Properties.TARGET_CLASS;
        Class<?> clazz = Properties.getTargetClassAndDontInitialise();
        if (clazz != null) {
            goals.addAll(getCoverageGoals(clazz, className));
            Class<?>[] innerClasses = clazz.getDeclaredClasses();
            for (Class<?> innerClass : innerClasses) {
                String innerClassName = innerClass.getCanonicalName();
                goals.addAll(getCoverageGoals(innerClass, innerClassName));
            }
        }
        goalComputationTime = System.currentTimeMillis() - start;
        return goals;
    }

    protected List<T> getCoverageGoals(Class<?> clazz, String className) {
        List<T> goals = new ArrayList<>();
        Constructor<?>[] allConstructors = clazz.getDeclaredConstructors();
        for (Constructor<?> c : allConstructors) {
            if (TestUsageChecker.canUse(c)) {
                String methodName = "<init>" + Type.getConstructorDescriptor(c);
                logger.info("Adding goal for constructor " + className + "." + methodName);
                goals.add(createGoal(className, methodName));
            }
        }
        Method[] allMethods = clazz.getDeclaredMethods();
        for (Method m : allMethods) {
            if (TestUsageChecker.canUse(m)) {
                if (clazz.isEnum()) {
                    if (m.getName().equals("valueOf") || m.getName().equals("values")
                            || m.getName().equals("ordinal")) {
                        logger.debug("Excluding valueOf for Enum " + m);
                        continue;
                    }
                }
                if (clazz.isInterface() && Modifier.isAbstract(m.getModifiers())) {
                    // Don't count interface declarations as targets
                    continue;
                }
                String methodName = m.getName() + Type.getMethodDescriptor(m);
                if (!matcher.methodMatches(methodName)) {
                    logger.info("Method {} does not match criteria. ", methodName);
                    continue;
                }
                logger.info("Adding goal for method " + className + "." + methodName);
                goals.add(createGoal(className, methodName));
            }
        }
        return goals;
    }

    protected abstract T createGoal(String className, String methodName);
}
