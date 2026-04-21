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

import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.ComboBoxModel;
import javax.swing.ComboBoxEditor;
import javax.swing.plaf.basic.BasicComboBoxEditor;
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

    private MockHeadlessSwing() {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
            return new Dimension(HEADLESS_SCREEN_SIZE);
        }
        return source.getScreenSize();
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
        if (!MockFramework.isEnabled() || !GraphicsEnvironment.isHeadless()) {
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
        if (!MockFramework.isEnabled() || !GraphicsEnvironment.isHeadless()) {
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
        if (!MockFramework.isEnabled() || !GraphicsEnvironment.isHeadless()) {
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
        if (!MockFramework.isEnabled() || !GraphicsEnvironment.isHeadless()) {
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
        if (!MockFramework.isEnabled() || !GraphicsEnvironment.isHeadless()) {
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
        if (!MockFramework.isEnabled() || !GraphicsEnvironment.isHeadless()) {
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
        if (!MockFramework.isEnabled() || !GraphicsEnvironment.isHeadless()) {
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
        if (!MockFramework.isEnabled() || !GraphicsEnvironment.isHeadless()) {
            source.setSelectedItem(item);
            return;
        }

        try {
            if (source.isEditable()) {
                ComboBoxEditor editor = source.getEditor();
                if (editor == null) {
                    source.setEditor(new BasicComboBoxEditor());
                }
            }
            ComboBoxModel model = source.getModel();
            if (model != null) {
                model.setSelectedItem(item);
            }
        } catch (Throwable ignored) {
            // best effort in headless mode
        }
    }

    /**
     * Replacement for {@code JColorChooser.showDialog(...)}.
     */
    public static Color replacement_showColorDialog(Component parent, String title, Color initialColor) {
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
            return null;
        }
        return JColorChooser.showDialog(parent, title, initialColor);
    }

    /**
     * Replacement for {@code Desktop.isDesktopSupported()}.
     */
    public static boolean replacement_isDesktopSupported() {
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
            return false;
        }
        return Desktop.isDesktopSupported();
    }

    /**
     * Replacement for {@code Desktop.getDesktop()}.
     */
    public static Desktop replacement_getDesktop() {
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
            return null;
        }
        return Desktop.getDesktop();
    }

    public static void replacement_desktopOpen(Desktop source, File file) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
            return false;
        }
        return SystemTray.isSupported();
    }

    /**
     * Replacement for {@code SystemTray.getSystemTray()}.
     */
    public static SystemTray replacement_getSystemTray() {
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
            return null;
        }
        return SystemTray.getSystemTray();
    }

    public static void replacement_systemTrayAdd(SystemTray source, TrayIcon trayIcon) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
            return;
        }
        source.remove(trayIcon);
    }

    /**
     * Replacement for {@code new Robot()}.
     */
    public static Robot replacement_newRobot() {
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
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
