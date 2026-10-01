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

public class MockHeadlessSwingCursorTest {

    @BeforeEach
    public void enableMocking() {
        MockFramework.enable();
    }

    @AfterEach
    public void disableMocking() {
        MockFramework.disable();
    }

    /**
     * Only a system custom cursor needs the display. Handing back null for the others made any SUT
     * that uses the cursor it just asked for fail: squirrel-sql's CursorChanger rejects a null
     * cursor outright, so 12 action classes never reached actionPerformed on any seed of the SF110
     * replication.
     */
    @Test
    public void cursorFactoriesThatWorkHeadlessAreNotMockedAway() {
        // Left unguarded on purpose: the contract is now "always a real cursor", which holds in
        // both modes. Note this only FAILS on a headless JVM, because that is the only place the
        // old code returned null - on a machine with a display it passes either way. Run it with
        // -Djava.awt.headless=true to exercise the regression.
        Assertions.assertEquals(java.awt.Cursor.DEFAULT_CURSOR,
                MockHeadlessSwing.replacement_getDefaultCursor().getType());
        Assertions.assertEquals(java.awt.Cursor.HAND_CURSOR,
                MockHeadlessSwing.replacement_getPredefinedCursor(java.awt.Cursor.HAND_CURSOR).getType());
        Assertions.assertEquals(java.awt.Cursor.HAND_CURSOR,
                MockHeadlessSwing.replacement_newCursor(java.awt.Cursor.HAND_CURSOR).getType());
    }

    /**
     * Focus bookkeeping needs no display either, and a null manager only defers the failure:
     * freemind's IndependantMapViewCreator threw on addPropertyChangeListener. Same caveat as
     * above - this only fails on a headless JVM.
     */
    @Test
    public void keyboardFocusManagersAreNotMockedAway() {
        Assertions.assertNotNull(MockHeadlessSwing.replacement_getCurrentKeyboardFocusManager());
        Assertions.assertNotNull(MockHeadlessSwing.replacement_newDefaultKeyboardFocusManager());
    }

    @Test
    public void aSystemCustomCursorIsStillMockedAway() throws Exception {
        // This one really does need the display: the JDK throws HeadlessException.
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        Assertions.assertNull(MockHeadlessSwing.replacement_getSystemCustomCursor("copy"));
    }
}
