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

import javax.swing.JFrame;
import java.awt.GraphicsConfiguration;
import java.awt.HeadlessException;

/**
 * OverrideMock for {@link javax.swing.JFrame}.  When a SUT class extends
 * {@code JFrame}, bytecode instrumentation replaces the superclass with this
 * mock.  The mock temporarily disables the JVM headless flag so that the
 * JDK constructor chain does not throw {@link HeadlessException}, and it
 * overrides display-related methods as no-ops so that no actual GUI window
 * is created or shown.
 */
public class MockJFrame extends JFrame implements OverrideMock {

    private static final long serialVersionUID = 4875318892903060591L;

    // ── side-effect helper (evaluated before super()) ───────────────

    private static String prepareTitle(String title) {
        GuiSupport.disableHeadlessForMockConstruction();
        return title;
    }

    // ── constructors (mirror every public JFrame constructor) ───────

    public MockJFrame() throws HeadlessException {
        super(prepareTitle(""));
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockJFrame(String title) throws HeadlessException {
        super(prepareTitle(title));
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockJFrame(GraphicsConfiguration gc) {
        super(prepareTitle(""), gc);
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockJFrame(String title, GraphicsConfiguration gc) {
        super(prepareTitle(title), gc);
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
