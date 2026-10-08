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

import org.evosuite.runtime.instrumentation.MethodCallReplacementCache;
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
        // The calls are left to the JDK, which builds a predefined cursor headless too. Run with
        // -Djava.awt.headless=true to exercise the headless case.
        MethodCallReplacementCache cache = MethodCallReplacementCache.getInstance();
        Assertions.assertFalse(cache.hasReplacementCall("java/awt/Cursor", "getDefaultCursor()Ljava/awt/Cursor;"));
        Assertions.assertFalse(cache.hasReplacementCall("java/awt/Cursor", "getPredefinedCursor(I)Ljava/awt/Cursor;"));
        Assertions.assertFalse(cache.hasReplacementCall("java/awt/Cursor", "<init>(I)V"));
        Assertions.assertEquals(java.awt.Cursor.DEFAULT_CURSOR, java.awt.Cursor.getDefaultCursor().getType());
        Assertions.assertEquals(java.awt.Cursor.HAND_CURSOR,
                java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR).getType());
        Assertions.assertEquals(java.awt.Cursor.HAND_CURSOR, new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR).getType());
    }

    /**
     * Focus bookkeeping needs no display either, and a null manager only defers the failure:
     * freemind's IndependantMapViewCreator threw on addPropertyChangeListener. Same caveat as
     * above - the headless case only runs on a headless JVM.
     */
    @Test
    public void keyboardFocusManagersAreNotMockedAway() {
        MethodCallReplacementCache cache = MethodCallReplacementCache.getInstance();
        Assertions.assertFalse(cache.hasReplacementCall("java/awt/KeyboardFocusManager",
                "getCurrentKeyboardFocusManager()Ljava/awt/KeyboardFocusManager;"));
        Assertions.assertFalse(cache.hasReplacementCall("java/awt/DefaultKeyboardFocusManager", "<init>()V"));
        Assertions.assertNotNull(java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager());
        Assertions.assertNotNull(new java.awt.DefaultKeyboardFocusManager());
    }

    @Test
    public void aSystemCustomCursorIsStillMockedAway() throws Exception {
        // This one really does need the display: the JDK throws HeadlessException.
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        Assertions.assertNull(MockHeadlessSwing.replacement_getSystemCustomCursor("copy"));
    }
}
