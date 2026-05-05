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

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;

public class MockHeadlessSwingAwtCtorTest {

    private static final class PlainContainer extends Container {
        private static final long serialVersionUID = 1L;
    }

    private static final class PlainComponent extends Component {
        private static final long serialVersionUID = 1L;
    }

    @BeforeEach
    public void enableMocking() {
        MockFramework.enable();
    }

    @AfterEach
    public void disableMocking() {
        MockFramework.disable();
    }

    @Test
    public void awtConstructorsHandleHeadlessWhenMockingEnabled() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        Assertions.assertNull(MockHeadlessSwing.replacement_newButton());
        Assertions.assertNull(MockHeadlessSwing.replacement_newButton("x"));
        Assertions.assertNull(MockHeadlessSwing.replacement_newLabel());
        Assertions.assertNull(MockHeadlessSwing.replacement_newLabel("x"));
        Assertions.assertNull(MockHeadlessSwing.replacement_newLabel("x", java.awt.Label.LEFT));
        Assertions.assertNull(MockHeadlessSwing.replacement_newTextField());
        Assertions.assertNull(MockHeadlessSwing.replacement_newTextField("x"));
        Assertions.assertNull(MockHeadlessSwing.replacement_newTextField(3));
        Assertions.assertNull(MockHeadlessSwing.replacement_newTextField("x", 3));
        Assertions.assertNotNull(MockHeadlessSwing.replacement_newJTextField());
        Assertions.assertNotNull(MockHeadlessSwing.replacement_newJTextField("x"));
        Assertions.assertNotNull(MockHeadlessSwing.replacement_newJTextField(3));
        Assertions.assertNotNull(MockHeadlessSwing.replacement_newJTextField("x", 3));
        Assertions.assertNotNull(MockHeadlessSwing.replacement_newJTextField(null, "x", 3));
        Assertions.assertNull(MockHeadlessSwing.replacement_newMenuItem());
        Assertions.assertNull(MockHeadlessSwing.replacement_newMenuItem("x"));
        Assertions.assertNull(MockHeadlessSwing.replacement_newMenuItem("x", null));
        Assertions.assertNull(MockHeadlessSwing.replacement_newMenu());
        Assertions.assertNull(MockHeadlessSwing.replacement_newMenu("x"));
        Assertions.assertNull(MockHeadlessSwing.replacement_newMenu("x", false));
        Assertions.assertNull(MockHeadlessSwing.replacement_newMenuBar());
        Assertions.assertNull(MockHeadlessSwing.replacement_newWindow((java.awt.Frame) null));
        Assertions.assertNull(MockHeadlessSwing.replacement_newWindow((java.awt.Window) null));
        Assertions.assertNull(MockHeadlessSwing.replacement_newWindow((java.awt.Window) null, null));
        Assertions.assertNull(MockHeadlessSwing.replacement_newFrame());
        Assertions.assertNull(MockHeadlessSwing.replacement_newFrame("x"));
        Assertions.assertNull(MockHeadlessSwing.replacement_newDialog((java.awt.Frame) null));
        Assertions.assertNull(MockHeadlessSwing.replacement_newDialog((java.awt.Frame) null, true));
        Assertions.assertNull(MockHeadlessSwing.replacement_newDialog((java.awt.Frame) null, "x"));
        Assertions.assertNull(MockHeadlessSwing.replacement_newDialog((java.awt.Frame) null, "x", true));
        Assertions.assertNull(MockHeadlessSwing.replacement_newPopupMenu());
        Assertions.assertNull(MockHeadlessSwing.replacement_newPopupMenu("x"));
        Assertions.assertNull(MockHeadlessSwing.replacement_newCheckboxMenuItem());
        Assertions.assertNull(MockHeadlessSwing.replacement_newCheckboxMenuItem("x"));
        Assertions.assertNull(MockHeadlessSwing.replacement_newCheckboxMenuItem("x", true));
        Assertions.assertNull(MockHeadlessSwing.replacement_newTextArea());
        Assertions.assertNull(MockHeadlessSwing.replacement_newTextArea("x"));
        Assertions.assertNull(MockHeadlessSwing.replacement_newTextArea(1, 2));
        Assertions.assertNull(MockHeadlessSwing.replacement_newTextArea("x", 1, 2));
        Assertions.assertNull(MockHeadlessSwing.replacement_newTextArea("x", 1, 2, java.awt.TextArea.SCROLLBARS_NONE));
        java.awt.Insets insets = MockHeadlessSwing.replacement_newInsets(1, 2, 3, 4);
        Assertions.assertNotNull(insets);
        Assertions.assertEquals(1, insets.top);
        Assertions.assertEquals(2, insets.left);
        Assertions.assertEquals(3, insets.bottom);
        Assertions.assertEquals(4, insets.right);
        Assertions.assertNull(MockHeadlessSwing.replacement_newDefaultKeyboardFocusManager());
    }

    @Test
    public void containerAddReplacementsNoOpWhenMockingEnabled() {
        PlainContainer container = new PlainContainer();
        PlainComponent component = new PlainComponent();

        Assertions.assertSame(component, MockHeadlessSwing.replacement_containerAdd(container, component));
        Assertions.assertSame(component, MockHeadlessSwing.replacement_containerAdd(container, "name", component));
        Assertions.assertSame(component, MockHeadlessSwing.replacement_containerAdd(container, component, 0));
        Assertions.assertDoesNotThrow(() -> MockHeadlessSwing.replacement_containerAdd(container, component, "north"));
        Assertions.assertDoesNotThrow(
                () -> MockHeadlessSwing.replacement_containerAdd(container, component, "north", 0));
        Assertions.assertEquals(0, container.getComponentCount());
    }

}
