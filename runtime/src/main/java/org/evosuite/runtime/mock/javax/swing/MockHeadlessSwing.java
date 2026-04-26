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
import org.evosuite.runtime.mock.MockFramework;

import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.ComboBoxModel;
import javax.swing.ComboBoxEditor;
import javax.swing.plaf.basic.BasicComboBoxEditor;
import javax.swing.text.Caret;
import javax.swing.text.DefaultCaret;
import javax.swing.text.JTextComponent;
import javax.swing.JColorChooser;
import java.awt.AWTException;
import java.awt.Button;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.DefaultKeyboardFocusManager;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.CheckboxMenuItem;
import java.awt.Label;
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

/**
 * Headless-safe replacements for Swing DnD-related APIs that can throw
 * HeadlessException when GUI setup is executed in headless CI.
 */
public final class MockHeadlessSwing {
    private static final Dimension HEADLESS_SCREEN_SIZE = new Dimension(1024, 768);
    private static final GraphicsEnvironment HEADLESS_GRAPHICS_ENVIRONMENT =
            new HeadlessSafeGraphicsEnvironment();

    private MockHeadlessSwing() {
    }

    private static boolean isMockingInHeadlessEnvironment() {
        return MockFramework.isEnabled() && isHeadlessSafe();
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
     * Replacement for {@code Cursor.getDefaultCursor()}.
     *
     * @return default cursor or null in headless mode when mocking is enabled
     */
    public static Cursor replacement_getDefaultCursor() {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
        return Cursor.getDefaultCursor();
    }

    /**
     * Replacement for {@code Cursor.getPredefinedCursor(int)}.
     *
     * @param type predefined cursor type
     * @return predefined cursor or null in headless mode when mocking is enabled
     */
    public static Cursor replacement_getPredefinedCursor(int type) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
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
     * Replacement for {@code new Cursor(int)}.
     *
     * @param type predefined cursor type
     * @return a cursor instance or null in headless mode when mocking is enabled
     */
    public static Cursor replacement_newCursor(int type) {
        if (isMockingInHeadlessEnvironment()) {
            return null;
        }
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
            return new java.awt.Font[0];
        }

        @Override
        public String[] getAvailableFontFamilyNames() {
            return new String[0];
        }

        @Override
        public String[] getAvailableFontFamilyNames(java.util.Locale l) {
            return new String[0];
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
