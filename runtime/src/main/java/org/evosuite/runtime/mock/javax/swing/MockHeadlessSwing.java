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

import org.evosuite.runtime.GuiSupport;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.MockFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JMenuBar;
import javax.swing.JTable;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.Icon;
import javax.swing.ComboBoxModel;
import javax.swing.ComboBoxEditor;
import javax.swing.plaf.basic.BasicComboBoxEditor;
import javax.swing.text.Caret;
import javax.swing.text.DefaultCaret;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.Position;
import javax.swing.text.Segment;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.JColorChooser;
import javax.swing.event.DocumentListener;
import javax.swing.event.UndoableEditListener;
import java.awt.AWTError;
import java.awt.AWTException;
import java.awt.Button;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.DefaultKeyboardFocusManager;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.HeadlessException;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.CheckboxMenuItem;
import java.awt.Label;
import java.awt.MediaTracker;
import java.awt.Menu;
import java.awt.MenuBar;
import java.awt.MenuItem;
import java.awt.MenuShortcut;
import java.awt.MouseInfo;
import java.awt.PointerInfo;
import java.awt.PopupMenu;
import java.awt.Robot;
import java.awt.Shape;
import java.awt.SystemTray;
import java.awt.TextArea;
import java.awt.TextField;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.datatransfer.FlavorMap;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Headless-safe replacements for Swing DnD-related APIs that can throw
 * HeadlessException when GUI setup is executed in headless CI.
 */
public final class MockHeadlessSwing {

    private static final Logger logger = LoggerFactory.getLogger(MockHeadlessSwing.class);

    private static final Dimension HEADLESS_SCREEN_SIZE = new Dimension(1024, 768);
    private static final GraphicsEnvironment HEADLESS_GRAPHICS_ENVIRONMENT =
            new HeadlessSafeGraphicsEnvironment();

    /**
     * Sticky positive result of {@link #isRealSwingUiUsable()}. Only a success is
     * remembered; see there for why a failure must not be.
     */
    private static volatile boolean realSwingUiUsable;

    private static volatile boolean realSwingUiFailureLogged;

    private MockHeadlessSwing() {
    }

    private static boolean isMockingInHeadlessEnvironment() {
        if (!MockFramework.isEnabled()) {
            return false;
        }
        if (isHeadlessSafe()) {
            return true;
        }
        // EvoSuite temporarily flips headless to false for two scopes that are
        // still part of mocked test execution: per-constructor GUI scopes
        // (ConstructorStatement) and the whole JUnit recheck run. In both
        // windows the SUT is running under mocking, so we must keep returning
        // safe stubs from these replacements. Falling through to the real
        // JDK GraphicsEnvironment/Toolkit in this state would, on JDK 17.0.10+,
        // trip the "Local GraphicsEnvironment must not be null" AWTError when
        // the cached LocalGE.INSTANCE swap did not take, or expose uninstrumented
        // java.awt.* internals (called from the SUT) to a misconfigured GE.
        return GuiSupport.isHeadlessTemporarilyDisabledForMockConstruction();
    }

    private static boolean isGuiMockingEnabled() {
        // Both flags matter: the mock framework has to be active at all, and GUI
        // mocking specifically has to be switched on (-Dreplace_gui, mirrored into
        // RuntimeSettings.mockGUI by the client process and by the generated
        // scaffolding). Ignoring mockGUI here made -Dreplace_gui=false a no-op.
        return MockFramework.isEnabled() && RuntimeSettings.mockGUI;
    }

    /**
     * Checks whether real Swing UI delegates can be installed in this JVM.
     *
     * <p>{@code -Djava.awt.headless=true} on a normal workstation still has a
     * display and a font configuration underneath, and there real Swing installs
     * fonts, carets and UI delegates just fine. A bare CI container may have
     * neither, and building a UI delegate ends in font-metrics code that can then
     * fail. So instead of assuming either way, build a throwaway real text area
     * and menu bar and look at what came out.
     *
     * <p>Only a success is remembered: the very first probe can run in a
     * temporarily hostile state, e.g. inside the sandbox while the SUT is
     * executing, and caching that failure would silently degrade every GUI
     * component for the rest of the JVM. Re-probing after a failure costs one
     * throwaway component, and a broken environment fails it immediately.
     *
     * @return true if real UI delegates populate font and caret in this JVM
     */
    private static boolean isRealSwingUiUsable() {
        if (realSwingUiUsable) {
            return true;
        }
        try {
            JTextArea probeArea = new JTextArea("x", 1, 1);
            JMenuBar probeMenuBar = new JMenuBar();
            if (probeArea.getFont() != null
                    && probeArea.getCaret() != null
                    && probeMenuBar.getFont() != null) {
                realSwingUiUsable = true;
                return true;
            }
            logRealSwingUiFailureOnce("font/caret stay unset");
        } catch (Throwable t) {
            logRealSwingUiFailureOnce(t.toString());
        }
        return false;
    }

    private static void logRealSwingUiFailureOnce(String reason) {
        if (realSwingUiFailureLogged) {
            return;
        }
        realSwingUiFailureLogged = true;
        logger.debug("Real Swing UI delegates are unusable here,"
                + " falling back to stub GUI components: {}", reason);
    }

    /**
     * Runs the real {@code updateUI()} of a mocked GUI component if this JVM can
     * support it. Callers must always patch up whatever is still missing
     * afterwards: this is best effort, and the real delegate can also fail
     * silently (UIManager only logs and hands back a null delegate when the look
     * and feel has no class for the component).
     *
     * @param realUpdateUi the {@code super.updateUI()} of the calling component
     */
    private static void tryRealUpdateUI(Runnable realUpdateUi) {
        if (!isRealSwingUiUsable()) {
            return;
        }
        try {
            realUpdateUi.run();
        } catch (Throwable t) {
            // A component can still fail individually, e.g. because the SUT
            // installed its own look and feel. Never let that escape into the SUT.
            logger.debug("Real UI delegate installation failed, using stub component: {}",
                    t.toString());
        }
    }

    /**
     * Last-resort defaults for a text component whose real UI delegate could not
     * be installed. Without a caret, {@code JTextArea(String, int, int)} throws an
     * NPE from {@code select(0, 0)}, and without a font any SUT calling
     * {@code getFont().getSize()} does too, so the stub has to supply both.
     * Only fills in what the real UI delegate did not.
     *
     * @param component the text component to patch up
     */
    private static void installMinimalTextDefaults(JTextComponent component) {
        try {
            if (component.getCaret() == null) {
                component.setCaret(new DefaultCaret());
            }
        } catch (Throwable t) {
            logger.debug("Could not install minimal caret: {}", t.toString());
        }
        installMinimalFont(component);
    }

    /**
     * Gives a mocked component a font if its UI delegate did not, so that SUT
     * code doing {@code component.getFont().getSize()} keeps working.
     *
     * @param component the component to patch up
     */
    private static void installMinimalFont(Component component) {
        try {
            if (component.getFont() == null) {
                component.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
            }
        } catch (Throwable t) {
            logger.debug("Could not install minimal font: {}", t.toString());
        }
    }

    private static boolean isHeadlessSafe() {
        try {
            return GraphicsEnvironment.isHeadless();
        } catch (Throwable ignored) {
            // If AWT initialization is broken, prefer headless-safe behavior.
            return true;
        }
    }

