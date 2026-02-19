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

package org.evosuite.instrumentation;

import org.evosuite.Properties;
import org.evosuite.runtime.RuntimeSettings;

/**
 * Configuration for the bytecode instrumentation process.
 * Encapsulates all flags and settings previously read directly from {@link Properties}.
 */
public class InstrumentationConfig {

    public final boolean ttEnabled;
    public final Properties.TransformationScope ttScope;
    public final String targetClass;
    public final String projectPrefix;
    public final String targetClassPrefix;
    public final boolean skipDebug;
    public final boolean resetStaticFields;
    public final boolean resetStaticFinalFields;
    public final boolean pureInspectors;
    public final long maxLoopIterations;
    public final boolean testCarving;
    public final boolean makeAccessible;
    public final boolean exceptionBranches;
    public final boolean errorBranches;
    public final String classPrefix;
    public final boolean stringReplacement;
    public final boolean applyUidTransformation;

    /**
     * Constructor for InstrumentationConfig.
     */
    public InstrumentationConfig(boolean ttEnabled, Properties.TransformationScope ttScope, String targetClass,
                                 String projectPrefix, String targetClassPrefix, boolean skipDebug,
                                 boolean resetStaticFields, boolean resetStaticFinalFields,
                                 boolean pureInspectors, long maxLoopIterations, boolean testCarving,
                                 boolean makeAccessible, boolean exceptionBranches, boolean errorBranches,
                                 String classPrefix, boolean stringReplacement, boolean applyUidTransformation) {
        this.ttEnabled = ttEnabled;
        this.ttScope = ttScope;
        this.targetClass = targetClass;
        this.projectPrefix = projectPrefix;
        this.targetClassPrefix = targetClassPrefix;
        this.skipDebug = skipDebug;
        this.resetStaticFields = resetStaticFields;
        this.resetStaticFinalFields = resetStaticFinalFields;
        this.pureInspectors = pureInspectors;
        this.maxLoopIterations = maxLoopIterations;
        this.testCarving = testCarving;
        this.makeAccessible = makeAccessible;
        this.exceptionBranches = exceptionBranches;
        this.errorBranches = errorBranches;
        this.classPrefix = classPrefix;
        this.stringReplacement = stringReplacement;
        this.applyUidTransformation = applyUidTransformation;
    }

    /**
     * Creates an {@link InstrumentationConfig} by reading values from the current {@link Properties}.
     *
     * @return a new {@link InstrumentationConfig} instance.
     */
    public static InstrumentationConfig fromProperties() {
        return new InstrumentationConfig(
                Properties.TT,
                Properties.TT_SCOPE,
                Properties.TARGET_CLASS,
                Properties.PROJECT_PREFIX,
                Properties.TARGET_CLASS_PREFIX,
                Properties.INSTRUMENTATION_SKIP_DEBUG,
                Properties.RESET_STATIC_FIELDS,
                Properties.RESET_STATIC_FINAL_FIELDS,
                Properties.PURE_INSPECTORS,
                Properties.MAX_LOOP_ITERATIONS,
                Properties.TEST_CARVING,
                Properties.MAKE_ACCESSIBLE,
                Properties.EXCEPTION_BRANCHES,
                Properties.ERROR_BRANCHES,
                Properties.CLASS_PREFIX,
                Properties.STRING_REPLACEMENT,
                RuntimeSettings.applyUIDTransformation
        );
    }
}
