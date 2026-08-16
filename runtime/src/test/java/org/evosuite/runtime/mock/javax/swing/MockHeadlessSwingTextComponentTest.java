/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.javax.swing;

import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.MockFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JMenuBar;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.UIManager;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The mocked Swing text components used to be created without a UI delegate,
 * which left font and caret null; SUT constructors that read {@code getFont()}
 * then died with an NPE, and {@code JTextArea(String, ...)} even failed inside
 * the replacement itself. These tests pin the populated state down.
 */
class MockHeadlessSwingTextComponentTest {

    private boolean defaultMockGui;
    private boolean defaultMockJvmNonDeterminism;
    private boolean defaultMockFrameworkEnabled;

    @BeforeEach
    void enableGuiMocking() {
        defaultMockGui = RuntimeSettings.mockGUI;
        defaultMockJvmNonDeterminism = RuntimeSettings.mockJVMNonDeterminism;
        defaultMockFrameworkEnabled = MockFramework.isEnabled();

        RuntimeSettings.mockGUI = true;
        MockFramework.enable();
    }

    @AfterEach
    void restoreRuntimeState() {
        RuntimeSettings.mockGUI = defaultMockGui;
        RuntimeSettings.mockJVMNonDeterminism = defaultMockJvmNonDeterminism;
        if (defaultMockFrameworkEnabled) {
            MockFramework.enable();
        } else {
            MockFramework.disable();
        }
    }

    @Test
    void mockedTextAreaHasFontAndCaret() {
        JTextArea area = MockHeadlessSwing.replacement_newJTextArea();

        assertNotNull(area);
        assertNotNull(area.getFont(), "SUTs read getFont().getSize() from their constructors");
        assertNotNull(area.getCaret());
    }

    @Test
    void mockedTextAreaWithTextIsConstructedAndPopulated() {
        // These two used to throw an NPE from select(0, 0) on a null caret.
        JTextArea fromText = MockHeadlessSwing.replacement_newJTextArea("hello");
        assertNotNull(fromText.getFont());
        assertEquals("hello", fromText.getText());

        JTextArea sized = MockHeadlessSwing.replacement_newJTextArea("hello", 3, 4);
        assertNotNull(sized.getFont());
        assertNotNull(sized.getCaret());
        assertEquals("hello", sized.getText());
        assertEquals(3, sized.getRows());
        assertEquals(4, sized.getColumns());
    }

    @Test
    void mockedTextAreaFromRowsAndDocumentHasFont() {
        assertNotNull(MockHeadlessSwing.replacement_newJTextArea(2, 3).getFont());
        assertNotNull(MockHeadlessSwing.replacement_newJTextArea((javax.swing.text.Document) null)
                .getFont());
        assertNotNull(MockHeadlessSwing
                .replacement_newJTextArea(null, "text", 2, 3).getFont());
    }

    @Test
    void mockedTextFieldHasFontAndCaret() {
        JTextField field = MockHeadlessSwing.replacement_newJTextField("hi", 5);

        assertNotNull(field.getFont());
        assertNotNull(field.getCaret());
        assertEquals("hi", field.getText());
    }

    @Test
    void mockedMenuBarHasFont() {
        JMenuBar menuBar = MockHeadlessSwing.replacement_newJMenuBar();

        assertNotNull(menuBar);
        assertNotNull(menuBar.getFont());
    }

    @Test
    void mockedComponentsSurviveAnUnusableLookAndFeel() {
        // Simulate the hostile GUI environment the mock exists for: no UI delegate
        // class for these components. Real Swing then leaves font and caret unset,
        // and JTextArea(String, int, int) dies in its own constructor.
        Object textAreaUi = UIManager.get("TextAreaUI");
        Object textFieldUi = UIManager.get("TextFieldUI");
        Object menuBarUi = UIManager.get("MenuBarUI");
        PrintStream systemErr = System.err;
        try {
            // UIManager reports the missing delegate by printing a stack trace.
            System.setErr(new PrintStream(new ByteArrayOutputStream()));
            UIManager.put("TextAreaUI", "org.evosuite.NoSuchTextAreaUI");
            UIManager.put("TextFieldUI", "org.evosuite.NoSuchTextFieldUI");
            UIManager.put("MenuBarUI", "org.evosuite.NoSuchMenuBarUI");

            JTextArea area = MockHeadlessSwing.replacement_newJTextArea("hello", 3, 4);
            assertNotNull(area.getFont());
            assertNotNull(area.getCaret());
            assertEquals("hello", area.getText());

            JTextField field = MockHeadlessSwing.replacement_newJTextField("hi", 5);
            assertNotNull(field.getFont());
            assertNotNull(field.getCaret());

            assertNotNull(MockHeadlessSwing.replacement_newJMenuBar().getFont());
        } finally {
            UIManager.put("TextAreaUI", textAreaUi);
            UIManager.put("TextFieldUI", textFieldUi);
            UIManager.put("MenuBarUI", menuBarUi);
            System.setErr(systemErr);
        }
    }

    @Test
    void guiMockingCanBeSwitchedOff() {
        // -Dreplace_gui=false is mirrored into RuntimeSettings.mockGUI and has to
        // hand out plain Swing components again.
        assertNotSame(JTextArea.class, MockHeadlessSwing.replacement_newJTextArea("x").getClass());

        RuntimeSettings.mockGUI = false;

        assertSame(JTextArea.class, MockHeadlessSwing.replacement_newJTextArea("x").getClass());
        assertSame(JTextField.class, MockHeadlessSwing.replacement_newJTextField("x").getClass());
        assertSame(JMenuBar.class, MockHeadlessSwing.replacement_newJMenuBar().getClass());
    }
}
