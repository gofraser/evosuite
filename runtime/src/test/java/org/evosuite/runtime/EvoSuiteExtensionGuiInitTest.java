/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class EvoSuiteExtensionGuiInitTest {

    private final boolean defaultMockGui = RuntimeSettings.mockGUI;

    @AfterEach
    public void cleanup() {
        RuntimeSettings.mockGUI = defaultMockGui;
        java.lang.System.clearProperty(EvoSuiteExtension.EAGER_GUI_INIT_PROPERTY);
    }

    @Test
    public void shouldNotInitializeGuiByDefault() {
        RuntimeSettings.mockGUI = false;
        java.lang.System.clearProperty(EvoSuiteExtension.EAGER_GUI_INIT_PROPERTY);

        Assertions.assertFalse(EvoSuiteExtension.shouldInitializeGui());
    }

    @Test
    public void shouldInitializeGuiWhenMockGuiEnabled() {
        RuntimeSettings.mockGUI = true;
        java.lang.System.clearProperty(EvoSuiteExtension.EAGER_GUI_INIT_PROPERTY);

        Assertions.assertTrue(EvoSuiteExtension.shouldInitializeGui());
    }

    @Test
    public void shouldInitializeGuiWhenPropertyEnabled() {
        RuntimeSettings.mockGUI = false;
        java.lang.System.setProperty(EvoSuiteExtension.EAGER_GUI_INIT_PROPERTY, "true");

        Assertions.assertTrue(EvoSuiteExtension.shouldInitializeGui());
    }
}
