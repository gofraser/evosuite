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
package org.evosuite.runtime.classhandling;

import org.evosuite.runtime.RuntimeSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ClassStateSupportTest {

    public static class PlainLoadedClass {
    }

    private final boolean defaultMockJvm = RuntimeSettings.mockJVMNonDeterminism;
    private final boolean defaultResetStaticState = RuntimeSettings.resetStaticState;

    @AfterEach
    void restoreRuntimeSettings() {
        RuntimeSettings.mockJVMNonDeterminism = defaultMockJvm;
        RuntimeSettings.resetStaticState = defaultResetStaticState;
        ClassStateSupport.clearNonInstrumentedClassDetectionFlag();
    }

    @Test
    void initializeClassesMarksNonInstrumentedClassesWhenResetStaticStateIsEnabled() {
        RuntimeSettings.mockJVMNonDeterminism = false;
        RuntimeSettings.resetStaticState = true;
        ClassStateSupport.clearNonInstrumentedClassDetectionFlag();

        boolean problem = ClassStateSupport.initializeClasses(
                getClass().getClassLoader(),
                PlainLoadedClass.class.getName());

        Assertions.assertTrue(problem);
        Assertions.assertTrue(ClassStateSupport.hadNonInstrumentedClassDetection());
    }

    @Test
    void initializeClassesDoesNotMarkNonInstrumentedClassesWhenInstrumentationDependentFeaturesAreOff() {
        RuntimeSettings.mockJVMNonDeterminism = false;
        RuntimeSettings.resetStaticState = false;
        ClassStateSupport.clearNonInstrumentedClassDetectionFlag();

        boolean problem = ClassStateSupport.initializeClasses(
                getClass().getClassLoader(),
                PlainLoadedClass.class.getName());

        Assertions.assertFalse(problem);
        Assertions.assertFalse(ClassStateSupport.hadNonInstrumentedClassDetection());
    }
}
