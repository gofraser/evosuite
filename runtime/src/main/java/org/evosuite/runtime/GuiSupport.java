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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.lang.reflect.Field;
import java.nio.file.FileSystems;

/**
 * Class used to handle some particular behaviors of GUI components in the
 * generated JUnit test files.
 *
 * @author arcuri
 */
public class GuiSupport {

    private static final Logger logger = LoggerFactory.getLogger(GuiSupport.class);

    /**
     * Where the tests run in headless mode?.
     */
    private static final boolean isDefaultHeadless = GraphicsEnvironment.isHeadless();
    private static final String defaultHeadlessProperty = java.lang.System.getProperty("java.awt.headless");

    private static final Field headless; // need reflection
    private static final boolean canForceHeadless;

    // Fields for swapping the cached GraphicsEnvironment singleton.
    // In JDK 17+ the singleton lives in GraphicsEnvironment$LocalGE.INSTANCE;
    // in older JDKs it was GraphicsEnvironment.localEnv.
    private static final Field geInstanceField;
    private static final Field headlessWrappedGeField;
    private static final boolean canSwapGe;

    // Saved HeadlessGraphicsEnvironment to restore after mock construction.
    private static GraphicsEnvironment savedHeadlessGe;

    static {
        Field tmpHeadless = null;
        boolean tmpCanForceHeadless = false;
        try {
            //AWT classes check GraphicsEnvironment for headless state
            tmpHeadless = java.awt.GraphicsEnvironment.class.getDeclaredField("headless");
            tmpHeadless.setAccessible(true);
            tmpCanForceHeadless = true;
        } catch (Throwable e) {
            logger.warn("Cannot access java.awt.GraphicsEnvironment#headless reflectively. "
                    + "Falling back to system property only: {}", e.getMessage());
        }
        headless = tmpHeadless;
        canForceHeadless = tmpCanForceHeadless;

        Field tmpGeInstance = null;
        Field tmpWrappedGe = null;
        boolean tmpCanSwap = false;
        try {
            // JDK 17+: GraphicsEnvironment$LocalGE.INSTANCE (static final)
            Class<?> localGeClass = Class.forName("java.awt.GraphicsEnvironment$LocalGE");
            tmpGeInstance = localGeClass.getDeclaredField("INSTANCE");
            tmpGeInstance.setAccessible(true);
        } catch (Throwable e) {
            try {
                // Older JDKs: GraphicsEnvironment.localEnv
                tmpGeInstance = GraphicsEnvironment.class.getDeclaredField("localEnv");
                tmpGeInstance.setAccessible(true);
            } catch (Throwable e2) {
                logger.debug("Cannot access cached GraphicsEnvironment field: {}", e2.getMessage());
            }
        }
        if (tmpGeInstance != null) {
            try {
                tmpWrappedGe = Class.forName("sun.java2d.HeadlessGraphicsEnvironment")
                        .getDeclaredField("ge");
                tmpWrappedGe.setAccessible(true);
                tmpCanSwap = true;
            } catch (Throwable e) {
                logger.debug("Cannot access HeadlessGraphicsEnvironment.ge: {}", e.getMessage());
            }
        }
        geInstanceField = tmpGeInstance;
        headlessWrappedGeField = tmpWrappedGe;
        canSwapGe = tmpCanSwap;
    }

    /**
     * Set the JVM in headless mode.
     */
    public static void setHeadless() {

        if (isDefaultHeadless) {
            //already headless: nothing to do
            return;
        }

        setHeadless(true);
    }

    /**
     * Initializes the GUI support, ensuring fonts are loaded and file system is accessible.
     */
    public static void initialize() {

        /*
            Since trying Java 8, started to get weird behavior on a Linux cluster.
            Issue raises from GUI now trying to write on disk (ie due to Fonts...).
            However, that sometimes strangely fails, even though executed before any
            sandbox. It happens quite often on cluster experiments, but was not able
            to reproduce it to debug :(
            As workaround, we try here to load default file system (it would happen anyway when
            loading fonts in Java 8), but do not crash the test suite (ie throw exception here
            in this method, which is usually called from a @BeforeClass). Reason is that
            maybe not all tests will access GUI.
         */
        try {
            FileSystems.getDefault();
        } catch (Throwable t) {
            logger.error("Failed to load default file system: " + t.getMessage());
            return;
        }

        /*
         * Force the loading of fonts.
         * This is needed because font loading in the JVM can take several seconds (done only once),
         * and that can mess up the JUnit test execution timeouts...
         */
        try {
            (new javax.swing.JButton()).getFontMetrics(new java.awt.Font(null));
        } catch (Throwable t) {
            logger.warn("Failed to eagerly initialize Swing fonts; continuing without GUI pre-initialization: {}",
                    t.getMessage());
        }
    }


