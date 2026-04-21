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

import org.evosuite.runtime.GuiSupport;
import org.evosuite.runtime.mock.OverrideMock;

import javax.swing.JWindow;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.HeadlessException;
import java.awt.Window;

/**
 * OverrideMock for {@link javax.swing.JWindow}. This keeps construction
 * possible in EvoSuite's mocked-GUI runs while preventing native peer/window
 * display operations.
 */
public class MockJWindow extends JWindow implements OverrideMock {

    private static final long serialVersionUID = -6548467457949168438L;

    private static Window prepareOwner(Window owner) {
        GuiSupport.disableHeadlessForMockConstruction();
        return owner;
    }

    private static GraphicsConfiguration safeGc(GraphicsConfiguration gc) {
        GuiSupport.disableHeadlessForMockConstruction();
        return gc != null ? gc : GuiSupport.getDefaultOrStubGraphicsConfiguration();
    }

    public MockJWindow() throws HeadlessException {
        super(prepareOwner((Window) null), GuiSupport.getDefaultOrStubGraphicsConfiguration());
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockJWindow(GraphicsConfiguration gc) {
        super(prepareOwner((Window) null), safeGc(gc));
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockJWindow(Frame owner) {
        super(prepareOwner(owner), GuiSupport.getDefaultOrStubGraphicsConfiguration());
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockJWindow(Window owner) {
        super(prepareOwner(owner), GuiSupport.getDefaultOrStubGraphicsConfiguration());
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockJWindow(Window owner, GraphicsConfiguration gc) {
        super(prepareOwner(owner), safeGc(gc));
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    @Override
    public void setVisible(boolean b) {
        // no-op: prevent actual window display
    }

    @SuppressWarnings("deprecation")
    @Override
    public void show() {
        // no-op
    }

    @Override
    public void pack() {
        // no-op: avoid native peer creation
    }

    @Override
    public void toFront() {
        // no-op
    }

    @Override
    public void toBack() {
        // no-op
    }
}

