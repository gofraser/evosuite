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
package org.evosuite.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every runner takes the resource limits of a generated test class from its annotation.
 */
public class RuntimeSettingsApplyTest {

    @EvoRunnerParameters(maxNumberOfThreads = 7, maxNumberOfIterationsPerLoop = 123)
    private static class Limited_ESTest {
    }

    private int threads;
    private long iterations;

    @BeforeEach
    public void remember() {
        threads = RuntimeSettings.maxNumberOfThreads;
        iterations = RuntimeSettings.maxNumberOfIterationsPerLoop;
    }

    @AfterEach
    public void restore() {
        RuntimeSettings.maxNumberOfThreads = threads;
        RuntimeSettings.maxNumberOfIterationsPerLoop = iterations;
    }

    @Test
    public void applyTakesOverResourceLimits() {
        RuntimeSettings.apply(Limited_ESTest.class.getAnnotation(EvoRunnerParameters.class));

        assertEquals(7, RuntimeSettings.maxNumberOfThreads);
        assertEquals(123L, RuntimeSettings.maxNumberOfIterationsPerLoop);
    }
}
