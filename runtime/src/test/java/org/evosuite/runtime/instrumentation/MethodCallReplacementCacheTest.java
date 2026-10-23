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
package org.evosuite.runtime.instrumentation;

import org.evosuite.runtime.RuntimeSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;

public class MethodCallReplacementCacheTest {

    @BeforeEach
    public void enableJvmMocking() {
        RuntimeSettings.mockJVMNonDeterminism = true;
    }

    @AfterEach
    public void resetState() {
        RuntimeSettings.mockJVMNonDeterminism = false;
        MethodCallReplacementCache.resetSingleton();
    }

    @Test
    public void testStaticReplacementRequiresMethodInMockClass() throws Exception {
        Method console = java.lang.System.class.getMethod("console");
        String consoleKey = console.getName() + Type.getMethodDescriptor(console);

        Method arraycopy = java.lang.System.class.getMethod(
                "arraycopy",
                Object.class,
                int.class,
                Object.class,
                int.class,
                int.class);
        String arraycopyKey = arraycopy.getName() + Type.getMethodDescriptor(arraycopy);

        MethodCallReplacementCache cache = MethodCallReplacementCache.getInstance();
        Assertions.assertTrue(cache.hasReplacementCall("java/lang/System", consoleKey));
        Assertions.assertFalse(cache.hasReplacementCall("java/lang/System", arraycopyKey));
    }

