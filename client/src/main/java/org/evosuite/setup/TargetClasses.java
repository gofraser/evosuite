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
package org.evosuite.setup;

import java.util.Set;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.junit.CoverageAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The two answers to "is this class under test": {@link #isCut(String)} is the narrow rule
 * (the target class and its nested classes), {@link #isTargetScope(String)} the broad one
 * (everything that is analysed and instrumented as part of the target).
 */
public final class TargetClasses {

    private static final Logger logger = LoggerFactory.getLogger(TargetClasses.class);

    /**
     * All classes found while analysing a target directory or jar; null outside that mode.
     */
    private static Set<String> analyzedTargets = null;

    private TargetClasses() {
    }

    static void setAnalyzedTargets(Set<String> targets) {
        analyzedTargets = targets;
    }

    /**
     * The target class itself or one of its nested classes ({@code Foo$Bar}).
     *
     * @param className the binary class name, with dots
     * @return true if the class is part of the class under test
     */
    public static boolean isCut(String className) {
        return isCut(className, Properties.TARGET_CLASS);
    }

    /**
     * Like {@link #isCut(String)}, for a target class that does not come from {@link Properties}.
     *
     * @param className   the binary class name, with dots
     * @param targetClass the target class name, may be null or empty
     * @return true if the class is the target class or one of its nested classes
     */
    public static boolean isCut(String className, String targetClass) {
        return targetClass != null && !targetClass.isEmpty()
                && (className.equals(targetClass) || className.startsWith(targetClass + "$"));
    }

    /**
     * Like {@link #isCut(String)}, but every class counts while no target class is set.
     *
     * @param className the binary class name, with dots
     * @return true if no target class is set or the class is part of the class under test
     */
    public static boolean isCutOrUnset(String className) {
        return Properties.TARGET_CLASS.isEmpty() || isCut(className);
    }

    /**
     * The target class itself, without its nested classes, or every class while no target class is
     * set. The input and output goals use it: they enumerate every method with its parameter and
     * return value descriptors, so counting nested classes multiplies them for generated code such as
     * protocol buffers, and the search then spends its budget on those goals.
     *
     * @param className the binary class name, with dots
     * @return true if no target class is set or the class is the target class
     */
    public static boolean isTopLevelCutOrUnset(String className) {
        return Properties.TARGET_CLASS.isEmpty() || className.equals(Properties.TARGET_CLASS);
    }

    /**
     * The class under test, its nested classes, the classes below {@code TARGET_CLASS_PREFIX} that
     * are not tests, and the classes of an analysed target directory or jar.
     *
     * @param className the binary class name, with dots
     * @return true if the class is in the scope of the target
     */
    public static boolean isTargetScope(String className) {
        if (!Properties.TARGET_CLASS_PREFIX.isEmpty()
                && className.startsWith(Properties.TARGET_CLASS_PREFIX)) {
            // exclude existing tests from the target project
            try {
                Class<?> clazz = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(className);
                return !CoverageAnalysis.isTest(clazz);
            } catch (ClassNotFoundException e) {
                logger.info("Could not find class " + className);
            }
        }
        if (isCut(className)) {
            return true;
        }
        return analyzedTargets != null && analyzedTargets.contains(className);
    }
}