    /**
     * Restore the original headless setting of when the JVM was started.
     * This is necessary for when EvoSuite tests (which are in headless mode) are
     * run together with manual tests that are not headless.
     */
    public static void restoreHeadlessMode() {
        if (canForceHeadless) {
            if (GraphicsEnvironment.isHeadless() && !isDefaultHeadless) {
                setHeadless(false);
            }
            return;
        }

        // Reflection is blocked by JPMS; best-effort restoration of system property.
        try {
            if (defaultHeadlessProperty == null) {
                java.lang.System.clearProperty("java.awt.headless");
            } else {
                java.lang.System.setProperty("java.awt.headless", defaultHeadlessProperty);
            }
        } catch (SecurityException e) {
            logger.warn("Could not restore java.awt.headless property: {}", e.getMessage());
        }
    }


    /**
     * Temporarily disable headless mode so that mock Window/Frame/JFrame
     * constructors can call their JDK super-constructors without
     * triggering {@link java.awt.HeadlessException}.
     *
     * <p>Setting the {@code headless} flag alone is not enough: if the cached
     * {@link GraphicsEnvironment} singleton is a {@code HeadlessGraphicsEnvironment},
     * its {@code getDefaultScreenDevice()} throws unconditionally.  We therefore
     * also swap the cached singleton to the unwrapped real environment.
     *
     * <p>Must be paired with {@link #restoreHeadlessAfterMockConstruction()}.
     */
    public static void disableHeadlessForMockConstruction() {
        setHeadless(false);
        if (canSwapGe) {
            try {
                GraphicsEnvironment current = (GraphicsEnvironment) geInstanceField.get(null);
                if (current != null
                        && "sun.java2d.HeadlessGraphicsEnvironment".equals(current.getClass().getName())) {
                    GraphicsEnvironment real = (GraphicsEnvironment) headlessWrappedGeField.get(current);
                    if (real != null) {
                        savedHeadlessGe = current;
                        setStaticField(geInstanceField, real);
                    }
                }
            } catch (Throwable t) {
                logger.debug("Could not swap cached GraphicsEnvironment: {}", t.getMessage());
            }
        }
    }

    /**
     * Re-enable headless mode after a mock constructor has completed.
     *
     * @see #disableHeadlessForMockConstruction()
     */
    public static void restoreHeadlessAfterMockConstruction() {
        if (canSwapGe && savedHeadlessGe != null) {
            try {
                setStaticField(geInstanceField, savedHeadlessGe);
            } catch (Throwable t) {
                logger.debug("Could not restore cached GraphicsEnvironment: {}", t.getMessage());
            } finally {
                savedHeadlessGe = null;
            }
        }
        setHeadless(true);
    }

    private static void setHeadless(boolean isHeadless) {

        //changing system property is not enough
        java.lang.System.setProperty("java.awt.headless", "" + isHeadless);

        if (!canForceHeadless) {
            return;
        }

        try {
            headless.set(null, isHeadless);
        } catch (Exception | Error e) {
            logger.warn("Could not change AWT headless state reflectively: {}", e.getMessage());
        }

    }

    /**
     * Sets a static field value, handling both regular and static-final fields.
     * For static-final fields (like the LocalGE.INSTANCE holder), we use
     * sun.misc.Unsafe since Field.set refuses to modify final fields in Java 12+.
     */
    private static void setStaticField(Field field, Object value) throws Exception {
        int mods = field.getModifiers();
        if (!java.lang.reflect.Modifier.isFinal(mods)) {
            field.set(null, value);
            return;
        }
        // static final: need Unsafe
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            java.lang.reflect.Method staticFieldOffset =
                    unsafeClass.getMethod("staticFieldOffset", Field.class);
            java.lang.reflect.Method staticFieldBase =
                    unsafeClass.getMethod("staticFieldBase", Field.class);
            java.lang.reflect.Method putObject =
                    unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
            Object base = staticFieldBase.invoke(unsafe, field);
            long offset = (long) staticFieldOffset.invoke(unsafe, field);
            putObject.invoke(unsafe, base, offset, value);
        } catch (ClassNotFoundException e) {
            // sun.misc.Unsafe not available — try jdk.internal.misc.Unsafe
            Class<?> unsafeClass = Class.forName("jdk.internal.misc.Unsafe");
            java.lang.reflect.Method getUnsafe = unsafeClass.getMethod("getUnsafe");
            Object unsafe = getUnsafe.invoke(null);
            java.lang.reflect.Method staticFieldOffset =
                    unsafeClass.getMethod("staticFieldOffset", Field.class);
            java.lang.reflect.Method staticFieldBase =
                    unsafeClass.getMethod("staticFieldBase", Field.class);
            java.lang.reflect.Method putReference =
                    unsafeClass.getMethod("putReference", Object.class, long.class, Object.class);
            Object base = staticFieldBase.invoke(unsafe, field);
            long offset = (long) staticFieldOffset.invoke(unsafe, field);
            putReference.invoke(unsafe, base, offset, value);
        }
    }

    static boolean canForceHeadlessForTests() {
        return canForceHeadless;
    }

    static boolean canSwapGeForTests() {
        return canSwapGe;
    }
}