    @Test
    public void testHeadlessSwingReplacementsAreRegistered() throws Exception {



        Method comboSetSelectedItem = javax.swing.JComboBox.class.getMethod("setSelectedItem", Object.class);
        String comboSetSelectedItemKey =
                comboSetSelectedItem.getName() + Type.getMethodDescriptor(comboSetSelectedItem);
        Method comboSetEditable = javax.swing.JComboBox.class.getMethod("setEditable", boolean.class);
        String comboSetEditableKey =
                comboSetEditable.getName() + Type.getMethodDescriptor(comboSetEditable);
        Method comboGetEditor = javax.swing.JComboBox.class.getMethod("getEditor");
        String comboGetEditorKey =
                comboGetEditor.getName() + Type.getMethodDescriptor(comboGetEditor);
        Method textGetCaret = javax.swing.text.JTextComponent.class.getMethod("getCaret");
        String textGetCaretKey =
                textGetCaret.getName() + Type.getMethodDescriptor(textGetCaret);
        Method textSetCaretPosition = javax.swing.text.JTextComponent.class.getMethod("setCaretPosition", int.class);
        String textSetCaretPositionKey =
                textSetCaretPosition.getName() + Type.getMethodDescriptor(textSetCaretPosition);
        Method showColorDialog = javax.swing.JColorChooser.class.getMethod(
                "showDialog", java.awt.Component.class, String.class, java.awt.Color.class);
        String showColorDialogKey = showColorDialog.getName() + Type.getMethodDescriptor(showColorDialog);
        Method isDesktopSupported = java.awt.Desktop.class.getMethod("isDesktopSupported");
        String isDesktopSupportedKey = isDesktopSupported.getName() + Type.getMethodDescriptor(isDesktopSupported);
        Method getDesktop = java.awt.Desktop.class.getMethod("getDesktop");
        String getDesktopKey = getDesktop.getName() + Type.getMethodDescriptor(getDesktop);
        Method desktopOpen = java.awt.Desktop.class.getMethod("open", java.io.File.class);
        String desktopOpenKey = desktopOpen.getName() + Type.getMethodDescriptor(desktopOpen);
        Method desktopEdit = java.awt.Desktop.class.getMethod("edit", java.io.File.class);
        String desktopEditKey = desktopEdit.getName() + Type.getMethodDescriptor(desktopEdit);
        Method desktopPrint = java.awt.Desktop.class.getMethod("print", java.io.File.class);
        String desktopPrintKey = desktopPrint.getName() + Type.getMethodDescriptor(desktopPrint);
        Method desktopBrowse = java.awt.Desktop.class.getMethod("browse", java.net.URI.class);
        String desktopBrowseKey = desktopBrowse.getName() + Type.getMethodDescriptor(desktopBrowse);
        Method desktopMail = java.awt.Desktop.class.getMethod("mail");
        String desktopMailKey = desktopMail.getName() + Type.getMethodDescriptor(desktopMail);
        Method desktopMailUri = java.awt.Desktop.class.getMethod("mail", java.net.URI.class);
        String desktopMailUriKey = desktopMailUri.getName() + Type.getMethodDescriptor(desktopMailUri);
        Method systemTraySupported = java.awt.SystemTray.class.getMethod("isSupported");
        String systemTraySupportedKey = systemTraySupported.getName() + Type.getMethodDescriptor(systemTraySupported);
        Method getSystemTray = java.awt.SystemTray.class.getMethod("getSystemTray");
        String getSystemTrayKey = getSystemTray.getName() + Type.getMethodDescriptor(getSystemTray);
        Method systemTrayAdd = java.awt.SystemTray.class.getMethod("add", java.awt.TrayIcon.class);
        String systemTrayAddKey = systemTrayAdd.getName() + Type.getMethodDescriptor(systemTrayAdd);
        Method systemTrayRemove = java.awt.SystemTray.class.getMethod("remove", java.awt.TrayIcon.class);
        String systemTrayRemoveKey = systemTrayRemove.getName() + Type.getMethodDescriptor(systemTrayRemove);
        String newRobotDefaultKey = "<init>()V";
        String newRobotDeviceKey = "<init>(Ljava/awt/GraphicsDevice;)V";
        Method robotCreateCapture = java.awt.Robot.class.getMethod("createScreenCapture", java.awt.Rectangle.class);
        String robotCreateCaptureKey = robotCreateCapture.getName() + Type.getMethodDescriptor(robotCreateCapture);
        Method mouseInfoPointer = java.awt.MouseInfo.class.getMethod("getPointerInfo");
        String mouseInfoPointerKey = mouseInfoPointer.getName() + Type.getMethodDescriptor(mouseInfoPointer);

        Method getScreenSize = java.awt.Toolkit.class.getMethod("getScreenSize");
        String getScreenSizeKey = getScreenSize.getName() + Type.getMethodDescriptor(getScreenSize);
        Method getDefaultToolkit = java.awt.Toolkit.class.getMethod("getDefaultToolkit");
        String getDefaultToolkitKey = getDefaultToolkit.getName() + Type.getMethodDescriptor(getDefaultToolkit);
        Method getLocalGraphicsEnvironment = java.awt.GraphicsEnvironment.class.getMethod(
                "getLocalGraphicsEnvironment");
        String getLocalGraphicsEnvironmentKey =
                getLocalGraphicsEnvironment.getName() + Type.getMethodDescriptor(getLocalGraphicsEnvironment);
        Method codeSourceGetLocation = java.security.CodeSource.class.getMethod("getLocation");
        String codeSourceGetLocationKey =
                codeSourceGetLocation.getName() + Type.getMethodDescriptor(codeSourceGetLocation);
        Method getDefaultCursor = java.awt.Cursor.class.getMethod("getDefaultCursor");
        String getDefaultCursorKey = getDefaultCursor.getName() + Type.getMethodDescriptor(getDefaultCursor);
        Method getPredefinedCursor = java.awt.Cursor.class.getMethod("getPredefinedCursor", int.class);
        String getPredefinedCursorKey = getPredefinedCursor.getName() + Type.getMethodDescriptor(getPredefinedCursor);
        Method getSystemCustomCursor = java.awt.Cursor.class.getMethod("getSystemCustomCursor", String.class);
        String getSystemCustomCursorKey =
                getSystemCustomCursor.getName() + Type.getMethodDescriptor(getSystemCustomCursor);
        String newCursorKey = "<init>(I)V";
        String newButtonDefaultKey = "<init>()V";
        String newButtonLabelKey = "<init>(Ljava/lang/String;)V";
        String newLabelDefaultKey = "<init>()V";
        String newLabelTextKey = "<init>(Ljava/lang/String;)V";
        String newLabelTextAlignKey = "<init>(Ljava/lang/String;I)V";
        String newJTextFieldDefaultKey = "<init>()V";
        String newJTextFieldTextKey = "<init>(Ljava/lang/String;)V";
        String newJTextFieldColumnsKey = "<init>(I)V";
        String newJTextFieldTextColumnsKey = "<init>(Ljava/lang/String;I)V";
        String newJTextFieldDocumentKey = "<init>(Ljavax/swing/text/Document;Ljava/lang/String;I)V";
        String newJMenuBarKey = "<init>()V";
        String newJTextAreaDefaultKey = "<init>()V";
        String newJTextAreaTextKey = "<init>(Ljava/lang/String;)V";
        String newJTextAreaRowsColsKey = "<init>(II)V";
        String newJTextAreaTextRowsColsKey = "<init>(Ljava/lang/String;II)V";
        String newJTextAreaDocumentKey = "<init>(Ljavax/swing/text/Document;)V";
        String newJTextAreaDocumentTextRowsColsKey = "<init>(Ljavax/swing/text/Document;Ljava/lang/String;II)V";
        String newMenuBarKey = "<init>()V";
        String newDialogFrameOwnerKey = "<init>(Ljava/awt/Frame;)V";
        String newDialogFrameModalKey = "<init>(Ljava/awt/Frame;Z)V";
        String newDialogFrameTitleKey = "<init>(Ljava/awt/Frame;Ljava/lang/String;)V";
        String newDialogFrameTitleModalKey = "<init>(Ljava/awt/Frame;Ljava/lang/String;Z)V";
        String newPopupMenuDefaultKey = "<init>()V";
        String newPopupMenuLabelKey = "<init>(Ljava/lang/String;)V";
        String newCheckboxMenuItemDefaultKey = "<init>()V";
        String newCheckboxMenuItemLabelKey = "<init>(Ljava/lang/String;)V";
        String newCheckboxMenuItemLabelStateKey = "<init>(Ljava/lang/String;Z)V";
        String newInsetsKey = "<init>(IIII)V";
        String newDefaultKfmKey = "<init>()V";
        Method getCurrentKfm = java.awt.KeyboardFocusManager.class.getMethod("getCurrentKeyboardFocusManager");
        String getCurrentKfmKey = getCurrentKfm.getName() + Type.getMethodDescriptor(getCurrentKfm);
        Method setCurrentKfm = java.awt.KeyboardFocusManager.class.getMethod(
                "setCurrentKeyboardFocusManager", java.awt.KeyboardFocusManager.class);
        String setCurrentKfmKey = setCurrentKfm.getName() + Type.getMethodDescriptor(setCurrentKfm);

        MethodCallReplacementCache cache = MethodCallReplacementCache.getInstance();
        Assertions.assertTrue(cache.hasReplacementCall("javax/swing/JComboBox", comboSetSelectedItemKey));
        Assertions.assertTrue(cache.hasReplacementCall("javax/swing/JComboBox", comboSetEditableKey));
        Assertions.assertTrue(cache.hasReplacementCall("javax/swing/JComboBox", comboGetEditorKey));
        Assertions.assertTrue(cache.hasReplacementCall("javax/swing/text/JTextComponent", textGetCaretKey));
        Assertions.assertTrue(cache.hasReplacementCall("javax/swing/text/JTextComponent", textSetCaretPositionKey));
        MethodCallReplacement comboReplacement = cache.getReplacementCall(
                "javax/swing/JComboBox", comboSetSelectedItemKey);
        java.lang.reflect.Field origOpcodeField = MethodCallReplacement.class.getDeclaredField("origOpcode");
        origOpcodeField.setAccessible(true);
        int comboOrigOpcode = origOpcodeField.getInt(comboReplacement);
        Assertions.assertEquals(Opcodes.INVOKEVIRTUAL, comboOrigOpcode,
                "Fallback opcode for JComboBox#setSelectedItem must remain INVOKEVIRTUAL");
        Assertions.assertTrue(cache.hasReplacementCall("javax/swing/JColorChooser", showColorDialogKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Desktop", isDesktopSupportedKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Desktop", getDesktopKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Desktop", desktopOpenKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Desktop", desktopEditKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Desktop", desktopPrintKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Desktop", desktopBrowseKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Desktop", desktopMailKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Desktop", desktopMailUriKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/SystemTray", systemTraySupportedKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/SystemTray", getSystemTrayKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/SystemTray", systemTrayAddKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/SystemTray", systemTrayRemoveKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Robot", newRobotDefaultKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Robot", newRobotDeviceKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Robot", robotCreateCaptureKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/MouseInfo", mouseInfoPointerKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Toolkit", getScreenSizeKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Toolkit", getDefaultToolkitKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/GraphicsEnvironment",
                getLocalGraphicsEnvironmentKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/security/CodeSource", codeSourceGetLocationKey));
        Assertions.assertFalse(cache.hasReplacementCall("java/awt/Cursor", getDefaultCursorKey));
        Assertions.assertFalse(cache.hasReplacementCall("java/awt/Cursor", getPredefinedCursorKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Cursor", getSystemCustomCursorKey));
        Assertions.assertFalse(cache.hasReplacementCall("java/awt/Cursor", newCursorKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Button", newButtonDefaultKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Button", newButtonLabelKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Label", newLabelDefaultKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Label", newLabelTextKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Label", newLabelTextAlignKey));
        Assertions.assertTrue(cache.hasReplacementCall("javax/swing/JTextField", newJTextFieldDefaultKey));
        Assertions.assertTrue(cache.hasReplacementCall("javax/swing/JTextField", newJTextFieldTextKey));
        Assertions.assertTrue(cache.hasReplacementCall("javax/swing/JTextField", newJTextFieldColumnsKey));
        Assertions.assertTrue(cache.hasReplacementCall("javax/swing/JTextField", newJTextFieldTextColumnsKey));
        Assertions.assertTrue(cache.hasReplacementCall("javax/swing/JTextField", newJTextFieldDocumentKey));
        Assertions.assertTrue(cache.hasReplacementCall("javax/swing/JMenuBar", newJMenuBarKey));
        Assertions.assertTrue(cache.hasReplacementCall("javax/swing/JTextArea", newJTextAreaDefaultKey));
        Assertions.assertTrue(cache.hasReplacementCall("javax/swing/JTextArea", newJTextAreaTextKey));
        Assertions.assertTrue(cache.hasReplacementCall("javax/swing/JTextArea", newJTextAreaRowsColsKey));
        Assertions.assertTrue(cache.hasReplacementCall("javax/swing/JTextArea", newJTextAreaTextRowsColsKey));
        Assertions.assertTrue(cache.hasReplacementCall("javax/swing/JTextArea", newJTextAreaDocumentKey));
        Assertions.assertTrue(cache.hasReplacementCall("javax/swing/JTextArea", newJTextAreaDocumentTextRowsColsKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/MenuBar", newMenuBarKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Dialog", newDialogFrameOwnerKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Dialog", newDialogFrameModalKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Dialog", newDialogFrameTitleKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Dialog", newDialogFrameTitleModalKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/PopupMenu", newPopupMenuDefaultKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/PopupMenu", newPopupMenuLabelKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/CheckboxMenuItem", newCheckboxMenuItemDefaultKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/CheckboxMenuItem", newCheckboxMenuItemLabelKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/CheckboxMenuItem",
                newCheckboxMenuItemLabelStateKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/Insets", newInsetsKey));
        Assertions.assertFalse(cache.hasReplacementCall("java/awt/KeyboardFocusManager", getCurrentKfmKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/awt/KeyboardFocusManager", setCurrentKfmKey));
        Assertions.assertFalse(cache.hasReplacementCall("java/awt/DefaultKeyboardFocusManager", newDefaultKfmKey));
    }

    @Test
    public void testWindowAndFrameConstructorsAreNullStandInsOnlyWithoutGuiMocks() {
        String[] windowKeys = {"<init>(Ljava/awt/Frame;)V", "<init>(Ljava/awt/Window;)V",
                "<init>(Ljava/awt/Window;Ljava/awt/GraphicsConfiguration;)V"};
        String[] frameKeys = {"<init>()V", "<init>(Ljava/lang/String;)V"};

        RuntimeSettings.mockGUI = false;
        MethodCallReplacementCache.resetSingleton();
        MethodCallReplacementCache cache = MethodCallReplacementCache.getInstance();
        for (String key : windowKeys) {
            Assertions.assertTrue(cache.hasReplacementCall("java/awt/Window", key));
            Assertions.assertFalse(cache.hasSpecialReplacementCall("java/awt/Window", key));
        }
        for (String key : frameKeys) {
            Assertions.assertTrue(cache.hasReplacementCall("java/awt/Frame", key));
            Assertions.assertFalse(cache.hasSpecialReplacementCall("java/awt/Frame", key));
        }

        RuntimeSettings.mockGUI = true;
        try {
            MethodCallReplacementCache.resetSingleton();
            cache = MethodCallReplacementCache.getInstance();
            for (String key : windowKeys) {
                Assertions.assertFalse(cache.hasReplacementCall("java/awt/Window", key));
                Assertions.assertTrue(cache.hasSpecialReplacementCall("java/awt/Window", key));
            }
            for (String key : frameKeys) {
                Assertions.assertFalse(cache.hasReplacementCall("java/awt/Frame", key));
                Assertions.assertTrue(cache.hasSpecialReplacementCall("java/awt/Frame", key));
            }
        } finally {
            RuntimeSettings.mockGUI = false;
        }
    }

    @Test
    public void testThreadSleepReplacementsAreRegistered() throws Exception {
        Method sleepLong = java.lang.Thread.class.getMethod("sleep", long.class);
        String sleepLongKey = sleepLong.getName() + Type.getMethodDescriptor(sleepLong);

        Method sleepLongInt = java.lang.Thread.class.getMethod("sleep", long.class, int.class);
        String sleepLongIntKey = sleepLongInt.getName() + Type.getMethodDescriptor(sleepLongInt);

        MethodCallReplacementCache cache = MethodCallReplacementCache.getInstance();
        Assertions.assertTrue(cache.hasReplacementCall("java/lang/Thread", sleepLongKey));
        Assertions.assertTrue(cache.hasReplacementCall("java/lang/Thread", sleepLongIntKey));
    }

    @Test
    public void testReflectionAccessReplacementsAreRegisteredForAllFourOwners() {
        MethodCallReplacementCache cache = MethodCallReplacementCache.getInstance();

        String[] owners = {"java/lang/reflect/AccessibleObject", "java/lang/reflect/Field",
                "java/lang/reflect/Method", "java/lang/reflect/Constructor"};
        for (String owner : owners) {
            Assertions.assertTrue(cache.hasReplacementCall(owner, "setAccessible(Z)V"),
                    "Missing setAccessible(boolean) replacement for " + owner);
            Assertions.assertTrue(cache.hasReplacementCall(owner, "trySetAccessible()Z"),
                    "Missing trySetAccessible() replacement for " + owner);
        }

        Assertions.assertTrue(cache.hasReplacementCall("java/lang/reflect/AccessibleObject",
                "setAccessible([Ljava/lang/reflect/AccessibleObject;Z)V"));
    }

    @Test
    public void testCacheFollowsAChangeOfSettings() throws Exception {
        Method console = java.lang.System.class.getMethod("console");
        String consoleKey = console.getName() + Type.getMethodDescriptor(console);

        Assertions.assertTrue(MethodCallReplacementCache.getInstance()
                .hasReplacementCall("java/lang/System", consoleKey));

        // a second test class with other runner parameters, without a reset in between
        RuntimeSettings.mockJVMNonDeterminism = false;
        Assertions.assertFalse(MethodCallReplacementCache.getInstance()
                .hasReplacementCall("java/lang/System", consoleKey));

        RuntimeSettings.mockJVMNonDeterminism = true;
        Assertions.assertTrue(MethodCallReplacementCache.getInstance()
                .hasReplacementCall("java/lang/System", consoleKey));
    }

    @Test
    public void testEachSettingChangesTheFingerprintTheCacheKeysOn() {
        boolean vfs = RuntimeSettings.useVFS;
        boolean vnet = RuntimeSettings.useVNET;
        boolean gui = RuntimeSettings.mockGUI;
        try {
            for (Runnable flip : new Runnable[] {
                    () -> RuntimeSettings.useVFS = !RuntimeSettings.useVFS,
                    () -> RuntimeSettings.mockJVMNonDeterminism = !RuntimeSettings.mockJVMNonDeterminism,
                    () -> RuntimeSettings.useVNET = !RuntimeSettings.useVNET,
                    () -> RuntimeSettings.mockGUI = !RuntimeSettings.mockGUI}) {
                MethodCallReplacementCache cache = MethodCallReplacementCache.getInstance();
                int fingerprint = RuntimeSettings.mockingFingerprint();
                Assertions.assertSame(cache, MethodCallReplacementCache.getInstance());
                flip.run();
                Assertions.assertNotSame(cache, MethodCallReplacementCache.getInstance());
                Assertions.assertNotEquals(fingerprint, RuntimeSettings.mockingFingerprint());
                flip.run();
            }
        } finally {
            RuntimeSettings.useVFS = vfs;
            RuntimeSettings.useVNET = vnet;
            RuntimeSettings.mockGUI = gui;
        }
    }
}
