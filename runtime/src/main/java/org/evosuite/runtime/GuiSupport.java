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
import java.awt.geom.AffineTransform;
import java.awt.image.ColorModel;
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

    // Fields for swapping the cached Toolkit singleton.
    // HeadlessToolkit wraps the real toolkit and throws from getScreenInsets().
    private static final Field toolkitField;
    private static final java.lang.reflect.Method getUnderlyingToolkitMethod;
    private static final boolean canSwapToolkit;

    // Saved HeadlessGraphicsEnvironment and HeadlessToolkit to restore.
    private static GraphicsEnvironment savedHeadlessGe;
    private static Toolkit savedHeadlessToolkit;

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

        // Resolve Toolkit.toolkit field and HeadlessToolkit.getUnderlyingToolkit().
        Field tmpToolkitField = null;
        java.lang.reflect.Method tmpGetUnderlying = null;
        boolean tmpCanSwapToolkit = false;
        try {
            tmpToolkitField = Toolkit.class.getDeclaredField("toolkit");
            tmpToolkitField.setAccessible(true);
            Class<?> headlessToolkitClass = Class.forName("sun.awt.HeadlessToolkit");
            tmpGetUnderlying = headlessToolkitClass.getMethod("getUnderlyingToolkit");
            tmpCanSwapToolkit = true;
        } catch (Throwable e) {
            logger.debug("Cannot access Toolkit swap fields: {}", e.getMessage());
        }
        toolkitField = tmpToolkitField;
        getUnderlyingToolkitMethod = tmpGetUnderlying;
        canSwapToolkit = tmpCanSwapToolkit;
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
                        // On truly headless servers the real GE has no screen
                        // devices, so getDefaultScreenDevice() returns null.
                        // In that case, install a stub GE that delegates to
                        // the real one but provides our stub screen device.
                        GraphicsEnvironment replacement = real;
                        try {
                            if (real.getDefaultScreenDevice() == null) {
                                replacement = new StubGraphicsEnvironment(real);
                            }
                        } catch (Throwable ignore) {
                            // getDefaultScreenDevice() may throw on some platforms
                            replacement = new StubGraphicsEnvironment(real);
                        }
                        setStaticField(geInstanceField, replacement);
                    }
                }
            } catch (Throwable t) {
                logger.debug("Could not swap cached GraphicsEnvironment: {}", t.getMessage());
            }
        } else {
            logger.debug("Cannot swap cached GraphicsEnvironment singleton "
                    + "(missing --add-opens java.desktop/sun.java2d=ALL-UNNAMED?). "
                    + "Mock constructors for Window/Frame/JFrame may throw HeadlessException.");
        }
        if (canSwapToolkit) {
            try {
                Toolkit current = (Toolkit) toolkitField.get(null);
                if (current != null
                        && "sun.awt.HeadlessToolkit".equals(current.getClass().getName())) {
                    Toolkit real = (Toolkit) getUnderlyingToolkitMethod.invoke(current);
                    if (real != null) {
                        savedHeadlessToolkit = current;
                        toolkitField.set(null, real);
                    }
                }
            } catch (Throwable t) {
                logger.debug("Could not swap cached Toolkit: {}", t.getMessage());
            }
        }
    }

    /**
     * Re-enable headless mode after a mock constructor has completed.
     *
     * @see #disableHeadlessForMockConstruction()
     */
    public static void restoreHeadlessAfterMockConstruction() {
        if (canSwapToolkit && savedHeadlessToolkit != null) {
            try {
                toolkitField.set(null, savedHeadlessToolkit);
            } catch (Throwable t) {
                logger.debug("Could not restore cached Toolkit: {}", t.getMessage());
            } finally {
                savedHeadlessToolkit = null;
            }
        }
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

    /**
     * Returns a stub {@link GraphicsConfiguration} for use in mock constructors
     * when no real display is available.  This allows {@code Window.init(gc)}
     * to skip {@code getDefaultScreenDevice()} (which returns null on headless
     * servers) while still satisfying the {@code gc.getDevice().getType()}
     * and {@code gc.getBounds()} calls in the JDK init path.
     */
    public static GraphicsConfiguration getStubGraphicsConfiguration() {
        return StubGraphicsConfiguration.INSTANCE;
    }

    static boolean canForceHeadlessForTests() {
        return canForceHeadless;
    }

    static boolean canSwapGeForTests() {
        return canSwapGe;
    }

    /**
     * A GraphicsEnvironment that delegates to the real (unwrapped) environment
     * but provides a {@link StubGraphicsDevice} when no real screen device is
     * available.  This prevents NPEs from JDK code that calls
     * {@code getDefaultScreenDevice().getDefaultConfiguration()}.
     */
    private static final class StubGraphicsEnvironment extends GraphicsEnvironment {
        private final GraphicsEnvironment delegate;

        StubGraphicsEnvironment(GraphicsEnvironment delegate) {
            this.delegate = delegate;
        }

        @Override
        public GraphicsDevice[] getScreenDevices() {
            try {
                GraphicsDevice[] real = delegate.getScreenDevices();
                if (real != null && real.length > 0) {
                    return real;
                }
            } catch (Throwable ignored) {
            }
            return new GraphicsDevice[]{StubGraphicsDevice.INSTANCE};
        }

        @Override
        public GraphicsDevice getDefaultScreenDevice() {
            try {
                GraphicsDevice real = delegate.getDefaultScreenDevice();
                if (real != null) {
                    return real;
                }
            } catch (Throwable ignored) {
            }
            return StubGraphicsDevice.INSTANCE;
        }

        @Override
        public Graphics2D createGraphics(java.awt.image.BufferedImage img) {
            return delegate.createGraphics(img);
        }

        @Override
        public Font[] getAllFonts() {
            try {
                return delegate.getAllFonts();
            } catch (Throwable ignored) {
                return new Font[0];
            }
        }

        @Override
        public String[] getAvailableFontFamilyNames() {
            try {
                return delegate.getAvailableFontFamilyNames();
            } catch (Throwable ignored) {
                return new String[0];
            }
        }

        @Override
        public String[] getAvailableFontFamilyNames(java.util.Locale l) {
            try {
                return delegate.getAvailableFontFamilyNames(l);
            } catch (Throwable ignored) {
                return new String[0];
            }
        }
    }

    private static final class StubGraphicsDevice extends GraphicsDevice {
        static final StubGraphicsDevice INSTANCE = new StubGraphicsDevice();

        @Override
        public int getType() {
            return TYPE_RASTER_SCREEN;
        }

        @Override
        public String getIDstring() {
            return "EvoSuite-Stub";
        }

        @Override
        public GraphicsConfiguration[] getConfigurations() {
            return new GraphicsConfiguration[]{StubGraphicsConfiguration.INSTANCE};
        }

        @Override
        public GraphicsConfiguration getDefaultConfiguration() {
            return StubGraphicsConfiguration.INSTANCE;
        }
    }

    private static final class StubGraphicsConfiguration extends GraphicsConfiguration {
        static final StubGraphicsConfiguration INSTANCE = new StubGraphicsConfiguration();

        @Override
        public GraphicsDevice getDevice() {
            return StubGraphicsDevice.INSTANCE;
        }

        @Override
        public ColorModel getColorModel() {
            return ColorModel.getRGBdefault();
        }

        @Override
        public ColorModel getColorModel(int transparency) {
            return ColorModel.getRGBdefault();
        }

        @Override
        public AffineTransform getDefaultTransform() {
            return new AffineTransform();
        }

        @Override
        public AffineTransform getNormalizingTransform() {
            return new AffineTransform();
        }

        @Override
        public Rectangle getBounds() {
            return new Rectangle(0, 0, 1024, 768);
        }
    }
}
