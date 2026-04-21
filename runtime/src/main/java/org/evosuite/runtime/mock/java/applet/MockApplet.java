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
package org.evosuite.runtime.mock.java.applet;

import org.evosuite.runtime.GuiSupport;
import org.evosuite.runtime.mock.OverrideMock;

import java.applet.Applet;
import java.awt.HeadlessException;

/**
 * OverrideMock for {@link java.applet.Applet}.  When a SUT class extends
 * {@code Applet}, bytecode instrumentation replaces the superclass with this
 * mock.  The mock temporarily disables the JVM headless flag so that the
 * JDK constructor chain ({@code Applet → Panel → Component}) does not throw
 * {@link HeadlessException}, and it overrides display-related methods as
 * no-ops so that no actual GUI is created.
 *
 * <p>Unlike {@code MockFrame}, which uses a static side-effect method in the
 * {@code super()} argument expression, {@code Applet()} takes no arguments.
 * We solve this with a chained-constructor pattern: the public no-arg
 * constructor delegates to a private constructor via {@code this(dummy)},
 * where the dummy argument expression disables headless mode as a side effect
 * before any super-constructor runs.</p>
 */
@SuppressWarnings("deprecation")
public class MockApplet extends Applet implements OverrideMock {

    private static final long serialVersionUID = -2273935585997344756L;

    // ── side-effect helper (evaluated before this()/super()) ───────

    private static Void disableHeadless() {
        GuiSupport.disableHeadlessForMockConstruction();
        return null;
    }

    // ── constructors ───────────────────────────────────────────────
    // The public no-arg constructor chains to the private Void constructor.
    // disableHeadless() is evaluated as the argument expression of this(),
    // which means headless mode is disabled before Applet() runs.

    public MockApplet() throws HeadlessException {
        this(disableHeadless());
    }

    private MockApplet(Void dummy) throws HeadlessException {
        super();
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    // ── display-related overrides (no-ops) ─────────────────────────

    @Override
    public void setVisible(boolean b) {
        // no-op: prevent actual display
    }

    @SuppressWarnings("deprecation")
    @Override
    public void show() {
        // no-op
    }

    @Override
    public void resize(int width, int height) {
        // no-op
    }

    @Override
    public void resize(java.awt.Dimension d) {
        // no-op
    }
}
