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

import org.evosuite.runtime.Runtime;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.java.io.MockFile;
import org.junit.jupiter.api.*;

import javax.swing.*;
import java.lang.reflect.Field;
import java.io.File;

public class MockJFileChooserTest {

    private static final boolean VFS = RuntimeSettings.useVFS;

    @BeforeEach
    public void init() {
        Assumptions.assumeTrue(!isJava24OrNewer(), "JFileChooser test is unstable on Java 24+ in headless CI/JVM contexts");
        RuntimeSettings.useVFS = true;
        Runtime.getInstance().resetRuntime();
    }

    @AfterEach
    public void restoreProperties() {
        RuntimeSettings.useVFS = VFS;
        MockFramework.disable();
    }

    @Test
    public void testGetCurrentDirectory() {

        JFileChooser chooser = new MockJFileChooser();
        File dir = chooser.getCurrentDirectory();

        Assertions.assertTrue(dir.exists());
        Assertions.assertTrue(dir instanceof MockFile);
    }

    @Test
    public void testUiIsAvailableForHeadlessSafeOperations() {
        JFileChooser chooser = new MockJFileChooser();
        Assertions.assertNotNull(chooser.getUI());
        Assertions.assertNotNull(chooser.getUI().getFileView(chooser));
        Assertions.assertDoesNotThrow(chooser::rescanCurrentDirectory);
        Assertions.assertDoesNotThrow(() -> chooser.ensureFileIsVisible(new File("dummy.txt")));
    }

    @Test
    public void testUiRecoversWhenCleared() {
        MockJFileChooser chooser = new MockJFileChooser();
        try {
            Field uiField = JComponent.class.getDeclaredField("ui");
            uiField.setAccessible(true);
            uiField.set(chooser, null);
        } catch (Exception e) {
            Assertions.fail("Could not clear JFileChooser UI reflectively: " + e.getMessage());
        }
        Assertions.assertNotNull(chooser.getUI());
        Assertions.assertNotNull(chooser.getUI().getFileView(chooser));
    }

    private static boolean isJava24OrNewer() {
        String specVersion = java.lang.System.getProperty("java.specification.version", "0");
        try {
            return Integer.parseInt(specVersion) >= 24;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    @Test
    public void testHeadlessMockingShowDialogReturnsCancelWithoutCreatingWindow() {
        Assumptions.assumeTrue(java.awt.GraphicsEnvironment.isHeadless());
        MockFramework.enable();
        MockJFileChooser chooser = new MockJFileChooser();
        int result = chooser.showOpenDialog(null);
        Assertions.assertEquals(JFileChooser.CANCEL_OPTION, result);
    }

    @Test
    public void testHeadlessMockingSetDragEnabledDoesNotThrow() {
        Assumptions.assumeTrue(java.awt.GraphicsEnvironment.isHeadless());
        MockFramework.enable();
        MockJFileChooser chooser = new MockJFileChooser();
        Assertions.assertDoesNotThrow(() -> chooser.setDragEnabled(true));
        Assertions.assertTrue(chooser.getDragEnabled());
    }

}
