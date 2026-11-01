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

import org.evosuite.Properties;
import org.evosuite.classpath.ClassPathHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With a target class prefix, deciding whether a class is a target must not run its static
 * initialiser: that would execute SUT code outside the instrumenting class loader.
 */
public class TargetClassesScopeTest {

    static boolean probeInitialised;

    static class Probe {
        static {
            probeInitialised = true;
        }
    }

    private final String defaultPrefix = Properties.TARGET_CLASS_PREFIX;

    @BeforeEach
    public void setUp() {
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
        probeInitialised = false;
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS_PREFIX = defaultPrefix;
        ClassPathHandler.resetSingleton();
    }

    @Test
    public void prefixModeDoesNotInitialiseTheClass() {
        String name = Probe.class.getName();
        Properties.TARGET_CLASS_PREFIX = name;

        assertTrue(TargetClasses.isTargetScope(name));
        assertFalse(probeInitialised);
    }
}
