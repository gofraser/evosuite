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
package org.evosuite.eclipse.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.evosuite.eclipse.UiThread;
import org.evosuite.eclipse.launch.SuiteAction;
import org.junit.jupiter.api.Test;

/** The question about existing suites, and the dialog that asks it. */
public class ExistingSuitesQuestionTest {

    @Test
    public void asksAboutOneSuiteAndPreselectsReplace() {
        ExistingSuitesQuestion question = ExistingSuitesQuestion.of(List.of("Calculator"), 1, "src/test/evosuite");

        assertEquals("Existing Test Suite", question.title);
        assertTrue(question.message.startsWith("Calculator already has a test suite in src/test/evosuite.\n\n"),
                question.message);
        assertTrue(question.message.contains("Replace: overwrite it with the new suite."), question.message);
        assertFalse(question.message.contains("choice applies"), question.message);
        assertEquals(SuiteAction.REPLACE, question.defaultAction);
        assertEquals(0, question.defaultButton());
    }

    @Test
    public void asksOnceForAllClassesAndAbbreviatesLongLists() {
        List<String> classes = List.of("A", "B", "C", "D", "E", "F", "G");
        ExistingSuitesQuestion question = ExistingSuitesQuestion.of(classes, 9, null);

        assertEquals("Existing Test Suites", question.title);
        assertTrue(question.message.startsWith("7 of the 9 classes already have test suites: A, B, C, D and 3 more."),
                question.message);
        assertTrue(question.message.endsWith("The choice applies to all 7; the other classes get new suites."),
                question.message);
    }

    @Test
    public void mapsButtonsToActions() {
        assertEquals(SuiteAction.REPLACE, ExistingSuitesQuestion.action(0));
        assertEquals(SuiteAction.ADD, ExistingSuitesQuestion.action(1));
        assertEquals(SuiteAction.COMPARE, ExistingSuitesQuestion.action(2));
        assertNull(ExistingSuitesQuestion.action(3), "Cancel");
        assertNull(ExistingSuitesQuestion.action(SWT.DEFAULT), "closed with Escape");
    }

    /**
     * The dialog shows the four answers with the preselected one as the default button. Set
     * {@code EVOSUITE_SCREENSHOTS} to a folder to get an image of it.
     */
    @Test
    public void dialogOffersTheFourAnswers() throws Exception {
        ExistingSuitesQuestion question = ExistingSuitesQuestion.of(List.of("Calculator", "Parser", "Lexer"), 5,
                "src/test/evosuite");
        UiThread.run(() -> {
            ExistingSuitesDialog dialog = new ExistingSuitesDialog(
                    PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), question);
            dialog.setBlockOnOpen(false);
            dialog.open();
            try {
                Shell shell = dialog.getShell();
                UiThread.drain();
                List<String> labels = new ArrayList<>();
                collectButtons(shell, labels);
                // In any order: on macOS the default button moves to the right.
                assertEquals(Set.of("&Replace", "&Add New Tests", "C&ompare", "Cancel"), Set.copyOf(labels));
                assertEquals(4, labels.size());
                assertEquals("&Replace", shell.getDefaultButton().getText());
                String folder = System.getenv("EVOSUITE_SCREENSHOTS");
                if (folder != null && !folder.isEmpty()) {
                    UiThread.drain(1000);
                    save(shell, new File(folder, "existing-suites-dialog.png"));
                }
            } finally {
                dialog.close();
            }
        });
    }

    private static void collectButtons(Composite parent, List<String> labels) {
        for (Control child : parent.getChildren()) {
            if (child instanceof Button button && (button.getStyle() & SWT.PUSH) != 0) {
                labels.add(button.getText());
            } else if (child instanceof Composite composite) {
                collectButtons(composite, labels);
            }
        }
    }

    /**
     * Saves what the screen shows of {@code shell} as a PNG file. Copied from the screen because
     * {@code Shell.print} leaves a dialog's labels out on macOS.
     */
    public static void save(Shell shell, File file) {
        file.getParentFile().mkdirs();
        Rectangle bounds = shell.getBounds();
        Image image = new Image(shell.getDisplay(), bounds.width, bounds.height);
        GC gc = new GC(shell.getDisplay());
        try {
            gc.copyArea(image, bounds.x, bounds.y);
            ImageLoader loader = new ImageLoader();
            loader.data = new ImageData[]{image.getImageData()};
            loader.save(file.getAbsolutePath(), SWT.IMAGE_PNG);
        } finally {
            gc.dispose();
            image.dispose();
        }
    }
}
