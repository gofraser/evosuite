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

import org.evosuite.runtime.mock.MockFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import javax.swing.JComboBox;
import java.awt.Color;

public class MockHeadlessSwingKeyboardFocusManagerTest {

    @BeforeEach
    public void enableMocking() {
        MockFramework.enable();
    }

    @AfterEach
    public void disableMocking() {
        MockFramework.disable();
    }

    @Test
    public void keyboardFocusManagerStaticCallsAreNeutralizedWhenHeadlessAndMockingEnabled() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        Assertions.assertNull(MockHeadlessSwing.replacement_getCurrentKeyboardFocusManager());
        Assertions.assertDoesNotThrow(() -> MockHeadlessSwing.replacement_setCurrentKeyboardFocusManager(null));
    }

    @Test
    public void comboBoxSelectionIsHeadlessSafeWhenEditorIsMissing() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        JComboBox<String> combo = new JComboBox<>(new String[]{"a", "b"});
        combo.setEditable(true);
        combo.setEditor(null);

        Assertions.assertDoesNotThrow(() -> MockHeadlessSwing.replacement_setSelectedItem(combo, "b"));
        Assertions.assertEquals("b", combo.getSelectedItem());
    }

    @Test
    public void desktopSystemTrayRobotMouseInfoAndColorChooserAreHeadlessSafe() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        Assertions.assertFalse(MockHeadlessSwing.replacement_isDesktopSupported());
        Assertions.assertNull(MockHeadlessSwing.replacement_getDesktop());
        Assertions.assertFalse(MockHeadlessSwing.replacement_isSystemTraySupported());
        Assertions.assertNull(MockHeadlessSwing.replacement_getSystemTray());
        Assertions.assertNull(MockHeadlessSwing.replacement_getPointerInfo());
        Assertions.assertNull(MockHeadlessSwing.replacement_showColorDialog(null, "t", Color.RED));
        Assertions.assertNull(MockHeadlessSwing.replacement_newRobot());
        Assertions.assertNull(MockHeadlessSwing.replacement_newRobot(null));
    }
}
