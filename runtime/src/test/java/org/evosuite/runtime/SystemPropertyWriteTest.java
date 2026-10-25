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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.PropertyPermission;

/**
 * Writes the SUT may make to system properties with call replacement off: the ones that cannot
 * be restored are refused, and all others are allowed but must be undone after the test.
 */
public class SystemPropertyWriteTest {

    private static final String PROPERTY = "evosuite.property.write.test";

    private boolean mockJVMNonDeterminism;

    @BeforeEach
    public void setUp() {
        mockJVMNonDeterminism = RuntimeSettings.mockJVMNonDeterminism;
        RuntimeSettings.mockJVMNonDeterminism = false;
        org.evosuite.runtime.System.restoreProperties();
    }

    @AfterEach
    public void tearDown() {
        RuntimeSettings.mockJVMNonDeterminism = mockJVMNonDeterminism;
        org.evosuite.runtime.System.restoreProperties();
        java.lang.System.clearProperty(PROPERTY);
    }

    @Test
    public void writesToSystemPropertiesAreRefused() {
        Assertions.assertFalse(org.evosuite.runtime.System.handlePropertyPermission(
                new PropertyPermission("os.name", "write")));
        Assertions.assertFalse(org.evosuite.runtime.System.wasAnyPropertyWritten());
    }

    @Test
    public void otherWritesAreAllowedAndRestored() {
        Assertions.assertTrue(org.evosuite.runtime.System.handlePropertyPermission(
                new PropertyPermission(PROPERTY, "write")));
        java.lang.System.setProperty(PROPERTY, "written by the SUT");
        Assertions.assertTrue(org.evosuite.runtime.System.wasAnyPropertyWritten());

        org.evosuite.runtime.System.restoreProperties();

        Assertions.assertNull(java.lang.System.getProperty(PROPERTY));
    }
}
