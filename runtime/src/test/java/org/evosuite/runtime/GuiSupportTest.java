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
package org.evosuite.runtime;

import org.evosuite.runtime.mock.javax.swing.MockJFrame;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.awt.*;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class GuiSupportTest {

    private static final class TestComponent extends JComponent {
        private static final long serialVersionUID = 1L;
    }

    //only one of the 2 tests can be actually executed, as dependent on JVM options

    @Test
    public void testWhenHeadless() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());

        GuiSupport.setHeadless(); //should do nothing
        Assertions.assertTrue(GraphicsEnvironment.isHeadless());

        GuiSupport.restoreHeadlessMode(); //should do nothing
        Assertions.assertTrue(GraphicsEnvironment.isHeadless());
    }

    @Test
    public void testSetHeadlessRepairsNullGraphicsEnvironmentCacheWhenAlreadyHeadless() throws Exception {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        Assumptions.assumeTrue(GuiSupport.canSwapGeForTests());

        Field geFieldHolder = GuiSupport.class.getDeclaredField("geInstanceField");
        geFieldHolder.setAccessible(true);
        Field geField = (Field) geFieldHolder.get(null);
        Assertions.assertNotNull(geField);

        Object original = geField.get(null);
        Method setStaticField = GuiSupport.class.getDeclaredMethod("setStaticField", Field.class, Object.class);
        setStaticField.setAccessible(true);
        try {
            setStaticField.invoke(null, geField, null);

            GuiSupport.setHeadless();

            Assertions.assertDoesNotThrow(GraphicsEnvironment::getLocalGraphicsEnvironment);
            Assertions.assertNotNull(GuiSupport.getDefaultOrStubGraphicsConfiguration());
        } finally {
            setStaticField.invoke(null, geField, original);
        }
    }

    @Test
    public void testWhenNotHeadless() {
        Assumptions.assumeTrue(!GraphicsEnvironment.isHeadless());

        Toolkit toolkitBefore = Toolkit.getDefaultToolkit();
        if (GuiSupport.canSwapToolkitForTests()) {
            Assertions.assertNotEquals("sun.awt.HeadlessToolkit", toolkitBefore.getClass().getName());
        }

        GuiSupport.setHeadless();
        if (GuiSupport.canForceHeadlessForTests()) {
            Assertions.assertTrue(GraphicsEnvironment.isHeadless());
            if (GuiSupport.canSwapToolkitForTests()) {
                Assertions.assertEquals("sun.awt.HeadlessToolkit",
                        Toolkit.getDefaultToolkit().getClass().getName());
            }
        }

        GuiSupport.restoreHeadlessMode(); //should restore headless
        Assertions.assertFalse(GraphicsEnvironment.isHeadless());
        if (GuiSupport.canSwapToolkitForTests()) {
            Assertions.assertNotEquals("sun.awt.HeadlessToolkit",
                    Toolkit.getDefaultToolkit().getClass().getName());
        }
    }

    @Test
    public void testMockConstructionCycleWhenNotHeadless() {
        Assumptions.assumeTrue(!GraphicsEnvironment.isHeadless());
        // On macOS disableHeadlessForMockConstruction() is a deliberate no-op:
        // swapping in the real CGraphicsEnvironment/LWCToolkit aborts the JVM
        // when touched off the main thread.  The flip-and-swap behavior this
        // test exercises therefore does not apply on macOS.
        Assumptions.assumeFalse(GuiSupport.isMacOsForTests());

        // Simulate: set headless, then disable for mock construction, then restore.
        GuiSupport.setHeadless();
        if (GuiSupport.canForceHeadlessForTests()) {
            Assertions.assertTrue(GraphicsEnvironment.isHeadless());
        }

        try {
            GuiSupport.disableHeadlessForMockConstruction();
            Assertions.assertFalse(GraphicsEnvironment.isHeadless());

            // The cached GE should now be the real (non-headless) one, so
            // getDefaultScreenDevice() should not throw HeadlessException.
            if (GuiSupport.canSwapGeForTests()) {
                Assertions.assertDoesNotThrow(() ->
                        GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice());
            }

            GuiSupport.restoreHeadlessAfterMockConstruction();
            if (GuiSupport.canForceHeadlessForTests()) {
                Assertions.assertTrue(GraphicsEnvironment.isHeadless());
            }
        } finally {
            GuiSupport.restoreHeadlessMode();
        }
    }

    @Test
    public void testSwingComponentConstructionDuringMockWindowInHeadlessMode() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());

        GuiSupport.disableHeadlessForMockConstruction();
        try {
            if (GuiSupport.canForceHeadlessForTests() && !GuiSupport.isMacOsForTests()) {
                Assertions.assertFalse(GraphicsEnvironment.isHeadless());
            } else if (GuiSupport.canForceHeadlessForTests()) {
                Assertions.assertTrue(GraphicsEnvironment.isHeadless());
            }
            Assertions.assertDoesNotThrow(() -> {
                new TestComponent();
            });
            Assertions.assertDoesNotThrow(() -> {
                new JPanel();
            });
            Assertions.assertDoesNotThrow(() -> new JLabel("x"));
        } finally {
            GuiSupport.restoreHeadlessAfterMockConstruction();
        }
    }

    @Test
    public void testMockJFrameConstructionInHeadlessMode() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        Assumptions.assumeTrue(GuiSupport.canForceHeadlessForTests());
        Assumptions.assumeFalse(GuiSupport.isMacOsForTests());

        Assertions.assertDoesNotThrow(() -> {
            JFrame frame = new MockJFrame("x");
            Assertions.assertNotNull(frame.getContentPane());
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        });
    }

    /**
     * On macOS, {@link GuiSupport#disableHeadlessForMockConstruction()} is a
     * deliberate no-op because touching the real {@code CGraphicsEnvironment}
     * or {@code LWCToolkit} from EvoSuite's worker thread aborts the JVM
     * through AppKit. As a consequence, mock GUI constructors keep seeing
     * {@code headless=true} and the JDK super-constructor throws
     * {@link HeadlessException} — which the SUT treats as a normal test
     * exception rather than crashing the process. This test pins that
     * intentional behavior so a future refactor cannot regress it silently.
     */
    @Test
    public void testMockJFrameConstructionInHeadlessModeOnMacOsThrowsHeadlessException() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        Assumptions.assumeTrue(GuiSupport.canForceHeadlessForTests());
        Assumptions.assumeTrue(GuiSupport.isMacOsForTests());

        Assertions.assertThrows(HeadlessException.class, () -> new MockJFrame("x"));
    }

    @Test
    public void testForceRestoreClosesLeakedMockConstructionScope() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        Assumptions.assumeTrue(GuiSupport.canForceHeadlessForTests());
        Assumptions.assumeFalse(GuiSupport.isMacOsForTests());

        GuiSupport.disableHeadlessForMockConstruction();
        Assertions.assertFalse(GraphicsEnvironment.isHeadless());

        GuiSupport.forceRestoreHeadlessAfterMockConstructionLeak();
        Assertions.assertTrue(GraphicsEnvironment.isHeadless());
    }

    /**
     * Regression for class-init cascade observed on JDK 17.0.10+ (with the
     * JDK-8316324 null-check backport): when the cached {@code LocalGE.INSTANCE}
     * is null during JUnit recheck, the {@code (new JButton()).getFontMetrics(...)}
     * eager-preload in {@link GuiSupport#initialize()} faulted partway through
     * Swing's UIManager/Font chain, leaving those classes in permanent
     * <init-failed> state and propagating {@code NoClassDefFoundError} to
     * unrelated SUT classes (e.g. XmlBeans via log4j) for the rest of the JVM.
     * The fix gates the JButton preload on
     * {@link GuiSupport#isGraphicsEnvironmentUsable()}; this test pins both
     * sides of that gate: it returns false when {@code LocalGE.INSTANCE} is
     * null, and {@link GuiSupport#initialize()} stays silent in that state
     * rather than propagating the AWT error or triggering Swing init.
     */
    @Test
    public void testInitializeSkipsFontPreloadWhenGraphicsEnvironmentIsNull() throws Exception {
        Assumptions.assumeTrue(GuiSupport.canSwapGeForTests());

        Field geFieldHolder = GuiSupport.class.getDeclaredField("geInstanceField");
        geFieldHolder.setAccessible(true);
        Field geField = (Field) geFieldHolder.get(null);
        Assertions.assertNotNull(geField);

        Method setStaticField = GuiSupport.class.getDeclaredMethod("setStaticField", Field.class, Object.class);
        setStaticField.setAccessible(true);

        Object original = geField.get(null);
        try {
            setStaticField.invoke(null, geField, null);
            // With INSTANCE == null the usability probe must report unusable…
            Assertions.assertFalse(GuiSupport.isGraphicsEnvironmentUsable());
            // …and initialize() must not propagate the resulting AWTError.
            // Idempotent: calling twice is also safe.
            Assertions.assertDoesNotThrow(GuiSupport::initialize);
            Assertions.assertDoesNotThrow(GuiSupport::initialize);
        } finally {
            setStaticField.invoke(null, geField, original);
        }
    }

    @Test
    public void testIsHeadlessTemporarilyDisabledTracksDisableRestorePairs() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        Assumptions.assumeTrue(GuiSupport.canForceHeadlessForTests());
        Assumptions.assumeFalse(GuiSupport.isMacOsForTests());

        Assertions.assertFalse(GuiSupport.isHeadlessTemporarilyDisabledForMockConstruction());
        GuiSupport.disableHeadlessForMockConstruction();
        try {
            Assertions.assertTrue(GuiSupport.isHeadlessTemporarilyDisabledForMockConstruction());
            GuiSupport.disableHeadlessForMockConstruction();
            try {
                Assertions.assertTrue(GuiSupport.isHeadlessTemporarilyDisabledForMockConstruction());
            } finally {
                GuiSupport.restoreHeadlessAfterMockConstruction();
            }
            Assertions.assertTrue(GuiSupport.isHeadlessTemporarilyDisabledForMockConstruction());
        } finally {
            GuiSupport.restoreHeadlessAfterMockConstruction();
        }
        Assertions.assertFalse(GuiSupport.isHeadlessTemporarilyDisabledForMockConstruction());
    }

    @Test
    public void testForceRestoreClosesNestedLeakedMockConstructionScope() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        Assumptions.assumeTrue(GuiSupport.canForceHeadlessForTests());
        Assumptions.assumeFalse(GuiSupport.isMacOsForTests());

        GuiSupport.disableHeadlessForMockConstruction();
        GuiSupport.disableHeadlessForMockConstruction();
        Assertions.assertFalse(GraphicsEnvironment.isHeadless());

        // Simulate one unmatched restore (e.g., constructor aborts before
        // executing all post-super restore calls).
        GuiSupport.restoreHeadlessAfterMockConstruction();
        Assertions.assertFalse(GraphicsEnvironment.isHeadless());

        GuiSupport.forceRestoreHeadlessAfterMockConstructionLeak();
        Assertions.assertTrue(GraphicsEnvironment.isHeadless());
    }
}
