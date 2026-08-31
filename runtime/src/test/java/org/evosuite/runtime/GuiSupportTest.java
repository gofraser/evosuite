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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
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

    /**
     * Directly exercises {@code PeerCreationGuardToolkit} (the class {@code
     * disableHeadlessForMockConstruction()} wraps the real toolkit in on
     * Linux/Unix -- see its javadoc) rather than going through the public
     * disable/restore API, because on macOS {@code disableHeadlessForMockConstruction()}
     * is a deliberate no-op ({@link GuiSupport#isMacOsForTests()}) and never
     * installs it. Constructed here purely via reflection, mirroring how
     * {@code GuiSupport} itself builds it, so this test exercises the exact
     * same code path in production regardless of platform.
     *
     * <p>Skips (rather than fails) when {@code sun.awt.ComponentFactory}
     * cannot be resolved on this JVM launch (missing {@code --add-exports
     * java.desktop/sun.awt=ALL-UNNAMED}) -- that is the documented graceful
     * fallback, not a bug; {@code disableHeadlessForMockConstruction()} falls
     * back to the pre-existing unwrapped-real-toolkit behavior in that case.
     */
    @Test
    public void testPeerCreationGuardToolkitThrowsHeadlessExceptionFromPeerCreation() throws Exception {
        Class<?> guardClass;
        try {
            guardClass = Class.forName("org.evosuite.runtime.PeerCreationGuardToolkit");
        } catch (Throwable t) {
            Assumptions.abort("PeerCreationGuardToolkit unavailable on this JDK: " + t);
            return;
        }

        Class<?> componentFactoryClass;
        try {
            componentFactoryClass = Class.forName("sun.awt.ComponentFactory");
        } catch (Throwable t) {
            Assumptions.abort("sun.awt.ComponentFactory unavailable on this JDK: " + t);
            return;
        }

        Toolkit real = Toolkit.getDefaultToolkit();
        Constructor<?> ctor = guardClass.getDeclaredConstructor(Toolkit.class);
        ctor.setAccessible(true);
        Object guard;
        try {
            guard = ctor.newInstance(real);
        } catch (Throwable t) {
            Assumptions.abort("Could not construct PeerCreationGuardToolkit on this JDK: " + t);
            return;
        }

        Assertions.assertTrue(guard instanceof Toolkit);
        Assertions.assertTrue(componentFactoryClass.isInstance(guard),
                "PeerCreationGuardToolkit must implement sun.awt.ComponentFactory so that "
                        + "Component.getComponentFactory() resolves it for both heavyweight "
                        + "(Window/Frame/Dialog) and lightweight (JComponent) peer creation");

        // createFrame(Frame) is one of ComponentFactory's peer-creation methods.
        // None of them are overridden by PeerCreationGuardToolkit, so calling any
        // of them resolves to the interface's own default implementation, which
        // throws HeadlessException -- verified here for createFrame and
        // createWindow as representative samples of the family.
        for (String methodName : new String[]{"createFrame", "createWindow", "createDialog"}) {
            Class<?> targetType = "createFrame".equals(methodName) ? Frame.class
                    : "createWindow".equals(methodName) ? Window.class
                    : java.awt.Dialog.class;
            Method peerMethod = componentFactoryClass.getMethod(methodName, targetType);
            try {
                peerMethod.invoke(guard, new Object[]{null});
                Assertions.fail(methodName + " should have thrown HeadlessException");
            } catch (InvocationTargetException e) {
                Assertions.assertInstanceOf(HeadlessException.class, e.getCause(),
                        methodName + " must throw HeadlessException, not reach the real "
                                + "toolkit's native peer-creation path");
            }
        }

        // The lightweight peer path used by JComponent/Swing must remain safe
        // and must NOT throw -- it resolves to ComponentFactory's lightweight
        // marker peer default, independent of the real toolkit entirely.
        Method createComponent = componentFactoryClass.getMethod("createComponent", java.awt.Component.class);
        Assertions.assertDoesNotThrow(() -> createComponent.invoke(guard, new Object[]{null}));
    }
}
