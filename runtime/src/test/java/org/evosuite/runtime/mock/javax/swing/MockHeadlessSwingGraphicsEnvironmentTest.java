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
package org.evosuite.runtime.mock.javax.swing;

import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.MockFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;

public class MockHeadlessSwingGraphicsEnvironmentTest {

    private boolean defaultMockGui;

    @BeforeEach
    public void enableMocking() {
        defaultMockGui = RuntimeSettings.mockGUI;
        RuntimeSettings.mockGUI = true;
        MockFramework.enable();
    }

    @AfterEach
    public void disableMocking() {
        MockFramework.disable();
        RuntimeSettings.mockGUI = defaultMockGui;
    }

    /**
     * The headless-safe {@link GraphicsEnvironment} handed out while mocking is
     * enabled in a headless JVM used to hard-code an empty font list, even though
     * font enumeration does not need a display device (the real headless-wrapped
     * environment answers it fine, as this test's assumption below establishes).
     * A SUT branch that inspects getAvailableFontFamilyNames() (e.g. looking for
     * a match, or checking whether any font is installed) could then never see
     * the true font set during the search. It must now delegate instead of
     * fabricating an empty answer.
     */
    @Test
    public void availableFontFamilyNamesAreNotDiscardedWhileMockingHeadless() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        String[] realNames = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        Assumptions.assumeTrue(realNames.length > 0,
                "this JVM reports no fonts at all outside of mocking; nothing to compare against");

        GraphicsEnvironment mocked = MockHeadlessSwing.replacement_getLocalGraphicsEnvironment();
        Assertions.assertNotNull(mocked);
        Assertions.assertEquals(realNames.length, mocked.getAvailableFontFamilyNames().length);
        Assertions.assertTrue(mocked.getAllFonts().length > 0);
    }
}
