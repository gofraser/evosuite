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
package org.evosuite.runtime.mock.java.awt;

import org.evosuite.runtime.GuiSupport;
import org.evosuite.runtime.mock.OverrideMock;

import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.HeadlessException;
import java.awt.Window;

/**
 * OverrideMock for {@link java.awt.Window}.  When a SUT class directly extends
 * {@code Window}, bytecode instrumentation replaces the superclass with this
 * mock.  The mock temporarily disables the JVM headless flag so that the
 * JDK constructor chain does not throw {@link HeadlessException}, and it
 * overrides display-related methods as no-ops so that no actual GUI window
 * is created or shown.
 */
public class MockWindow extends Window implements OverrideMock {

    private static final long serialVersionUID = -5382554780896178636L;

    // ── side-effect helpers (evaluated before super()) ──────────────

    private static Window prepareWindow(Window owner) {
        GuiSupport.disableHeadlessForMockConstruction();
        return owner;
    }

    // ── constructors (mirror every public Window constructor) ───────
    // All constructors route through super(Window, GraphicsConfiguration)
    // with a non-null GC so that Window.initGC() never calls
    // getDefaultScreenDevice() (which returns null on headless servers).

    public MockWindow(Frame owner) {
        super(prepareWindow(owner), GuiSupport.getDefaultOrStubGraphicsConfiguration());
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockWindow(Window owner) {
        super(prepareWindow(owner), GuiSupport.getDefaultOrStubGraphicsConfiguration());
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockWindow(Window owner, GraphicsConfiguration gc) {
        super(prepareWindow(owner), gc != null ? gc : GuiSupport.getDefaultOrStubGraphicsConfiguration());
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    // ── display-related overrides (no-ops) ──────────────────────────

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
