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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import javax.swing.JComboBox;
import javax.swing.ComboBoxEditor;
import javax.swing.JTextField;
import java.awt.Color;

public class MockHeadlessSwingKeyboardFocusManagerTest {

    @BeforeEach
    public void enableMocking() {
        MockFramework.enable();
    }

    @AfterEach
    public void disableMocking() {
        MockFramework.disable();
    }

    @Test
    public void keyboardFocusManagerStaticCallsAreNeutralizedWhenHeadlessAndMockingEnabled() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        Assertions.assertNull(MockHeadlessSwing.replacement_getCurrentKeyboardFocusManager());
        Assertions.assertDoesNotThrow(() -> MockHeadlessSwing.replacement_setCurrentKeyboardFocusManager(null));
    }

    @Test
    public void comboBoxSelectionIsSafeWhenEditorIsMissing() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        TestEditorComboBox combo = new TestEditorComboBox();
        combo.mode = EditorMode.RETURN_NULL;

        Assertions.assertDoesNotThrow(() -> MockHeadlessSwing.replacement_setSelectedItem(combo, "b"));
        Assertions.assertEquals("b", combo.getSelectedItem());
    }

    @Test
    public void comboBoxGetEditorReturnsNonNullWhenEditorIsMissing() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        TestEditorComboBox combo = new TestEditorComboBox();
        combo.mode = EditorMode.RETURN_NULL;

        ComboBoxEditor editor = Assertions.assertDoesNotThrow(() -> MockHeadlessSwing.replacement_getEditor(combo));
        Assertions.assertNotNull(editor);
    }

    @Test
    public void comboBoxGetEditorReturnsFallbackWhenComboEditorAccessThrows() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        TestEditorComboBox combo = new TestEditorComboBox();
        combo.mode = EditorMode.THROW;

        ComboBoxEditor editor = Assertions.assertDoesNotThrow(() -> MockHeadlessSwing.replacement_getEditor(combo));
        Assertions.assertNotNull(editor);
        Assertions.assertDoesNotThrow(() -> editor.setItem("x"));
    }

    @Test
    public void comboBoxSetSelectedItemIsSafeWhenComboEditorAccessThrows() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        TestEditorComboBox combo = new TestEditorComboBox();
        combo.mode = EditorMode.THROW;

        Assertions.assertDoesNotThrow(() -> MockHeadlessSwing.replacement_setSelectedItem(combo, "x"));
    }

    @Test
    public void comboBoxSetEditableRecoversMissingEditor() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        TestEditorComboBox combo = new TestEditorComboBox();
        combo.mode = EditorMode.RETURN_NULL;

        Assertions.assertDoesNotThrow(() -> MockHeadlessSwing.replacement_setEditable(combo, true));
        ComboBoxEditor editor = Assertions.assertDoesNotThrow(() -> MockHeadlessSwing.replacement_getEditor(combo));
        Assertions.assertNotNull(editor);
    }

    @Test
    public void textComponentCaretIsRecoveredWhenMissing() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        JTextField textField = new JTextField("abc");
        textField.setCaret(null);

        Assertions.assertNotNull(MockHeadlessSwing.replacement_getCaret(textField));
        Assertions.assertDoesNotThrow(() -> MockHeadlessSwing.replacement_setCaretPosition(textField, 2));
    }

    @Test
    public void textComponentSelectionApisAreRecoveredWhenCaretIsMissing() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        JTextField textField = new JTextField("abcdef");
        textField.setCaret(null);

        Assertions.assertDoesNotThrow(() -> MockHeadlessSwing.replacement_moveCaretPosition(textField, 1));
        Assertions.assertDoesNotThrow(() -> MockHeadlessSwing.replacement_setSelectionStart(textField, 0));
        Assertions.assertDoesNotThrow(() -> MockHeadlessSwing.replacement_setSelectionEnd(textField, 3));
        Assertions.assertDoesNotThrow(() -> MockHeadlessSwing.replacement_select(textField, 2, 5));
        Assertions.assertDoesNotThrow(() -> MockHeadlessSwing.replacement_selectAll(textField));
        Assertions.assertNotNull(MockHeadlessSwing.replacement_getCaret(textField));
    }

    @Test
    public void desktopSystemTrayRobotMouseInfoAndColorChooserAreHeadlessSafe() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        Assertions.assertFalse(MockHeadlessSwing.replacement_isDesktopSupported());
        Assertions.assertNull(MockHeadlessSwing.replacement_getDesktop());
        Assertions.assertFalse(MockHeadlessSwing.replacement_isSystemTraySupported());
        Assertions.assertNull(MockHeadlessSwing.replacement_getSystemTray());
        Assertions.assertNull(MockHeadlessSwing.replacement_getPointerInfo());
        Assertions.assertNull(MockHeadlessSwing.replacement_showColorDialog(null, "t", Color.RED));
        Assertions.assertNull(MockHeadlessSwing.replacement_newRobot());
        Assertions.assertNull(MockHeadlessSwing.replacement_newRobot(null));
    }

    @Test
    public void graphicsEnvironmentReplacementIsNonNullWhenHeadlessAndMockingEnabled() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        Assertions.assertNotNull(MockHeadlessSwing.replacement_getLocalGraphicsEnvironment());
        Assertions.assertNotNull(
                MockHeadlessSwing.replacement_getLocalGraphicsEnvironment().getDefaultScreenDevice());
    }

    /**
     * Reproduces the JUnit-recheck scenario that produced
     * {@code AWTError: Local GraphicsEnvironment must not be null} on JDK 17.0.10+:
     * EvoSuite has flipped headless to false via
     * {@link GuiSupport#disableHeadlessForMockConstruction()} for the duration of
     * the recheck. The instrumented SUT call to
     * {@code GraphicsEnvironment.getLocalGraphicsEnvironment()} must still return
     * the safe stub instead of falling through to the JDK call, which can hit
     * a null cached singleton.
     */
    @Test
    public void graphicsEnvironmentReplacementStaysSafeDuringMockConstructionDisable() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        // macOS: disableHeadlessForMockConstruction is a deliberate no-op, so
        // headless stays true and this scenario does not apply.
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("mac"));

        GuiSupport.disableHeadlessForMockConstruction();
        try {
            // Sanity: EvoSuite has temporarily disabled headless. The
            // replacement must still hand back the EvoSuite-controlled stub
            // and not fall through to the real JDK GraphicsEnvironment.
            Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
            Assertions.assertTrue(GuiSupport.isHeadlessTemporarilyDisabledForMockConstruction());
            GraphicsEnvironment ge = MockHeadlessSwing.replacement_getLocalGraphicsEnvironment();
            Assertions.assertNotNull(ge);
            Assertions.assertNotNull(ge.getDefaultScreenDevice());
            Assertions.assertNotNull(ge.getDefaultScreenDevice().getDefaultConfiguration());
        } finally {
            GuiSupport.restoreHeadlessAfterMockConstruction();
        }
    }

    @Test
    public void toolkitReplacementDoesNotThrowWhenHeadlessAndMockingEnabled() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless());
        Toolkit toolkit = Assertions.assertDoesNotThrow(MockHeadlessSwing::replacement_getDefaultToolkit);
        // Depending on JVM internals this may still be null, but replacement must stay non-throwing.
        if (toolkit != null) {
            Assertions.assertDoesNotThrow(toolkit::toString);
        }
    }

    private enum EditorMode {
        NORMAL,
        RETURN_NULL,
        THROW
    }

    private static final class TestEditorComboBox extends JComboBox<String> {
        private EditorMode mode = EditorMode.NORMAL;

        private TestEditorComboBox() {
            super(new String[]{"a", "b"});
        }

        @Override
        public boolean isEditable() {
            return true;
        }

        @Override
        public ComboBoxEditor getEditor() {
            if (mode == EditorMode.THROW) {
                throw new RuntimeException("simulated getEditor failure");
            }
            if (mode == EditorMode.RETURN_NULL) {
                return null;
            }
            return super.getEditor();
        }

        @Override
        public void setEditor(ComboBoxEditor anEditor) {
            if (mode == EditorMode.THROW) {
                throw new RuntimeException("simulated setEditor failure");
            }
            if (mode == EditorMode.RETURN_NULL) {
                return;
            }
            super.setEditor(anEditor);
        }

    }
}