    /**
     * Replacement for {@code Toolkit.getScreenSize()}.
     *
     * @param source the toolkit instance
     * @return screen size; fixed value in headless mode when mocking is enabled
     */
    public static Dimension replacement_getScreenSize(Toolkit source) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (isMockingInHeadlessEnvironment()) {
            return new Dimension(HEADLESS_SCREEN_SIZE);
        }
        return source.getScreenSize();
    }

    /**
     * Replacement for {@code GraphicsEnvironment.getLocalGraphicsEnvironment()}.
     *
     * @return a headless-safe non-null graphics environment when mocking is enabled in headless mode
     */
    public static GraphicsEnvironment replacement_getLocalGraphicsEnvironment() {
        if (isMockingInHeadlessEnvironment()) {
            return HEADLESS_GRAPHICS_ENVIRONMENT;
        }
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment();
        } catch (Throwable ignored) {
            // Some CI/headless combinations may still throw AWTError
            // ("Local GraphicsEnvironment must not be null"). Keep generated
            // tests executable by returning a stable non-null environment.
            if (MockFramework.isEnabled()) {
                return HEADLESS_GRAPHICS_ENVIRONMENT;
            }
            throw ignored;
        }
    }

    /**
     * Replacement for {@code Toolkit.getDefaultToolkit()}.
     *
     * @return the default toolkit, or a cached toolkit in headless mock mode if default lookup fails
     */
    public static Toolkit replacement_getDefaultToolkit() {
        if (!isMockingInHeadlessEnvironment()) {
            return Toolkit.getDefaultToolkit();
        }
        try {
            return Toolkit.getDefaultToolkit();
        } catch (Throwable ignored) {
            return readCachedToolkit();
        }
    }

    /**
     * Replacement for {@code MediaTracker.waitForID(int)}.
     *
     * <p>{@code Toolkit.getImage(...)} itself never blocks: it hands back an {@code Image}
     * immediately and starts an asynchronous load on a background {@code sun.awt.image.ImageFetcher}
     * thread. The blocking happens here, in {@code MediaTracker}, which is exactly why a SUT
     * constructor that does {@code getImage(...)} followed by {@code tracker.waitForID(0)} can sit
     * for the entire test timeout: on a real display the fetcher finishes in milliseconds, but under
     * EvoSuite the fetcher thread is unrelated to progress of the search and the wait is pure lost
     * time. Note the return path below claims the wait <em>finished</em>, not that the image is
     * absent or broken; see {@link #replacement_statusID(MediaTracker, int, boolean)} for why that
     * distinction matters.
     *
     * @param source the media tracker
     * @param id     the tracking id
     * @throws InterruptedException propagated from the real wait when mocking is off
     */
    public static void replacement_waitForID(MediaTracker source, int id) throws InterruptedException {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!isMockingInHeadlessEnvironment()) {
            source.waitForID(id);
            return;
        }
        // Headless and mocking enabled: the load is not going to get any more done by waiting
        // for it, so treat it as already finished instead of blocking the search.
    }

    /**
     * Replacement for {@code MediaTracker.waitForID(int, long)}. See
     * {@link #replacement_waitForID(MediaTracker, int)}.
     *
     * @param source the media tracker
     * @param id     the tracking id
     * @param ms     the timeout in milliseconds
     * @return {@code true} (the wait is reported as complete) in mocked mode
     * @throws InterruptedException propagated from the real wait when mocking is off
     */
    public static boolean replacement_waitForID(MediaTracker source, int id, long ms) throws InterruptedException {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!isMockingInHeadlessEnvironment()) {
            return source.waitForID(id, ms);
        }
        return true;
    }

    /**
     * Replacement for {@code MediaTracker.waitForAll()}. See
     * {@link #replacement_waitForID(MediaTracker, int)}.
     *
     * @param source the media tracker
     * @throws InterruptedException propagated from the real wait when mocking is off
     */
    public static void replacement_waitForAll(MediaTracker source) throws InterruptedException {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!isMockingInHeadlessEnvironment()) {
            source.waitForAll();
            return;
        }
        // Headless and mocking enabled: nothing left to usefully wait for.
    }

    /**
     * Replacement for {@code MediaTracker.waitForAll(long)}. See
     * {@link #replacement_waitForID(MediaTracker, int)}.
     *
     * @param source the media tracker
     * @param ms     the timeout in milliseconds
     * @return {@code true} (the wait is reported as complete) in mocked mode
     * @throws InterruptedException propagated from the real wait when mocking is off
     */
    public static boolean replacement_waitForAll(MediaTracker source, long ms) throws InterruptedException {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!isMockingInHeadlessEnvironment()) {
            return source.waitForAll(ms);
        }
        return true;
    }

    /**
     * Replacement for {@code MediaTracker.checkID(int)}.
     *
     * <p>A caller that just returned from a (now instant) {@code waitForID}/{@code waitForAll} and
     * then asks {@code checkID}/{@code checkAll} whether the image is done must see the same answer
     * the wait implied, or it falls straight into its own retry/poll loop and the starvation comes
     * back through a different door. So this reports "done" here too, consistently with
     * {@link #replacement_statusID(MediaTracker, int, boolean)}.
     *
     * @param source the media tracker
     * @param id     the tracking id
     * @return {@code true} in mocked mode
     */
    public static boolean replacement_checkID(MediaTracker source, int id) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!isMockingInHeadlessEnvironment()) {
            return source.checkID(id);
        }
        return true;
    }

    /**
     * Replacement for {@code MediaTracker.checkID(int, boolean)}. See
     * {@link #replacement_checkID(MediaTracker, int)}.
     *
     * @param source the media tracker
     * @param id     the tracking id
     * @param load   whether to start loading if not already started
     * @return {@code true} in mocked mode
     */
    public static boolean replacement_checkID(MediaTracker source, int id, boolean load) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!isMockingInHeadlessEnvironment()) {
            return source.checkID(id, load);
        }
        return true;
    }

    /**
     * Replacement for {@code MediaTracker.checkAll()}. See
     * {@link #replacement_checkID(MediaTracker, int)}.
     *
     * @param source the media tracker
     * @return {@code true} in mocked mode
     */
    public static boolean replacement_checkAll(MediaTracker source) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!isMockingInHeadlessEnvironment()) {
            return source.checkAll();
        }
        return true;
    }

    /**
     * Replacement for {@code MediaTracker.checkAll(boolean)}. See
     * {@link #replacement_checkID(MediaTracker, int)}.
     *
     * @param source the media tracker
     * @param load   whether to start loading if not already started
     * @return {@code true} in mocked mode
     */
    public static boolean replacement_checkAll(MediaTracker source, boolean load) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!isMockingInHeadlessEnvironment()) {
            return source.checkAll(load);
        }
        return true;
    }

    /**
     * Replacement for {@code MediaTracker.statusID(int, boolean)}.
     *
     * <p>Reports {@link MediaTracker#COMPLETE}, not {@link MediaTracker#ERRORED}. Three earlier
     * defects in this file - {@code getAllFonts()} returning empty, {@code Container.add} being
     * skipped, {@code Cursor.getPredefinedCursor} returning null - all came from a mock claiming the
     * requested thing was simply not there, which starved SUT code that legitimately branches on
     * success. Reporting {@code ERRORED} here would repeat that mistake: SUT code that checks
     * {@code isErrorID}/{@code isErrorAny} or a {@code catch}/error-handling branch would now run
     * instead of the normal path the class was written for, for a reason that has nothing to do with
     * the actual image. {@code COMPLETE} is the answer consistent with "the wait is over and nothing
     * went wrong", which is the only claim this mock is actually entitled to make.
     *
     * @param source the media tracker
     * @param id     the tracking id
     * @param load   whether to start loading if not already started
     * @return {@link MediaTracker#COMPLETE} in mocked mode
     */
    public static int replacement_statusID(MediaTracker source, int id, boolean load) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!isMockingInHeadlessEnvironment()) {
            return source.statusID(id, load);
        }
        return MediaTracker.COMPLETE;
    }

    /**
     * Replacement for {@code MediaTracker.statusAll(boolean)}. See
     * {@link #replacement_statusID(MediaTracker, int, boolean)}.
     *
     * @param source the media tracker
     * @param load   whether to start loading if not already started
     * @return {@link MediaTracker#COMPLETE} in mocked mode
     */
    public static int replacement_statusAll(MediaTracker source, boolean load) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!isMockingInHeadlessEnvironment()) {
            return source.statusAll(load);
        }
        return MediaTracker.COMPLETE;
    }

    /**
     * Replacement for {@code setDragEnabled} on {@link JList}.
     *
     * @param source  the source component
     * @param enabled the enabled state
     */
    public static void replacement_setDragEnabled(JList<?> source, boolean enabled) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!isMockingInHeadlessEnvironment()) {
            source.setDragEnabled(enabled);
            return;
        }
        // Headless and mocking enabled: skip DnD activation.
    }

    /**
     * Generic replacement for {@code setDragEnabled}.
     *
     * @param source  the source component
     * @param enabled the enabled state
     */
    public static void replacement_setDragEnabledGeneric(Object source, boolean enabled) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (source instanceof JList) {
            replacement_setDragEnabled((JList<?>) source, enabled);
            return;
        }
        if (source instanceof JTree) {
            replacement_setDragEnabled((JTree) source, enabled);
            return;
        }
        if (source instanceof JTable) {
            replacement_setDragEnabled((JTable) source, enabled);
            return;
        }
        invokeSetDragEnabledReflective(source, enabled);
    }

    /**
     * Replacement for {@code setDragEnabled} on {@link JTree}.
     *
     * @param source  the source component
     * @param enabled the enabled state
     */
    public static void replacement_setDragEnabled(JTree source, boolean enabled) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!isMockingInHeadlessEnvironment()) {
            source.setDragEnabled(enabled);
            return;
        }
        // Headless and mocking enabled: skip DnD activation.
    }

    /**
     * Replacement for {@code setDragEnabled} on {@link JTable}.
     *
     * @param source  the source component
     * @param enabled the enabled state
     */
    public static void replacement_setDragEnabled(JTable source, boolean enabled) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!isMockingInHeadlessEnvironment()) {
            source.setDragEnabled(enabled);
            return;
        }
        // Headless and mocking enabled: skip DnD activation.
    }

    /**
     * Replacement for {@code setDragEnabled} on {@link JFileChooser}.
     *
     * @param source the source component
     * @param enabled the enabled state
     */
    public static void replacement_setDragEnabled(JFileChooser source, boolean enabled) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!isMockingInHeadlessEnvironment()) {
            source.setDragEnabled(enabled);
            return;
        }
        // Headless and mocking enabled: avoid HeadlessException for chooser drag support.
    }

    /**
     * Replacement for {@code setDropTarget} on {@link JComponent}.
     *
     * @param source     the source component
     * @param dropTarget the drop target
     */
    public static void replacement_setDropTarget(JComponent source, DropTarget dropTarget) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!isMockingInHeadlessEnvironment()) {
            source.setDropTarget(dropTarget);
            return;
        }
        // Headless and mocking enabled: skip DropTarget wiring.
    }

    /**
     * Generic replacement for {@code setDropTarget}.
     *
     * @param source     the source component
     * @param dropTarget the drop target
     */
    public static void replacement_setDropTargetGeneric(Object source, DropTarget dropTarget) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (source instanceof JComponent) {
            replacement_setDropTarget((JComponent) source, dropTarget);
            return;
        }
        invokeSetDropTargetReflective(source, dropTarget);
    }

    /**
     * Replacement for {@code setMixingCutoutShape} on {@link Component}.
     *
     * @param source the source component
     * @param shape  the shape
     */
    public static void replacement_setMixingCutoutShape(Component source, Shape shape) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!isMockingInHeadlessEnvironment()) {
            source.setMixingCutoutShape(shape);
            return;
        }
        // Headless and mocking enabled: skip expensive shape mixing computation.
    }

    /**
     * Generic replacement for {@code setMixingCutoutShape}.
     *
     * @param source the source component
     * @param shape  the shape
     */
    public static void replacement_setMixingCutoutShapeGeneric(Object source, Shape shape) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (source instanceof Component) {
            replacement_setMixingCutoutShape((Component) source, shape);
            return;
        }
        invokeSetMixingCutoutShapeReflective(source, shape);
    }

    /**
     * Replacement for {@code Component.setCursor(Cursor)}.
     *
     * @param source the source component
     * @param cursor the cursor to set
     */
    public static void replacement_setCursor(Component source, Cursor cursor) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!isMockingInHeadlessEnvironment()) {
            source.setCursor(cursor);
            return;
        }
        // Headless and mocking enabled: skip cursor activation.
    }

    /**
     * Generic replacement for {@code setCursor}.
     *
     * @param source the source component
     * @param cursor the cursor to set
     */
    public static void replacement_setCursorGeneric(Object source, Cursor cursor) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (source instanceof Component) {
            replacement_setCursor((Component) source, cursor);
            return;
        }
        invokeSetCursorReflective(source, cursor);
    }

    /**
     * Replacement for {@code JComboBox.setSelectedItem(Object)}.
     * In headless mode some GUI-heavy classes end up with a combo box lacking an editor,
     * which can trigger NPE in Swing internals during selection updates.
     *
     * @param source the combo box
     * @param item the selected item
     */
    public static void replacement_setSelectedItem(JComboBox<?> source, Object item) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!MockFramework.isEnabled()) {
            source.setSelectedItem(item);
            return;
        }

        boolean editable = safeIsEditable(source);
        boolean missingEditorInEditableCombo = editable && getEditorSafely(source) == null;
        if (!isMockingInHeadlessEnvironment() && !missingEditorInEditableCombo) {
            source.setSelectedItem(item);
            return;
        }

        try {
            if (editable) {
                ComboBoxEditor editor = getOrCreateEditorSafely(source);
                if (editor != null) {
                    editor.setItem(item);
                }
            }
            ComboBoxModel model = source.getModel();
            if (model != null) {
                model.setSelectedItem(item);
            }
        } catch (Throwable ignored) {
            // best effort in headless mode or when editor initialization is missing
        }
    }

    /**
     * Replacement for {@code JComboBox.setEditable(boolean)}.
     * Some headless paths leave editable combo boxes with a null editor.
     *
     * @param source   the combo box
     * @param editable requested editable state
     */
    public static void replacement_setEditable(JComboBox<?> source, boolean editable) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!MockFramework.isEnabled()) {
            source.setEditable(editable);
            return;
        }

        try {
            source.setEditable(editable);
        } catch (Throwable ignored) {
            if (!isMockingInHeadlessEnvironment()) {
                throw ignored;
            }
        }

        if (editable && getEditorSafely(source) == null) {
            getOrCreateEditorSafely(source);
        }
    }

    /**
     * Replacement for {@code JComboBox.getEditor()}.
     * Some headless GUI paths produce editable combo boxes with a null editor,
     * and downstream code then calls {@code getEditor().setItem(...)} unguarded.
     *
     * @param source the combo box
     * @return a non-null editor in mocked mode when the combo box would otherwise return null
     */
    public static ComboBoxEditor replacement_getEditor(JComboBox<?> source) {
        if (source == null) {
            throw new NullPointerException();
        }

        ComboBoxEditor editor = getEditorSafely(source);
        if (!MockFramework.isEnabled()) {
            return editor;
        }
        if (editor != null) {
            return editor;
        }

        return getOrCreateEditorSafely(source);
    }

    private static boolean safeIsEditable(JComboBox<?> source) {
        try {
            return source.isEditable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static ComboBoxEditor getEditorSafely(JComboBox<?> source) {
        try {
            return source.getEditor();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ComboBoxEditor getOrCreateEditorSafely(JComboBox<?> source) {
        ComboBoxEditor existing = getEditorSafely(source);
        if (existing != null) {
            return existing;
        }
        ComboBoxEditor fallback = new BasicComboBoxEditor();
        try {
            source.setEditor(fallback);
            ComboBoxEditor installed = getEditorSafely(source);
            return installed != null ? installed : fallback;
        } catch (Throwable ignored) {
            // If the combo cannot install an editor in headless mode, still
            // return a synthetic editor so client code can call setItem().
            return fallback;
        }
    }

    /**
     * Replacement for {@code JTextComponent.getCaret()}.
     * Some headless paths may leave the caret unset; provide a best-effort default caret
     * when mocking is enabled to avoid null dereferences in UI helper code.
     *
     * @param source the text component
     * @return the current caret, or a best-effort default caret in mocked mode
     */
    public static Caret replacement_getCaret(JTextComponent source) {
        if (source == null) {
            throw new NullPointerException();
        }
        Caret caret = source.getCaret();
        if (!MockFramework.isEnabled() || caret != null) {
            return caret;
        }
        try {
            source.setCaret(new DefaultCaret());
            return source.getCaret();
        } catch (Throwable ignored) {
            return caret;
        }
    }

    /**
     * Replacement for {@code JTextComponent.setCaretPosition(int)}.
     * In headless mocked environments, some components may have a null caret;
     * initialize one lazily and set position as a best effort.
     *
     * @param source   the text component
     * @param position target caret position
     */
    public static void replacement_setCaretPosition(JTextComponent source, int position) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!MockFramework.isEnabled()) {
            source.setCaretPosition(position);
            return;
        }
        try {
            source.setCaretPosition(position);
            return;
        } catch (NullPointerException ignored) {
            // fall through and try best-effort with synthetic caret
        }

        Caret caret = replacement_getCaret(source);
        if (caret == null) {
            return;
        }
        try {
            int docLength = 0;
            if (source.getDocument() != null) {
                docLength = source.getDocument().getLength();
            }
            int bounded = boundCaretPosition(position, docLength);
            caret.setDot(bounded);
        } catch (Throwable ignored) {
            // best effort
        }
    }

    /**
     * Replacement for {@code JTextComponent.moveCaretPosition(int)}.
     * Ensures a caret exists in mocked mode before moving the selection endpoint.
     */
    public static void replacement_moveCaretPosition(JTextComponent source, int position) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!MockFramework.isEnabled()) {
            source.moveCaretPosition(position);
            return;
        }
        try {
            source.moveCaretPosition(position);
            return;
        } catch (NullPointerException ignored) {
            // fall through and try best-effort with synthetic caret
        }
        withCaretBestEffort(source, position, false, true);
    }

    /**
     * Replacement for {@code JTextComponent.setSelectionStart(int)}.
     * In mocked mode this avoids null-caret crashes when Swing internals call caret.setDot().
     */
    public static void replacement_setSelectionStart(JTextComponent source, int selectionStart) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!MockFramework.isEnabled()) {
            source.setSelectionStart(selectionStart);
            return;
        }
        try {
            source.setSelectionStart(selectionStart);
            return;
        } catch (NullPointerException ignored) {
            // fall through and try best-effort with synthetic caret
        }
        withCaretBestEffort(source, selectionStart, true, false);
    }

    /**
     * Replacement for {@code JTextComponent.setSelectionEnd(int)}.
     * In mocked mode this avoids null-caret crashes when Swing internals call caret.moveDot().
     */
    public static void replacement_setSelectionEnd(JTextComponent source, int selectionEnd) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!MockFramework.isEnabled()) {
            source.setSelectionEnd(selectionEnd);
            return;
        }
        try {
            source.setSelectionEnd(selectionEnd);
            return;
        } catch (NullPointerException ignored) {
            // fall through and try best-effort with synthetic caret
        }
        withCaretBestEffort(source, selectionEnd, false, true);
    }

    /**
     * Replacement for {@code JTextComponent.select(int,int)}.
     * In mocked mode this avoids null-caret crashes by lazily creating a caret.
     */
    public static void replacement_select(JTextComponent source, int selectionStart, int selectionEnd) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!MockFramework.isEnabled()) {
            source.select(selectionStart, selectionEnd);
            return;
        }
        try {
            source.select(selectionStart, selectionEnd);
            return;
        } catch (NullPointerException ignored) {
            // fall through and try best-effort with synthetic caret
        }
        int docLength = getDocumentLengthSafely(source);
        int boundedStart = boundCaretPosition(selectionStart, docLength);
        int boundedEnd = boundCaretPosition(selectionEnd, docLength);
        Caret caret = replacement_getCaret(source);
        if (caret == null) {
            return;
        }
        try {
            caret.setDot(boundedStart);
            caret.moveDot(boundedEnd);
        } catch (Throwable ignored) {
            // best effort
        }
    }

    /**
     * Replacement for {@code JTextComponent.selectAll()}.
     * In mocked mode this avoids null-caret crashes by lazily creating a caret.
     */
    public static void replacement_selectAll(JTextComponent source) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!MockFramework.isEnabled()) {
            source.selectAll();
            return;
        }
        try {
            source.selectAll();
            return;
        } catch (NullPointerException ignored) {
            // fall through and try best-effort with synthetic caret
        }
        int docLength = getDocumentLengthSafely(source);
        Caret caret = replacement_getCaret(source);
        if (caret == null) {
            return;
        }
        try {
            caret.setDot(0);
            caret.moveDot(docLength);
        } catch (Throwable ignored) {
            // best effort
        }
    }

    private static void withCaretBestEffort(JTextComponent source,
                                            int position,
                                            boolean setDot,
                                            boolean moveDot) {
        Caret caret = replacement_getCaret(source);
        if (caret == null) {
            return;
        }
        int docLength = getDocumentLengthSafely(source);
        int bounded = boundCaretPosition(position, docLength);
        try {
            if (setDot) {
                caret.setDot(bounded);
            }
            if (moveDot) {
                if (!setDot && caret.getDot() < 0) {
                    caret.setDot(bounded);
                }
                caret.moveDot(bounded);
            }
        } catch (Throwable ignored) {
            // best effort
        }
    }

    private static int getDocumentLengthSafely(JTextComponent source) {
        try {
            return source.getDocument() == null ? 0 : source.getDocument().getLength();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int boundCaretPosition(int position, int docLength) {
        return Math.max(0, Math.min(position, Math.max(0, docLength)));
    }

    /**
     * Replacement for {@code JColorChooser.showDialog(...)}.
     */
    public static Color replacement_showColorDialog(Component parent, String title, Color initialColor) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return JColorChooser.showDialog(parent, title, initialColor);
    }

    /**
     * Replacement for {@code Desktop.isDesktopSupported()}.
     */
    public static boolean replacement_isDesktopSupported() {
        if (isMockingInHeadlessEnvironment()) {
            return false;
        }
        return Desktop.isDesktopSupported();
    }

    /**
     * Replacement for {@code Desktop.getDesktop()}.
     */
    public static Desktop replacement_getDesktop() {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return Desktop.getDesktop();
    }

    public static void replacement_desktopOpen(Desktop source, File file) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (isMockingInHeadlessEnvironment()) {
            return;
        }
        try {
            source.open(file);
        } catch (Exception ignored) {
        }
    }

    public static void replacement_desktopEdit(Desktop source, File file) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (isMockingInHeadlessEnvironment()) {
            return;
        }
        try {
            source.edit(file);
        } catch (Exception ignored) {
        }
    }

    public static void replacement_desktopPrint(Desktop source, File file) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (isMockingInHeadlessEnvironment()) {
            return;
        }
        try {
            source.print(file);
        } catch (Exception ignored) {
        }
    }

    public static void replacement_desktopBrowse(Desktop source, URI uri) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (isMockingInHeadlessEnvironment()) {
            return;
        }
        try {
            source.browse(uri);
        } catch (Exception ignored) {
        }
    }

    public static void replacement_desktopMail(Desktop source) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (isMockingInHeadlessEnvironment()) {
            return;
        }
        try {
            source.mail();
        } catch (Exception ignored) {
        }
    }

    public static void replacement_desktopMail(Desktop source, URI uri) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (isMockingInHeadlessEnvironment()) {
            return;
        }
        try {
            source.mail(uri);
        } catch (Exception ignored) {
        }
    }

    /**
     * Replacement for {@code SystemTray.isSupported()}.
     */
    public static boolean replacement_isSystemTraySupported() {
        if (isMockingInHeadlessEnvironment()) {
            return false;
        }
        return SystemTray.isSupported();
    }

    /**
     * Replacement for {@code SystemTray.getSystemTray()}.
     */
    public static SystemTray replacement_getSystemTray() {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return SystemTray.getSystemTray();
    }

    public static void replacement_systemTrayAdd(SystemTray source, TrayIcon trayIcon) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (isMockingInHeadlessEnvironment()) {
            return;
        }
        try {
            source.add(trayIcon);
        } catch (Exception ignored) {
        }
    }

    public static void replacement_systemTrayRemove(SystemTray source, TrayIcon trayIcon) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (isMockingInHeadlessEnvironment()) {
            return;
        }
        source.remove(trayIcon);
    }

    /**
     * Replacement for {@code new Robot()}.
     */
    public static Robot replacement_newRobot() {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        try {
            return new Robot();
        } catch (AWTException e) {
            return null;
        }
    }

    /**
     * Replacement for {@code new Robot(GraphicsDevice)}.
     */
    public static Robot replacement_newRobot(GraphicsDevice device) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        try {
            return new Robot(device);
        } catch (Exception e) {
            return null;
        }
    }

    public static BufferedImage replacement_robotCreateScreenCapture(Robot source, Rectangle screenRect) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (isMockingInHeadlessEnvironment()) {
            int w = screenRect == null ? 1 : Math.max(1, screenRect.width);
            int h = screenRect == null ? 1 : Math.max(1, screenRect.height);
            return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        }
        return source.createScreenCapture(screenRect);
    }

    /**
     * Replacement for {@code MouseInfo.getPointerInfo()}.
     */
    public static PointerInfo replacement_getPointerInfo() {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return MouseInfo.getPointerInfo();
    }

    /**
     * Replacement for {@code Cursor.getDefaultCursor()}. A predefined cursor is a description, not
     * a native resource, so a headless JVM builds one happily; only
     * {@link #replacement_getSystemCustomCursor(String)} needs the display. Returning null here
     * starved every SUT that asks for a cursor and then uses it - squirrel-sql's CursorChanger does
     * {@code if (newCursor == null) throw new IllegalArgumentException("null Cursor passed")},
     * which made 12 action classes unable to run actionPerformed at all.
     *
     * @return the default cursor
     */
    public static Cursor replacement_getDefaultCursor() {
        return Cursor.getDefaultCursor();
    }

    /**
     * Replacement for {@code Cursor.getPredefinedCursor(int)}. Works headless; see
     * {@link #replacement_getDefaultCursor()}.
     *
     * @param type predefined cursor type
     * @return the predefined cursor
     */
    public static Cursor replacement_getPredefinedCursor(int type) {
        return Cursor.getPredefinedCursor(type);
    }

    /**
     * Replacement for {@code Cursor.getSystemCustomCursor(String)}.
     *
     * @param name cursor name
     * @return system custom cursor or null in headless mode when mocking is enabled
     * @throws AWTException if the cursor cannot be loaded
     */
    public static Cursor replacement_getSystemCustomCursor(String name) throws AWTException {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return Cursor.getSystemCustomCursor(name);
    }

    /**
     * Replacement for {@code new Cursor(int)}. Works headless; see
     * {@link #replacement_getDefaultCursor()}.
     *
     * @param type predefined cursor type
     * @return a cursor instance
     */
    public static Cursor replacement_newCursor(int type) {
        return new Cursor(type);
    }

    /**
     * Replacement for {@code new Button()}.
     *
     * @return a button instance or null in headless mode when mocking is enabled
     */
    public static Button replacement_newButton() {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new Button();
    }

    /**
     * Replacement for {@code new Button(String)}.
     *
     * @param label button label
     * @return a button instance or null in headless mode when mocking is enabled
     */
    public static Button replacement_newButton(String label) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new Button(label);
    }

    /**
     * Replacement for {@code new Label()}.
     *
     * @return a label instance or null in headless mode when mocking is enabled
     */
    public static Label replacement_newLabel() {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new Label();
    }

    /**
     * Replacement for {@code new Label(String)}.
     *
     * @param text label text
     * @return a label instance or null in headless mode when mocking is enabled
     */
    public static Label replacement_newLabel(String text) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new Label(text);
    }

    /**
     * Replacement for {@code new Label(String, int)}.
     *
     * @param text label text
     * @param alignment text alignment
     * @return a label instance or null in headless mode when mocking is enabled
     */
    public static Label replacement_newLabel(String text, int alignment) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new Label(text, alignment);
    }

    /**
     * Replacement for {@code new TextField()}.
     *
     * @return a text field instance or null in headless mode when mocking is enabled
     */
    public static TextField replacement_newTextField() {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new TextField();
    }

    /**
     * Replacement for {@code new TextField(String)}.
     *
     * @param text initial text
     * @return a text field instance or null in headless mode when mocking is enabled
     */
    public static TextField replacement_newTextField(String text) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new TextField(text);
    }

    /**
     * Replacement for {@code new TextField(int)}.
     *
     * @param columns number of columns
     * @return a text field instance or null in headless mode when mocking is enabled
     */
    public static TextField replacement_newTextField(int columns) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new TextField(columns);
    }

    /**
     * Replacement for {@code new TextField(String, int)}.
     *
     * @param text initial text
     * @param columns number of columns
     * @return a text field instance or null in headless mode when mocking is enabled
     */
    public static TextField replacement_newTextField(String text, int columns) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new TextField(text, columns);
    }

    /**
     * Replacement for {@code new JTextField()}.
     *
     * @return a text field instance
     */
    public static JTextField replacement_newJTextField() {
        if (isGuiMockingEnabled()) {
            return new HeadlessSafeJTextField();
        }
        return new JTextField();
    }

    /**
     * Replacement for {@code new JTextField(String)}.
     *
     * @param text initial text
     * @return a text field instance
     */
    public static JTextField replacement_newJTextField(String text) {
        if (isGuiMockingEnabled()) {
            return new HeadlessSafeJTextField(text);
        }
        return new JTextField(text);
    }

    /**
     * Replacement for {@code new JTextField(int)}.
     *
     * @param columns number of columns
     * @return a text field instance
     */
    public static JTextField replacement_newJTextField(int columns) {
        if (isGuiMockingEnabled()) {
            return new HeadlessSafeJTextField(columns);
        }
        return new JTextField(columns);
    }

    /**
     * Replacement for {@code new JTextField(String, int)}.
     *
     * @param text initial text
     * @param columns number of columns
     * @return a text field instance
     */
    public static JTextField replacement_newJTextField(String text, int columns) {
        if (isGuiMockingEnabled()) {
            return new HeadlessSafeJTextField(text, columns);
        }
        return new JTextField(text, columns);
    }

    /**
     * Replacement for {@code new JTextField(Document, String, int)}.
     *
     * @param doc backing document
     * @param text initial text
     * @param columns number of columns
     * @return a text field instance
     */
    public static JTextField replacement_newJTextField(Document doc, String text, int columns) {
        if (isGuiMockingEnabled()) {
            return new HeadlessSafeJTextField(doc, text, columns);
        }
        return new JTextField(doc, text, columns);
    }

    /**
     * Replacement for {@code new JTextArea()}.
     *
     * @return a text area instance
     */
    public static JTextArea replacement_newJTextArea() {
        if (isGuiMockingEnabled()) {
            return new HeadlessSafeJTextArea();
        }
        return new JTextArea();
    }

    /**
     * Replacement for {@code new JTextArea(String)}.
     *
     * @param text initial text
     * @return a text area instance
     */
    public static JTextArea replacement_newJTextArea(String text) {
        if (isGuiMockingEnabled()) {
            return new HeadlessSafeJTextArea(text);
        }
        return new JTextArea(text);
    }

    /**
     * Replacement for {@code new JTextArea(int, int)}.
     *
     * @param rows number of rows
     * @param columns number of columns
     * @return a text area instance
     */
    public static JTextArea replacement_newJTextArea(int rows, int columns) {
        if (isGuiMockingEnabled()) {
            return new HeadlessSafeJTextArea(rows, columns);
        }
        return new JTextArea(rows, columns);
    }

    /**
     * Replacement for {@code new JTextArea(String, int, int)}.
     *
     * @param text initial text
     * @param rows number of rows
     * @param columns number of columns
     * @return a text area instance
     */
    public static JTextArea replacement_newJTextArea(String text, int rows, int columns) {
        if (isGuiMockingEnabled()) {
            return new HeadlessSafeJTextArea(text, rows, columns);
        }
        return new JTextArea(text, rows, columns);
    }

    /**
     * Replacement for {@code new JTextArea(Document)}.
     *
     * @param doc backing document
     * @return a text area instance
     */
    public static JTextArea replacement_newJTextArea(Document doc) {
        if (isGuiMockingEnabled()) {
            return new HeadlessSafeJTextArea(doc);
        }
        return new JTextArea(doc);
    }

    /**
     * Replacement for {@code new JTextArea(Document, String, int, int)}.
     *
     * @param doc backing document
     * @param text initial text
     * @param rows number of rows
     * @param columns number of columns
     * @return a text area instance
     */
    public static JTextArea replacement_newJTextArea(Document doc, String text, int rows, int columns) {
        if (isGuiMockingEnabled()) {
            return new HeadlessSafeJTextArea(doc, text, rows, columns);
        }
        return new JTextArea(doc, text, rows, columns);
    }

    /**
     * Replacement for {@code new JMenuBar()}.
     *
     * @return a menu bar instance
     */
    public static JMenuBar replacement_newJMenuBar() {
        if (isGuiMockingEnabled()) {
            return new HeadlessSafeJMenuBar();
        }
        return new JMenuBar();
    }

    /**
     * Generic replacement for {@code add(Component)}.
     *
     * @param source source object
     * @param comp component to add
     * @return added component
     */
    public static Component replacement_addComponentGeneric(Object source, Component comp) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (source instanceof Container) {
            return replacement_containerAdd((Container) source, comp);
        }
        return invokeAddComponentReflective(source, comp);
    }

    /**
     * Replacement for {@code Container.remove(Component)}. The symmetric case to
     * {@link #replacement_containerAdd(Container, Component)}: {@code Container.remove} reads
     * {@code comp.parent} exactly as {@code addImpl} does, so a component the mock layer replaced
     * with {@code null} blows up here too. 102_squirrel-sql's ButtonTabComponent does
     * {@code remove(null)} and threw on all ten seeds of the SF110 replication.
     *
     * @param source source container
     * @param comp component to remove
     */
    public static void replacement_containerRemove(Container source, Component comp) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (isMockedAwayComponent(comp)) {
            // The add was skipped for this null, so there is nothing to take out again.
            return;
        }
        source.remove(comp);
    }

    /**
     * Generic replacement for {@code remove(Component)}.
     *
     * @param source source object
     * @param comp component to remove
     */
    public static void replacement_removeComponentGeneric(Object source, Component comp) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (source instanceof Container) {
            replacement_containerRemove((Container) source, comp);
            return;
        }
        invokeRemoveComponentReflective(source, comp);
    }

    /**
     * Generic replacement for {@code add(String, Component)}.
     *
     * @param source source object
     * @param name component name
     * @param comp component to add
     * @return added component
     */
    public static Component replacement_addNameComponentGeneric(Object source, String name, Component comp) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (source instanceof Container) {
            return replacement_containerAdd((Container) source, name, comp);
        }
        return invokeAddNameComponentReflective(source, name, comp);
    }

    /**
     * Generic replacement for {@code add(Component, int)}.
     *
     * @param source source object
     * @param comp component to add
     * @param index insertion index
     * @return added component
     */
    public static Component replacement_addComponentIndexGeneric(Object source, Component comp, int index) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (source instanceof Container) {
            return replacement_containerAdd((Container) source, comp, index);
        }
        return invokeAddComponentIndexReflective(source, comp, index);
    }

    /**
     * Generic replacement for {@code add(Component, Object)}.
     *
     * @param source source object
     * @param comp component to add
     * @param constraints layout constraints
     */
    public static void replacement_addComponentConstraintsGeneric(Object source, Component comp, Object constraints) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (source instanceof Container) {
            replacement_containerAdd((Container) source, comp, constraints);
            return;
        }
        invokeAddComponentConstraintsReflective(source, comp, constraints);
    }

    /**
     * Generic replacement for {@code add(Component, Object, int)}.
     *
     * @param source source object
     * @param comp component to add
     * @param constraints layout constraints
     * @param index insertion index
     */
    public static void replacement_addComponentConstraintsIndexGeneric(
            Object source, Component comp, Object constraints, int index) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (source instanceof Container) {
            replacement_containerAdd((Container) source, comp, constraints, index);
            return;
        }
        invokeAddComponentConstraintsIndexReflective(source, comp, constraints, index);
    }

    /**
     * Generic replacement for {@code addTab(String, Component)}.
     *
     * @param source source object
     * @param title tab title
     * @param component tab component
     */
    public static void replacement_addTabTitleComponentGeneric(Object source, String title, Component component) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (source instanceof JTabbedPane) {
            replacement_tabbedPaneAddTab((JTabbedPane) source, title, component);
            return;
        }
        invokeAddTabTitleComponentReflective(source, title, component);
    }

    /**
     * Generic replacement for {@code addTab(String, Icon, Component)}.
     *
     * @param source source object
     * @param title tab title
     * @param icon tab icon
     * @param component tab component
     */
    public static void replacement_addTabTitleIconComponentGeneric(
            Object source, String title, Icon icon, Component component) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (source instanceof JTabbedPane) {
            replacement_tabbedPaneAddTab((JTabbedPane) source, title, icon, component);
            return;
        }
        invokeAddTabTitleIconComponentReflective(source, title, icon, component);
    }

    /**
     * Generic replacement for {@code addTab(String, Icon, Component, String)}.
     *
     * @param source source object
     * @param title tab title
     * @param icon tab icon
     * @param component tab component
     * @param tip tab tooltip
     */
    public static void replacement_addTabTitleIconComponentTipGeneric(
            Object source, String title, Icon icon, Component component, String tip) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (source instanceof JTabbedPane) {
            replacement_tabbedPaneAddTab((JTabbedPane) source, title, icon, component, tip);
            return;
        }
        invokeAddTabTitleIconComponentTipReflective(source, title, icon, component, tip);
    }

    /**
     * Generic replacement for {@code insertTab(String, Icon, Component, String, int)}.
     *
     * @param source source object
     * @param title tab title
     * @param icon tab icon
     * @param component tab component
     * @param tip tab tooltip
     * @param index tab index
     */
    public static void replacement_insertTabGeneric(
            Object source, String title, Icon icon, Component component, String tip, int index) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (source instanceof JTabbedPane) {
            replacement_tabbedPaneInsertTab((JTabbedPane) source, title, icon, component, tip, index);
            return;
        }
        invokeInsertTabReflective(source, title, icon, component, tip, index);
    }

    /**
     * Replacement for {@code JTabbedPane.addTab(String, Component)}.
     */
    public static void replacement_tabbedPaneAddTab(JTabbedPane source, String title, Component component) {
        if (source == null) {
            throw new NullPointerException();
        }
        addOrDegrade(() -> {
            source.addTab(title, component);
            return component;
        }, component);
    }

    /**
     * Replacement for {@code JTabbedPane.addTab(String, Icon, Component)}.
     */
    public static void replacement_tabbedPaneAddTab(
            JTabbedPane source, String title, Icon icon, Component component) {
        if (source == null) {
            throw new NullPointerException();
        }
        addOrDegrade(() -> {
            source.addTab(title, icon, component);
            return component;
        }, component);
    }

    /**
     * Replacement for {@code JTabbedPane.addTab(String, Icon, Component, String)}.
     */
    public static void replacement_tabbedPaneAddTab(
            JTabbedPane source, String title, Icon icon, Component component, String tip) {
        if (source == null) {
            throw new NullPointerException();
        }
        addOrDegrade(() -> {
            source.addTab(title, icon, component, tip);
            return component;
        }, component);
    }

    /**
     * Replacement for {@code JTabbedPane.insertTab(String, Icon, Component, String, int)}.
     */
    public static void replacement_tabbedPaneInsertTab(
            JTabbedPane source, String title, Icon icon, Component component, String tip, int index) {
        if (source == null) {
            throw new NullPointerException();
        }
        addOrDegrade(() -> {
            source.insertTab(title, icon, component, tip, index);
            return component;
        }, component);
    }

    /**
     * Adding a component to a container is bookkeeping: it needs no native peer and works under
     * {@code java.awt.headless=true}. Skipping it while GUI mocking is on left the container and
     * the component inconsistent, and the next call that reads the tree failed - 10_water-simulator's
     * ProgressGui does {@code mainPanel.add(tab1, null)} and then
     * {@code mainPanel.setSelectedComponent(tab1)}, which threw "component not found in tabbed pane"
     * on every seed. So do the real work and only fall back to the previous no-op if the environment
     * really refuses it.
     *
     * @param add       the real add call
     * @param mocked    what to return when a headless environment refuses the call
     * @return the result of the real call, or {@code mocked}
     */
    private static <T> T addOrDegrade(java.util.function.Supplier<T> add, T mocked) {
        try {
            return add.get();
        } catch (HeadlessException | AWTError e) {
            // HeadlessException is how AWT/Swing normally report "no display"; AWTError is
            // what Toolkit throws when it cannot create a native peer. Both are environment
            // refusals, which is what this method exists to paper over.
            //
            // Nothing else is caught here. In particular an AssertionError, LinkageError or
            // ExceptionInInitializerError can reach this call from inside a SUT override of
            // addImpl/insertTab/etc (Container and JTabbedPane invoke those virtually), and
            // those are real SUT failures, not a headless refusal - swallowing them would
            // turn a genuine defect into apparent success. A ThreadDeath (EvoSuite
            // force-stopping a wedged test thread) or a VirtualMachineError (the SUT
            // exhausted the JVM) are likewise real outcomes, not ours to hide; they were
            // never caught here even before this needed to be more selective.
            if (!isGuiMockingEnabled()) {
                throw e;
            }
            logger.debug("Headless environment refused a container add; leaving it out", e);
            return mocked;
        }
    }

    /**
     * The AWT constructor replacements hand back {@code null} for a heavyweight component in a
     * headless environment ({@link #replacement_newLabel(String)} and its siblings), so a rewritten
     * container add can be handed a null the SUT never created. {@code Container.addImpl}
     * dereferences it and the NullPointerException escapes whatever the SUT was building. There is
     * nothing to add, so skip the call instead. {@code JTabbedPane.addTab}/{@code insertTab} are
     * deliberately not covered: a null component is legal there and still adds a tab.
     *
     * @param comp the component the SUT is adding
     * @return whether the mock layer, not the SUT, is the source of this null
     */
    private static boolean isMockedAwayComponent(Component comp) {
        return comp == null && isGuiMockingEnabled();
    }

    /**
     * Replacement for {@code Container.add(Component)}.
     *
     * @param source source container
     * @param comp component to add
     * @return added component
     */
    public static Component replacement_containerAdd(Container source, Component comp) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (isMockedAwayComponent(comp)) {
            return comp;
        }
        return addOrDegrade(() -> source.add(comp), comp);
    }

    /**
     * Replacement for {@code Container.add(String, Component)}.
     *
     * @param source source container
     * @param name component name
     * @param comp component to add
     * @return added component
     */
    public static Component replacement_containerAdd(Container source, String name, Component comp) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (isMockedAwayComponent(comp)) {
            return comp;
        }
        return addOrDegrade(() -> source.add(name, comp), comp);
    }

    /**
     * Replacement for {@code Container.add(Component, int)}.
     *
     * @param source source container
     * @param comp component to add
     * @param index insertion index
     * @return added component
     */
    public static Component replacement_containerAdd(Container source, Component comp, int index) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (isMockedAwayComponent(comp)) {
            return comp;
        }
        return addOrDegrade(() -> source.add(comp, index), comp);
    }

    /**
     * Replacement for {@code Container.add(Component, Object)}.
     *
     * @param source source container
     * @param comp component to add
     * @param constraints layout constraints
     */
    public static void replacement_containerAdd(Container source, Component comp, Object constraints) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (isMockedAwayComponent(comp)) {
            return;
        }
        addOrDegrade(() -> {
            source.add(comp, constraints);
            return comp;
        }, comp);
    }

    /**
     * Replacement for {@code Container.add(Component, Object, int)}.
     *
     * @param source source container
     * @param comp component to add
     * @param constraints layout constraints
     * @param index insertion index
     */
    public static void replacement_containerAdd(Container source, Component comp, Object constraints, int index) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (isMockedAwayComponent(comp)) {
            return;
        }
        addOrDegrade(() -> {
            source.add(comp, constraints, index);
            return comp;
        }, comp);
    }

    /**
     * Replacement for {@code new MenuItem()}.
     *
     * @return a menu item instance or null in headless mode when mocking is enabled
     */
    public static MenuItem replacement_newMenuItem() {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new MenuItem();
    }

    /**
     * Replacement for {@code new MenuItem(String)}.
     *
     * @param label menu item label
     * @return a menu item instance or null in headless mode when mocking is enabled
     */
    public static MenuItem replacement_newMenuItem(String label) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new MenuItem(label);
    }

    /**
     * Replacement for {@code new MenuItem(String, MenuShortcut)}.
     *
     * @param label menu item label
     * @param shortcut menu shortcut
     * @return a menu item instance or null in headless mode when mocking is enabled
     */
    public static MenuItem replacement_newMenuItem(String label, MenuShortcut shortcut) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new MenuItem(label, shortcut);
    }

    /**
     * Replacement for {@code new Menu()}.
     *
     * @return a menu instance or null in headless mode when mocking is enabled
     */
    public static Menu replacement_newMenu() {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new Menu();
    }

    /**
     * Replacement for {@code new Menu(String)}.
     *
     * @param label menu label
     * @return a menu instance or null in headless mode when mocking is enabled
     */
    public static Menu replacement_newMenu(String label) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new Menu(label);
    }

    /**
     * Replacement for {@code new Menu(String, boolean)}.
     *
     * @param label menu label
     * @param tearOff whether this is a tear-off menu
     * @return a menu instance or null in headless mode when mocking is enabled
     */
    public static Menu replacement_newMenu(String label, boolean tearOff) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new Menu(label, tearOff);
    }

    /**
     * Replacement for {@code new MenuBar()}.
     *
     * @return a menu bar instance or null in headless mode when mocking is enabled
     */
    public static MenuBar replacement_newMenuBar() {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new MenuBar();
    }

    /**
     * Replacement for {@code new Window(Frame)}.
     *
     * @param owner owner frame
     * @return a window instance or null in headless mode when mocking is enabled
     */
    public static Window replacement_newWindow(Frame owner) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new Window(owner);
    }

    /**
     * Replacement for {@code new Window(Window)}.
     *
     * @param owner owner window
     * @return a window instance or null in headless mode when mocking is enabled
     */
    public static Window replacement_newWindow(Window owner) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new Window(owner);
    }

    /**
     * Replacement for {@code new Window(Window, GraphicsConfiguration)}.
     *
     * @param owner owner window
     * @param gc graphics configuration
     * @return a window instance or null in headless mode when mocking is enabled
     */
    public static Window replacement_newWindow(Window owner, GraphicsConfiguration gc) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new Window(owner, gc);
    }

    /**
     * Replacement for {@code new Frame()}.
     *
     * @return a frame instance or null in headless mode when mocking is enabled
     */
    public static Frame replacement_newFrame() {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new Frame();
    }

    /**
     * Replacement for {@code new Frame(String)}.
     *
     * @param title frame title
     * @return a frame instance or null in headless mode when mocking is enabled
     */
    public static Frame replacement_newFrame(String title) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new Frame(title);
    }

    /**
     * Replacement for {@code new Dialog(Frame)}.
     *
     * @param owner owner frame
     * @return a dialog instance or null in headless mode when mocking is enabled
     */
    public static Dialog replacement_newDialog(Frame owner) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new Dialog(owner);
    }

    /**
     * Replacement for {@code new Dialog(Frame, boolean)}.
     *
     * @param owner owner frame
     * @param modal modal flag
     * @return a dialog instance or null in headless mode when mocking is enabled
     */
    public static Dialog replacement_newDialog(Frame owner, boolean modal) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new Dialog(owner, modal);
    }

    /**
     * Replacement for {@code new Dialog(Frame, String)}.
     *
     * @param owner owner frame
     * @param title dialog title
     * @return a dialog instance or null in headless mode when mocking is enabled
     */
    public static Dialog replacement_newDialog(Frame owner, String title) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new Dialog(owner, title);
    }

    /**
     * Replacement for {@code new Dialog(Frame, String, boolean)}.
     *
     * @param owner owner frame
     * @param title dialog title
     * @param modal modal flag
     * @return a dialog instance or null in headless mode when mocking is enabled
     */
    public static Dialog replacement_newDialog(Frame owner, String title, boolean modal) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new Dialog(owner, title, modal);
    }

    /**
     * Replacement for {@code new PopupMenu()}.
     *
     * @return a popup menu instance or null in headless mode when mocking is enabled
     */
    public static PopupMenu replacement_newPopupMenu() {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new PopupMenu();
    }

    /**
     * Replacement for {@code new PopupMenu(String)}.
     *
     * @param label popup menu label
     * @return a popup menu instance or null in headless mode when mocking is enabled
     */
    public static PopupMenu replacement_newPopupMenu(String label) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new PopupMenu(label);
    }

    /**
     * Replacement for {@code new CheckboxMenuItem()}.
     *
     * @return a checkbox menu item instance or null in headless mode when mocking is enabled
     */
    public static CheckboxMenuItem replacement_newCheckboxMenuItem() {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new CheckboxMenuItem();
    }

    /**
     * Replacement for {@code new CheckboxMenuItem(String)}.
     *
     * @param label checkbox menu item label
     * @return a checkbox menu item instance or null in headless mode when mocking is enabled
     */
    public static CheckboxMenuItem replacement_newCheckboxMenuItem(String label) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new CheckboxMenuItem(label);
    }

    /**
     * Replacement for {@code new CheckboxMenuItem(String, boolean)}.
     *
     * @param label checkbox menu item label
     * @param state selected state
     * @return a checkbox menu item instance or null in headless mode when mocking is enabled
     */
    public static CheckboxMenuItem replacement_newCheckboxMenuItem(String label, boolean state) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new CheckboxMenuItem(label, state);
    }

    /**
     * Replacement for {@code new TextArea()}.
     *
     * @return a text area instance or null in headless mode when mocking is enabled
     */
    public static TextArea replacement_newTextArea() {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new TextArea();
    }

    /**
     * Replacement for {@code new TextArea(String)}.
     *
     * @param text initial text
     * @return a text area instance or null in headless mode when mocking is enabled
     */
    public static TextArea replacement_newTextArea(String text) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new TextArea(text);
    }

    /**
     * Replacement for {@code new TextArea(int, int)}.
     *
     * @param rows number of rows
     * @param columns number of columns
     * @return a text area instance or null in headless mode when mocking is enabled
     */
    public static TextArea replacement_newTextArea(int rows, int columns) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new TextArea(rows, columns);
    }

    /**
     * Replacement for {@code new TextArea(String, int, int)}.
     *
     * @param text initial text
     * @param rows number of rows
     * @param columns number of columns
     * @return a text area instance or null in headless mode when mocking is enabled
     */
    public static TextArea replacement_newTextArea(String text, int rows, int columns) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new TextArea(text, rows, columns);
    }

    /**
     * Replacement for {@code new TextArea(String, int, int, int)}.
     *
     * @param text initial text
     * @param rows number of rows
     * @param columns number of columns
     * @param scrollbars scrollbar policy
     * @return a text area instance or null in headless mode when mocking is enabled
     */
    public static TextArea replacement_newTextArea(String text, int rows, int columns, int scrollbars) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new TextArea(text, rows, columns, scrollbars);
    }

    /**
     * Replacement for {@code new Insets(int, int, int, int)}.
     *
     * @param top top inset
     * @param left left inset
     * @param bottom bottom inset
     * @param right right inset
     * @return an insets instance. In headless mode we still return a real object
     * to avoid downstream NPEs in code that clones or dereferences insets.
     */
    public static Insets replacement_newInsets(int top, int left, int bottom, int right) {
        return new Insets(top, left, bottom, right);
    }

    /**
     * Replacement for {@code KeyboardFocusManager.getCurrentKeyboardFocusManager()}.
     *
     * @return current keyboard focus manager, or null in headless mode when mocking is enabled
     */
    public static KeyboardFocusManager replacement_getCurrentKeyboardFocusManager() {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return KeyboardFocusManager.getCurrentKeyboardFocusManager();
    }

    /**
     * Replacement for {@code KeyboardFocusManager.setCurrentKeyboardFocusManager(KeyboardFocusManager)}.
     *
     * @param manager the keyboard focus manager
     */
    public static void replacement_setCurrentKeyboardFocusManager(KeyboardFocusManager manager) {
        if (isMockingInHeadlessEnvironment()) {
            return;
        }
        KeyboardFocusManager.setCurrentKeyboardFocusManager(manager);
    }

    /**
     * Replacement for {@code new DefaultKeyboardFocusManager()}.
     *
     * @return a keyboard focus manager or null in headless mode when mocking is enabled
     */
    public static DefaultKeyboardFocusManager replacement_newDefaultKeyboardFocusManager() {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new DefaultKeyboardFocusManager();
    }

    /**
     * Replacement for {@code new DropTarget()}.
     *
     * @return a new drop target or null if headless
     */
    public static DropTarget replacement_newDropTarget() {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new DropTarget();
    }

    /**
     * Replacement for {@code new DropTarget(Component, DropTargetListener)}.
     *
     * @param c   the component
     * @param dtl the drop target listener
     * @return a new drop target or null if headless
     */
    public static DropTarget replacement_newDropTarget(Component c, DropTargetListener dtl) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new DropTarget(c, dtl);
    }

    /**
     * Replacement for {@code new DropTarget(Component, int, DropTargetListener)}.
     *
     * @param c   the component
     * @param ops the operations
     * @param dtl the drop target listener
     * @return a new drop target or null if headless
     */
    public static DropTarget replacement_newDropTarget(Component c, int ops, DropTargetListener dtl) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new DropTarget(c, ops, dtl);
    }

    /**
     * Replacement for {@code new DropTarget(Component, int, DropTargetListener, boolean)}.
     *
     * @param c   the component
     * @param ops the operations
     * @param dtl the drop target listener
     * @param act the active state
     * @return a new drop target or null if headless
     */
    public static DropTarget replacement_newDropTarget(Component c, int ops, DropTargetListener dtl, boolean act) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new DropTarget(c, ops, dtl, act);
    }

    /**
     * Replacement for {@code new DropTarget(Component, int, DropTargetListener, boolean, FlavorMap)}.
     *
     * @param c         the component
     * @param ops       the operations
     * @param dtl       the drop target listener
     * @param act       the active state
     * @param flavorMap the flavor map
     * @return a new drop target or null if headless
     */
    public static DropTarget replacement_newDropTarget(Component c, int ops, DropTargetListener dtl, boolean act,
                                                       FlavorMap flavorMap) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return new DropTarget(c, ops, dtl, act, flavorMap);
    }

    /**
     * Invoke {@code setDragEnabled} reflectively.
     *
     * @param source  the source component
     * @param enabled the enabled state
     */
    private static void invokeSetDragEnabledReflective(Object source, boolean enabled) {
        try {
            Method method = source.getClass().getMethod("setDragEnabled", boolean.class);
            method.invoke(source, enabled);
        } catch (InvocationTargetException e) {
            rethrowCause(e);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No setDragEnabled(boolean) on " + source.getClass().getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access setDragEnabled(boolean) on "
                    + source.getClass().getName(), e);
        }
    }

    /**
     * Invoke {@code setDropTarget} reflectively.
     *
     * @param source     the source component
     * @param dropTarget the drop target
     */
    private static void invokeSetDropTargetReflective(Object source, DropTarget dropTarget) {
        try {
            Method method = source.getClass().getMethod("setDropTarget", DropTarget.class);
            method.invoke(source, dropTarget);
        } catch (InvocationTargetException e) {
            rethrowCause(e);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No setDropTarget(DropTarget) on " + source.getClass().getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access setDropTarget(DropTarget) on "
                    + source.getClass().getName(), e);
        }
    }

    /**
     * Invoke {@code setMixingCutoutShape} reflectively.
     *
     * @param source the source component
     * @param shape  the shape
     */
    private static void invokeSetMixingCutoutShapeReflective(Object source, Shape shape) {
        try {
            Method method = source.getClass().getMethod("setMixingCutoutShape", Shape.class);
            method.invoke(source, shape);
        } catch (InvocationTargetException e) {
            rethrowCause(e);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No setMixingCutoutShape(Shape) on " + source.getClass().getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access setMixingCutoutShape(Shape) on "
                    + source.getClass().getName(), e);
        }
    }

    /**
     * Invoke {@code setCursor} reflectively.
     *
     * @param source the source component
     * @param cursor the cursor to set
     */
    private static void invokeSetCursorReflective(Object source, Cursor cursor) {
        try {
            Method method = source.getClass().getMethod("setCursor", Cursor.class);
            method.invoke(source, cursor);
        } catch (InvocationTargetException e) {
            rethrowCause(e);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No setCursor(Cursor) on " + source.getClass().getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access setCursor(Cursor) on "
                    + source.getClass().getName(), e);
        }
    }

    private static Component invokeAddComponentReflective(Object source, Component comp) {
        try {
            Method method = source.getClass().getMethod("add", Component.class);
            return (Component) method.invoke(source, comp);
        } catch (InvocationTargetException e) {
            rethrowCause(e);
            return null;
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No add(Component) on " + source.getClass().getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access add(Component) on " + source.getClass().getName(), e);
        }
    }

    private static void invokeRemoveComponentReflective(Object source, Component comp) {
        try {
            Method method = source.getClass().getMethod("remove", Component.class);
            method.invoke(source, comp);
        } catch (InvocationTargetException e) {
            rethrowCause(e);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No remove(Component) on " + source.getClass().getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access remove(Component) on "
                    + source.getClass().getName(), e);
        }
    }

    private static Component invokeAddNameComponentReflective(Object source, String name, Component comp) {
        try {
            Method method = source.getClass().getMethod("add", String.class, Component.class);
            return (Component) method.invoke(source, name, comp);
        } catch (InvocationTargetException e) {
            rethrowCause(e);
            return null;
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No add(String, Component) on " + source.getClass().getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access add(String, Component) on "
                    + source.getClass().getName(), e);
        }
    }

    private static Component invokeAddComponentIndexReflective(Object source, Component comp, int index) {
        try {
            Method method = source.getClass().getMethod("add", Component.class, int.class);
            return (Component) method.invoke(source, comp, index);
        } catch (InvocationTargetException e) {
            rethrowCause(e);
            return null;
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No add(Component, int) on " + source.getClass().getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access add(Component, int) on "
                    + source.getClass().getName(), e);
        }
    }

    private static void invokeAddComponentConstraintsReflective(Object source, Component comp, Object constraints) {
        try {
            Method method = source.getClass().getMethod("add", Component.class, Object.class);
            method.invoke(source, comp, constraints);
        } catch (InvocationTargetException e) {
            rethrowCause(e);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No add(Component, Object) on " + source.getClass().getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access add(Component, Object) on "
                    + source.getClass().getName(), e);
        }
    }

    private static void invokeAddComponentConstraintsIndexReflective(
            Object source, Component comp, Object constraints, int index) {
        try {
            Method method = source.getClass().getMethod("add", Component.class, Object.class, int.class);
            method.invoke(source, comp, constraints, index);
        } catch (InvocationTargetException e) {
            rethrowCause(e);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No add(Component, Object, int) on "
                    + source.getClass().getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access add(Component, Object, int) on "
                    + source.getClass().getName(), e);
        }
    }

    private static void invokeAddTabTitleComponentReflective(Object source, String title, Component component) {
        try {
            Method method = source.getClass().getMethod("addTab", String.class, Component.class);
            method.invoke(source, title, component);
        } catch (InvocationTargetException e) {
            rethrowCause(e);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No addTab(String, Component) on " + source.getClass().getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access addTab(String, Component) on "
                    + source.getClass().getName(), e);
        }
    }

    private static void invokeAddTabTitleIconComponentReflective(
            Object source, String title, Icon icon, Component component) {
        try {
            Method method = source.getClass().getMethod("addTab", String.class, Icon.class, Component.class);
            method.invoke(source, title, icon, component);
        } catch (InvocationTargetException e) {
            rethrowCause(e);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No addTab(String, Icon, Component) on "
                    + source.getClass().getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access addTab(String, Icon, Component) on "
                    + source.getClass().getName(), e);
        }
    }

    private static void invokeAddTabTitleIconComponentTipReflective(
            Object source, String title, Icon icon, Component component, String tip) {
        try {
            Method method = source.getClass().getMethod(
                    "addTab", String.class, Icon.class, Component.class, String.class);
            method.invoke(source, title, icon, component, tip);
        } catch (InvocationTargetException e) {
            rethrowCause(e);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No addTab(String, Icon, Component, String) on "
                    + source.getClass().getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access addTab(String, Icon, Component, String) on "
                    + source.getClass().getName(), e);
        }
    }

    private static void invokeInsertTabReflective(
            Object source, String title, Icon icon, Component component, String tip, int index) {
        try {
            Method method = source.getClass().getMethod(
                    "insertTab", String.class, Icon.class, Component.class, String.class, int.class);
            method.invoke(source, title, icon, component, tip, index);
        } catch (InvocationTargetException e) {
            rethrowCause(e);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No insertTab(String, Icon, Component, String, int) on "
                    + source.getClass().getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access insertTab(String, Icon, Component, String, int) on "
                    + source.getClass().getName(), e);
        }
    }

    private static Toolkit readCachedToolkit() {
        try {
            java.lang.reflect.Field toolkitField = Toolkit.class.getDeclaredField("toolkit");
            toolkitField.setAccessible(true);
            Object cachedToolkit = toolkitField.get(null);
            if (cachedToolkit instanceof Toolkit) {
                return (Toolkit) cachedToolkit;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return null;
    }

    /**
     * Headless-safe JTextField variant that avoids LAF/UI initialization.
     */
    private static final class HeadlessSafeJTextField extends JTextField {

        private static final long serialVersionUID = 1L;

        private HeadlessSafeJTextField() {
            this(null, null, 0);
        }

        private HeadlessSafeJTextField(String text) {
            this(null, text, 0);
        }

        private HeadlessSafeJTextField(int columns) {
            this(null, null, columns);
        }

        private HeadlessSafeJTextField(String text, int columns) {
            this(null, text, columns);
        }

        private HeadlessSafeJTextField(Document doc, String text, int columns) {
            super(doc != null ? doc : new HeadlessSafeDocument(), text, columns);
        }

        @Override
        public void updateUI() {
            // Install the real UI delegate where the JVM supports it: it is what
            // gives the component its font and caret, and SUTs read those from
            // their own constructors. Skipping it unconditionally is what used to
            // leave font/caret/ui null (see isRealSwingUiUsable).
            tryRealUpdateUI(super::updateUI);
            installMinimalTextDefaults(this);
        }

        @Override
        protected Document createDefaultModel() {
            return new HeadlessSafeDocument();
        }
    }

    /**
     * Headless-safe JMenuBar variant that avoids LAF/UI initialization.
     */
    private static final class HeadlessSafeJMenuBar extends JMenuBar {

        private static final long serialVersionUID = 1L;

        @Override
        public void updateUI() {
            // Same rationale as HeadlessSafeJTextField: prefer the real delegate,
            // degrade to a bare component only where it cannot be installed.
            tryRealUpdateUI(super::updateUI);
            installMinimalFont(this);
        }
    }

    /**
     * Headless-safe JTextArea variant that avoids LAF/UI initialization.
     */
    private static final class HeadlessSafeJTextArea extends JTextArea {

        private static final long serialVersionUID = 1L;

        private HeadlessSafeJTextArea() {
            this(null, null, 0, 0);
        }

        private HeadlessSafeJTextArea(String text) {
            this(null, text, 0, 0);
        }

        private HeadlessSafeJTextArea(int rows, int columns) {
            this(null, null, rows, columns);
        }

        private HeadlessSafeJTextArea(String text, int rows, int columns) {
            this(null, text, rows, columns);
        }

        private HeadlessSafeJTextArea(Document doc) {
            this(doc, null, 0, 0);
        }

        private HeadlessSafeJTextArea(Document doc, String text, int rows, int columns) {
            super(doc != null ? doc : new HeadlessSafeDocument(), text, rows, columns);
        }

        @Override
        public void updateUI() {
            // Same rationale as HeadlessSafeJTextField. Note that JTextArea's
            // text constructors call select(0, 0), so a component that comes out
            // of here without a caret throws an NPE before the SUT sees it.
            tryRealUpdateUI(super::updateUI);
            installMinimalTextDefaults(this);
        }

        @Override
        protected Document createDefaultModel() {
            return new HeadlessSafeDocument();
        }
    }

    /**
     * Minimal text model to support headless-safe JTextField construction
     * without invoking Swing's styled document internals.
     */
    private static final class HeadlessSafeDocument implements Document {

        private final StringBuilder text = new StringBuilder();
        private final Map<Object, Object> properties = new HashMap<>();
        private final Element rootElement = new HeadlessSafeElement(this);

        @Override
        public int getLength() {
            return text.length();
        }

        @Override
        public void addDocumentListener(DocumentListener listener) {
            // no-op
        }

        @Override
        public void removeDocumentListener(DocumentListener listener) {
            // no-op
        }

        @Override
        public void addUndoableEditListener(UndoableEditListener listener) {
            // no-op
        }

        @Override
        public void removeUndoableEditListener(UndoableEditListener listener) {
            // no-op
        }

        @Override
        public Object getProperty(Object key) {
            return properties.get(key);
        }

        @Override
        public void putProperty(Object key, Object value) {
            properties.put(key, value);
        }

        @Override
        public void remove(int offs, int len) throws BadLocationException {
            if (offs < 0 || len < 0 || offs + len > text.length()) {
                throw new BadLocationException("Invalid range", offs);
            }
            text.delete(offs, offs + len);
        }

        @Override
        public void insertString(int offset, String str, javax.swing.text.AttributeSet a)
                throws BadLocationException {
            if (offset < 0 || offset > text.length()) {
                throw new BadLocationException("Invalid offset", offset);
            }
            if (str != null) {
                text.insert(offset, str);
            }
        }

        @Override
        public String getText(int offset, int length) throws BadLocationException {
            if (offset < 0 || length < 0 || offset + length > text.length()) {
                throw new BadLocationException("Invalid range", offset);
            }
            return text.substring(offset, offset + length);
        }

        @Override
        public void getText(int offset, int length, Segment txt) throws BadLocationException {
            String value = getText(offset, length);
            txt.array = value.toCharArray();
            txt.offset = 0;
            txt.count = txt.array.length;
        }

        @Override
        public Position getStartPosition() {
            return new HeadlessSafePosition(0);
        }

        @Override
        public Position getEndPosition() {
            return new HeadlessSafePosition(text.length());
        }

        @Override
        public Position createPosition(int offs) throws BadLocationException {
            if (offs < 0 || offs > text.length()) {
                throw new BadLocationException("Invalid offset", offs);
            }
            return new HeadlessSafePosition(offs);
        }

        @Override
        public Element[] getRootElements() {
            return new Element[]{rootElement};
        }

        @Override
        public Element getDefaultRootElement() {
            return rootElement;
        }

        @Override
        public void render(Runnable r) {
            if (r != null) {
                r.run();
            }
        }
    }

    private static final class HeadlessSafePosition implements Position {

        private final int offset;

        private HeadlessSafePosition(int offset) {
            this.offset = offset;
        }

        @Override
        public int getOffset() {
            return offset;
        }
    }

    private static final class HeadlessSafeElement implements Element {

        private final Document document;

        private HeadlessSafeElement(Document document) {
            this.document = document;
        }

        @Override
        public Document getDocument() {
            return document;
        }

        @Override
        public Element getParentElement() {
            return null;
        }

        @Override
        public String getName() {
            return "headlessRoot";
        }

        @Override
        public javax.swing.text.AttributeSet getAttributes() {
            return SimpleAttributeSet.EMPTY;
        }

        @Override
        public int getStartOffset() {
            return 0;
        }

        @Override
        public int getEndOffset() {
            return document.getLength();
        }

        @Override
        public int getElementIndex(int offset) {
            return -1;
        }

        @Override
        public int getElementCount() {
            return 0;
        }

        @Override
        public Element getElement(int index) {
            return null;
        }

        @Override
        public boolean isLeaf() {
            return true;
        }
    }

    private static final class HeadlessSafeGraphicsEnvironment extends GraphicsEnvironment {

        private final GraphicsDevice[] devices = new GraphicsDevice[]{new HeadlessSafeGraphicsDevice()};

        @Override
        public GraphicsDevice[] getScreenDevices() {
            return devices.clone();
        }

        @Override
        public GraphicsDevice getDefaultScreenDevice() {
            return devices[0];
        }

        @Override
        public java.awt.Graphics2D createGraphics(BufferedImage img) {
            if (img == null) {
                throw new NullPointerException();
            }
            return img.createGraphics();
        }

        @Override
        public java.awt.Font[] getAllFonts() {
            // Font enumeration does not need a display device: even the JDK's own
            // sun.java2d.HeadlessGraphicsEnvironment delegates font queries to the
            // real font manager while still rejecting display-only operations. This
            // stub used to hand back an empty array unconditionally, which starves
            // any SUT branch that depends on a non-empty font list (e.g. iterating
            // getAvailableFontFamilyNames() looking for a match) of real data purely
            // because EvoSuite runs headless. Fall back to empty only if the real
            // environment genuinely cannot be reached.
            try {
                return GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts();
            } catch (Throwable ignored) {
                return new java.awt.Font[0];
            }
        }

        @Override
        public String[] getAvailableFontFamilyNames() {
            try {
                return GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
            } catch (Throwable ignored) {
                return new String[0];
            }
        }

        @Override
        public String[] getAvailableFontFamilyNames(java.util.Locale l) {
            try {
                return GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(l);
            } catch (Throwable ignored) {
                return new String[0];
            }
        }
    }

    private static final class HeadlessSafeGraphicsDevice extends GraphicsDevice {

        @Override
        public int getType() {
            return TYPE_RASTER_SCREEN;
        }

        @Override
        public String getIDstring() {
            return "EvoSuiteHeadlessDevice";
        }

        @Override
        public GraphicsConfiguration[] getConfigurations() {
            return new GraphicsConfiguration[]{GuiSupport.getStubGraphicsConfiguration()};
        }

        @Override
        public GraphicsConfiguration getDefaultConfiguration() {
            return GuiSupport.getStubGraphicsConfiguration();
        }
    }

    /**
     * Rethrow cause of {@link InvocationTargetException}.
     *
     * @param e the exception
     */
    private static void rethrowCause(InvocationTargetException e) {
        Throwable cause = e.getCause();
        if (cause == null) {
            throw new RuntimeException(e);
        }
        if (cause instanceof RuntimeException) {
            throw (RuntimeException) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        throw new RuntimeException(cause);
    }
}
