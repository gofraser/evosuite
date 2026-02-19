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

    public boolean ttEnabled() {
        return Properties.TT;
    }

    public Properties.TransformationScope ttScope() {
        return Properties.TT_SCOPE;
    }

    public String targetClass() {
        return Properties.TARGET_CLASS;
    }

    public String projectPrefix() {
        return Properties.PROJECT_PREFIX;
    }

    public String targetClassPrefix() {
        return Properties.TARGET_CLASS_PREFIX;
    }

    public boolean skipDebug() {
        return Properties.INSTRUMENTATION_SKIP_DEBUG;
    }

    public boolean resetStaticFields() {
        return Properties.RESET_STATIC_FIELDS;
    }

    public boolean resetStaticFinalFields() {
        return Properties.RESET_STATIC_FINAL_FIELDS;
    }

    public boolean pureInspectors() {
        return Properties.PURE_INSPECTORS;
    }

    public long maxLoopIterations() {
        return Properties.MAX_LOOP_ITERATIONS;
    }

    public boolean testCarving() {
        return Properties.TEST_CARVING;
    }

    public boolean makeAccessible() {
        return Properties.MAKE_ACCESSIBLE;
    }

    public boolean exceptionBranches() {
        return Properties.EXCEPTION_BRANCHES;
    }

    public boolean errorBranches() {
        return Properties.ERROR_BRANCHES;
    }

    public String classPrefix() {
        return Properties.CLASS_PREFIX;
    }

    public boolean stringReplacement() {
        return Properties.STRING_REPLACEMENT;
    }

    public boolean applyUidTransformation() {
        return RuntimeSettings.applyUIDTransformation;
    }

    /**
     * Creates an {@link InstrumentationConfig} by reading values from the current {@link Properties}.
     *
     * @return a new {@link InstrumentationConfig} instance.
     */
    public static InstrumentationConfig fromProperties() {
        return new InstrumentationConfig();
    }
}
