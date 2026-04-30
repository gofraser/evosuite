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
import java.util.concurrent.atomic.AtomicBoolean;

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

    // Reference counter for nested disableHeadlessForMockConstruction() calls.
    // Only the outermost disable actually changes state; only the outermost
    // restore reverts it.  This allows mock constructors (e.g. MockApplet) to
    // pair disable/restore internally while an outer caller (e.g.
    // ConstructorStatement) keeps headless disabled for the entire SUT
    // constructor — including its body, which may create other AWT components.
    private static int mockConstructionNestingDepth = 0;

    // One-shot guard for preloadHeadlessGuardedAwtClasses().
    private static final AtomicBoolean awtClassesPreloaded = new AtomicBoolean(false);

    // On macOS the real GraphicsEnvironment (CGraphicsEnvironment) and Toolkit
    // (LWCToolkit) call into AppKit, which aborts the JVM (SIGABRT) when touched
    // off the main thread.  The EvoSuite test-execution thread is never the
    // main thread, so swapping in the real GE/Toolkit and letting a mock
    // Window/Frame super-constructor run its native path crashes the client.
    // On macOS we therefore treat disableHeadlessForMockConstruction() as a
    // no-op -- mock constructors will throw HeadlessException, which is caught
    // as a regular test exception rather than killing the process.
    private static final boolean IS_MACOS =
            java.lang.System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");

    // AWT/Swing classes whose <clinit> calls a native initIDs() (or similar) only when
    // GraphicsEnvironment.isHeadless() returns false.  If we let any of these
    // load for the first time *after* setHeadless(false), the native call
    // happens and may fail with UnsatisfiedLinkError when the JVM was started
    // headless and libawt has no display-side bindings — leaving the class in
    // failed-init state forever.  We force their <clinit> to run while headless
    // is still true so the decision is permanently "skip initIDs()".
    private static final String[] HEADLESS_GUARDED_AWT_CLASSES = {
            // Linux/X11 toolkit internals: if first touched after headless is
            // temporarily disabled, GTK/X11 probing may invoke native entry points
            // such as UNIXToolkit.check_gtk and fail with UnsatisfiedLinkError
            // on CI images without matching GUI libraries.
            "sun.awt.UNIXToolkit",
            "sun.awt.X11.XToolkit",
            "sun.awt.X11GraphicsEnvironment",
            "java.awt.Insets",
            "java.awt.Rectangle",
            "java.awt.Cursor",
            "java.awt.MenuComponent",
            "java.awt.Menu",
            "java.awt.MenuBar",
            "java.awt.Button",
            "java.awt.Window",
            "java.awt.Frame",
            "java.awt.Dialog",
            "java.awt.TextField",
            "java.awt.MenuItem",
            "java.awt.TextArea",
            "java.awt.KeyboardFocusManager",
            "javax.swing.RepaintManager",
            "java.awt.AWTEvent",
            "java.awt.event.MouseEvent",
            "java.awt.event.KeyEvent",
            "java.awt.event.WindowEvent",
            // AWT/Swing color and UI defaults: these may trigger toolkit/UI manager
            // initialization paths that are sensitive to first-touch headless state.
            "java.awt.SystemColor",
            "java.awt.Color",
            "javax.swing.UIManager",
            "javax.swing.UIDefaults",
            "javax.swing.LookAndFeel",
            "javax.swing.plaf.basic.BasicLookAndFeel",
    };

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
            // Already headless, but we may still need to repair the cached
            // GraphicsEnvironment/Toolkit singletons before GUI-heavy static
            // initializers run.
            ensureGraphicsEnvironmentAvailable();
            preloadHeadlessGuardedAwtClasses();
            syncToolkitWithHeadlessState(true);
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

        // Inoculate headless-guarded AWT classes before we touch JButton/Font.
        // The eager font preload below transitively loads several AWT classes
        // whose static initializers branch on isHeadless(); if scaffolding has
        // not yet called setHeadless() they would otherwise trigger native
        // initIDs() calls that fail on JVMs started headless.
        ensureGraphicsEnvironmentAvailable();
        preloadHeadlessGuardedAwtClasses();

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
     * Force the {@code <clinit>} of headless-guarded AWT classes (e.g.
     * {@link java.awt.Insets}) to run <em>now</em>, while the JVM is still in
     * headless mode.  The static initializers of these classes follow the
     * pattern
     * <pre>{@code
     * static {
     *     Toolkit.loadLibraries();
     *     if (!GraphicsEnvironment.isHeadless()) {
     *         initIDs();   // native — may be unbound on a JVM started headless
     *     }
     * }
     * }</pre>
     * The {@code isHeadless()} branch is only evaluated once, so loading the
     * class while headless is true permanently freezes the decision as "skip
     * the native call".  Any subsequent {@link #disableHeadlessForMockConstruction()}
     * window is then safe to enter without poisoning the class to
     * {@code <initializer-failed>} state.
     *
     * <p>Idempotent — runs at most once per JVM.  Failures are logged and
     * swallowed so that an unloadable class does not break unrelated tests.
     */
    public static synchronized void preloadHeadlessGuardedAwtClasses() {
        if (awtClassesPreloaded.get()) {
            return;
        }
        if (!isHeadlessSafely()) {
            // Not in headless mode right now — preloading would not skip the
            // native call, so it offers no protection.  Leave the flag false so
            // a later call has another chance once scaffolding forces headless.
            return;
        }
        ClassLoader loader = GuiSupport.class.getClassLoader();
        for (String name : HEADLESS_GUARDED_AWT_CLASSES) {
            try {
                Class.forName(name, true, loader);
            } catch (Throwable t) {
                logger.debug("Could not preload {}: {}", name, t.toString());
            }
        }
        awtClassesPreloaded.set(true);
    }


    /**
     * Restore the original headless setting of when the JVM was started.
     * This is necessary for when EvoSuite tests (which are in headless mode) are
     * run together with manual tests that are not headless.
     */
    public static void restoreHeadlessMode() {
        if (canForceHeadless) {
            if (isHeadlessSafely() && !isDefaultHeadless) {
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
        mockConstructionNestingDepth++;
        if (mockConstructionNestingDepth > 1) {
            // Already disabled by an outer caller — nothing to do.
            return;
        }
        if (IS_MACOS) {
            // On macOS, unwrapping the real CGraphicsEnvironment/LWCToolkit can
            // call into AppKit from the test thread and abort the JVM. Instead
            // use a stub-only path: temporarily flip out of headless mode and
            // replace the cached GraphicsEnvironment with EvoSuite's stub, while
            // leaving the cached HeadlessToolkit in place.
            ensureGraphicsEnvironmentAvailable();
            preloadHeadlessGuardedAwtClasses();
            setHeadless(false);
            if (canSwapGe) {
                try {
                    GraphicsEnvironment current = (GraphicsEnvironment) geInstanceField.get(null);
                    if (current != null) {
                        savedHeadlessGe = current;
                        setStaticField(geInstanceField, new StubGraphicsEnvironment(null));
                    }
                } catch (Throwable t) {
                    logger.debug("Could not install stub GraphicsEnvironment on macOS: {}", t.getMessage());
                }
            }
            return;
        }
        // Force the <clinit> of Insets/Rectangle/Cursor/... to run while
        // headless is still true.  Otherwise the upcoming setHeadless(false)
        // would expose them to a native initIDs() call that may be unbound
        // on a JVM started with -Djava.awt.headless=true, leaving the class
        // in failed-init state for the rest of the run.
        ensureGraphicsEnvironmentAvailable();
        preloadHeadlessGuardedAwtClasses();
        initializeHeadlessToolkitIfNeeded();
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
     * Forces {@link Toolkit#getDefaultToolkit()} to populate the cached toolkit
     * while the JVM is still headless, so a later temporary flip can unwrap the
     * underlying toolkit instead of initializing a real one from scratch.
     */
    private static void initializeHeadlessToolkitIfNeeded() {
        if (!canSwapToolkit || toolkitField == null) {
            return;
        }
        try {
            if (toolkitField.get(null) == null) {
                Toolkit.getDefaultToolkit();
            }
        } catch (Throwable t) {
            logger.debug("Could not initialize headless Toolkit cache: {}", t.getMessage());
        }
    }

    /**
     * Re-enable headless mode after a mock constructor has completed.
     *
     * @see #disableHeadlessForMockConstruction()
     */
    public static void restoreHeadlessAfterMockConstruction() {
        if (mockConstructionNestingDepth > 0) {
            mockConstructionNestingDepth--;
        }
        if (mockConstructionNestingDepth > 0) {
            // Still inside an outer disable — don't actually restore yet.
            return;
        }
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

        if (isHeadless) {
            ensureGraphicsEnvironmentAvailable();
            preloadHeadlessGuardedAwtClasses();
        }
        syncToolkitWithHeadlessState(isHeadless);

    }

    private static void syncToolkitWithHeadlessState(boolean isHeadless) {
        if (!canSwapToolkit || toolkitField == null) {
            return;
        }
        try {
            Toolkit current = (Toolkit) toolkitField.get(null);
            if (isHeadless) {
                if (current != null && !isHeadlessToolkit(current)) {
                    // Force a later Toolkit.getDefaultToolkit() call to rebuild a
                    // HeadlessToolkit instead of reusing a cached real toolkit.
                    toolkitField.set(null, null);
                }
                return;
            }

            if (isHeadlessToolkit(current)) {
                Toolkit real = null;
                try {
                    real = (Toolkit) getUnderlyingToolkitMethod.invoke(current);
                } catch (Throwable t) {
                    logger.debug("Could not unwrap cached HeadlessToolkit: {}", t.getMessage());
                }
                toolkitField.set(null, real);
            }
        } catch (Throwable t) {
            logger.debug("Could not synchronize cached Toolkit with headless state: {}", t.getMessage());
        }
    }

    private static boolean isHeadlessToolkit(Toolkit toolkit) {
        return toolkit != null
                && "sun.awt.HeadlessToolkit".equals(toolkit.getClass().getName());
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

    /**
     * Returns the current default screen {@link GraphicsConfiguration} when available,
     * otherwise falls back to EvoSuite's stub configuration.
     *
     * <p>This is used by GUI OverrideMocks to reduce platform-specific failures
     * (for example, casts to platform-internal graphics config types on macOS)
     * while still remaining safe on headless servers.
     */
    public static GraphicsConfiguration getDefaultOrStubGraphicsConfiguration() {
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            if (ge != null) {
                GraphicsDevice dev = ge.getDefaultScreenDevice();
                if (dev != null) {
                    GraphicsConfiguration gc = dev.getDefaultConfiguration();
                    if (gc != null) {
                        return gc;
                    }
                }
            }
        } catch (Throwable ignored) {
            // fall back to stub below
            ensureGraphicsEnvironmentAvailable();
            try {
                GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
                if (ge != null) {
                    GraphicsDevice dev = ge.getDefaultScreenDevice();
                    if (dev != null) {
                        GraphicsConfiguration gc = dev.getDefaultConfiguration();
                        if (gc != null) {
                            return gc;
                        }
                    }
                }
            } catch (Throwable ignoredAgain) {
                // use stub below
            }
        }
        return StubGraphicsConfiguration.INSTANCE;
    }

    private static boolean isHeadlessSafely() {
        try {
            return GraphicsEnvironment.isHeadless();
        } catch (Throwable ignored) {
            // If GE lookup is broken, assume headless-safe behavior.
            return true;
        }
    }

    /**
     * Ensures the cached GraphicsEnvironment singleton is non-null.
     * Some CI/JDK combinations can leave it null, which triggers:
     * "Local GraphicsEnvironment must not be null".
     */
    private static void ensureGraphicsEnvironmentAvailable() {
        if (!canSwapGe || geInstanceField == null) {
            return;
        }
        try {
            Object current = geInstanceField.get(null);
            if (current == null) {
                setStaticField(geInstanceField, new StubGraphicsEnvironment(null));
            }
        } catch (Throwable t) {
            logger.debug("Could not ensure cached GraphicsEnvironment singleton: {}", t.getMessage());
        }
    }

    static boolean canForceHeadlessForTests() {
        return canForceHeadless;
    }

    static boolean isMacOsForTests() {
        return IS_MACOS;
    }

    static boolean canSwapGeForTests() {
        return canSwapGe;
    }

    static boolean canSwapToolkitForTests() {
        return canSwapToolkit;
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
            if (delegate != null) {
                return delegate.createGraphics(img);
            }
            return img.createGraphics();
        }

        @Override
        public Font[] getAllFonts() {
            try {
                return delegate != null ? delegate.getAllFonts() : new Font[0];
            } catch (Throwable ignored) {
                return new Font[0];
            }
        }

        @Override
        public String[] getAvailableFontFamilyNames() {
            try {
                return delegate != null ? delegate.getAvailableFontFamilyNames() : new String[0];
            } catch (Throwable ignored) {
                return new String[0];
            }
        }

        @Override
        public String[] getAvailableFontFamilyNames(java.util.Locale l) {
            try {
                return delegate != null ? delegate.getAvailableFontFamilyNames(l) : new String[0];
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
