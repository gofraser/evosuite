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

    @Test
    public void cursorStaticFactoriesReturnNullWhenHeadlessAndMockingEnabled() throws Exception {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        Assertions.assertNull(MockHeadlessSwing.replacement_getDefaultCursor());
        Assertions.assertNull(MockHeadlessSwing.replacement_getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        Assertions.assertNull(MockHeadlessSwing.replacement_getSystemCustomCursor("copy"));
        Assertions.assertNull(MockHeadlessSwing.replacement_newCursor(java.awt.Cursor.HAND_CURSOR));
    }
}
