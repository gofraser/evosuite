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
package org.evosuite.eclipse.preferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.preference.IPreferenceNode;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.preference.PreferenceManager;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.evosuite.eclipse.preferences.SettingsFields.Section;

/**
 * Opens the settings pages the way a user does, in the workbench's preference and property
 * dialogs, so that tests see the pages as registered in plugin.xml and laid out for real.
 *
 * <p>If the environment variable {@code EVOSUITE_SCREENSHOTS} names a directory, every page
 * shown is also saved there as a PNG, for checking the layout by eye.
 */
final class SettingsDialogs {

    private SettingsDialogs() {
    }

    /** Opens the workspace preferences on a page; call on the UI thread. */
    static PreferenceDialog preferences(Section section) {
        return open(PreferencesUtil.createPreferenceDialogOn(null, section.preferencePageId, null, null));
    }

    /** Opens the properties of {@code project} on a page; call on the UI thread. */
    static PreferenceDialog properties(IProject project, Section section) {
        return open(PreferencesUtil.createPropertyDialogOn(null, project, section.propertyPageId, null, null));
    }

    private static PreferenceDialog open(PreferenceDialog dialog) {
        dialog.setBlockOnOpen(false);
        dialog.open();
        pump();
        return dialog;
    }

    /** Selects a page in the dialog's tree, as a click on it does, and returns the page. */
    static IPreferencePage show(PreferenceDialog dialog, String pageId) {
        IPreferenceNode node = null;
        for (IPreferenceNode candidate : dialog.getPreferenceManager().getElements(PreferenceManager.PRE_ORDER)) {
            if (pageId.equals(candidate.getId())) {
                node = candidate;
            }
        }
        if (node == null) {
            throw new AssertionError("no page " + pageId);
        }
        dialog.getTreeViewer().setSelection(new StructuredSelection(node), true);
        pump();
        return (IPreferencePage) dialog.getSelectedPage();
    }

    /** Presses the dialog's OK button, "Apply and Close". */
    static void ok(PreferenceDialog dialog) {
        Button ok = buttons(dialog.getShell()).stream()
                .filter(b -> Integer.valueOf(IDialogConstants.OK_ID).equals(b.getData()))
                .findFirst().orElseThrow();
        ok.notifyListeners(SWT.Selection, new Event());
        pump();
    }

    /** Saves the dialog as {@code name}.png if screenshots are asked for. */
    static void screenshot(PreferenceDialog dialog, String name) {
        String directory = System.getenv("EVOSUITE_SCREENSHOTS");
        if (directory == null || directory.isBlank()) {
            return;
        }
        Shell shell = dialog.getShell();
        Point size = shell.getSize();
        Image image = new Image(shell.getDisplay(), size.x, size.y);
        GC gc = new GC(image);
        try {
            shell.print(gc);
        } finally {
            gc.dispose();
        }
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[]{image.getImageData()};
        new File(directory).mkdirs();
        loader.save(new File(directory, name + ".png").getPath(), SWT.IMAGE_PNG);
        image.dispose();
    }

    static void pump() {
        Display display = Display.getCurrent();
        while (display.readAndDispatch()) {
            // let the dialog lay itself out
        }
    }

    static List<Button> buttons(Composite parent) {
        List<Button> result = new ArrayList<>();
        for (Control child : parent.getChildren()) {
            if (child instanceof Button button) {
                result.add(button);
            } else if (child instanceof Composite composite) {
                result.addAll(buttons(composite));
            }
        }
        return result;
    }
}
