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
import org.evosuite.runtime.util.JOptionPaneInputs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;

public class MockJOptionPaneHeadlessTest {

    @BeforeEach
    public void setUp() {
        MockFramework.enable();
        JOptionPaneInputs.resetSingleton();
    }

    @AfterEach
    public void tearDown() {
        MockFramework.disable();
        JOptionPaneInputs.resetSingleton();
    }

    @Test
    public void headlessConfirmDialogReturnsClosedOption() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        JOptionPaneInputs.enqueueYesNoSelection(JOptionPane.YES_OPTION);
        int result = MockJOptionPane.showConfirmDialog(null, "msg");
        Assertions.assertEquals(JOptionPane.CLOSED_OPTION, result);
    }

    @Test
    public void headlessInputDialogReturnsNull() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        JOptionPaneInputs.enqueueInputString("queued");
        Assertions.assertNull(MockJOptionPane.showInputDialog("msg"));
    }

    @Test
    public void headlessOptionDialogReturnsClosedOrNull() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        JOptionPaneInputs.enqueueOptionSelection(0);
        int result = MockJOptionPane.showOptionDialog(null, "m", "t",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                new Object[]{"a", "b"}, "a");
        Assertions.assertEquals(JOptionPane.CLOSED_OPTION, result);

        Object selected = MockJOptionPane.showInputDialog(null, "m", "t",
                JOptionPane.QUESTION_MESSAGE, null, new Object[]{"x", "y"}, "x");
        Assertions.assertNull(selected);
    }
}
