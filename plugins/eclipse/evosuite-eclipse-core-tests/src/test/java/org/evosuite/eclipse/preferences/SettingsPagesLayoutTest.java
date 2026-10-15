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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.preference.PreferenceManager;
import org.eclipse.jface.preference.PreferenceNode;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Text;
import org.evosuite.eclipse.TestProjects;
import org.junit.jupiter.api.Test;

/**
 * The settings pages lay their editors out where a user can see them. A unit test of the
 * editors alone missed that the groups holding them had no size, which left both pages empty.
 */
public class SettingsPagesLayoutTest {

    @Test
    public void bothPagesShowEveryEditor() throws Exception {
        IJavaProject project = TestProjects.create("layout", "src", null);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Display.getDefault().syncExec(() -> {
            try {
                EvoSuitePreferencePage preferences = new EvoSuitePreferencePage();
                preferences.init(null);
                // Search budget, criteria, test folder, suffix, model, base URL, API key, calls,
                // heap, Java home, arguments; the property page has no API key.
                assertVisible(preferences, 11);
                EvoSuitePropertyPage properties = new EvoSuitePropertyPage();
                properties.setElement(project.getProject());
                assertVisible(properties, 10);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private static void assertVisible(IPreferencePage page, int textFields) {
        PreferenceManager manager = new PreferenceManager();
        manager.addToRoot(new PreferenceNode("page", page));
        PreferenceDialog dialog = new PreferenceDialog(null, manager);
        dialog.setBlockOnOpen(false);
        dialog.open();
        try {
            Display display = dialog.getShell().getDisplay();
            while (display.readAndDispatch()) {
                // let the dialog lay itself out
            }
            List<Control> inputs = new ArrayList<>();
            collectInputs((Composite) page.getControl(), inputs);
            List<String> invisible = new ArrayList<>();
            for (Control input : inputs) {
                Point size = input.getSize();
                if (size.x <= 0 || size.y <= 0 || !input.isVisible()) {
                    invisible.add(input.getClass().getSimpleName() + " " + input.getBounds());
                }
            }
            assertEquals(List.of(), invisible, "editors without room on " + page.getClass().getSimpleName());
            assertEquals(textFields, inputs.stream().filter(c -> c instanceof Text).count(),
                    "text fields on " + page.getClass().getSimpleName());
            assertTrue(inputs.stream().anyMatch(c -> c instanceof Combo), "no combo boxes");
        } finally {
            dialog.close();
        }
    }

    private static void collectInputs(Composite parent, List<Control> inputs) {
        for (Control child : parent.getChildren()) {
            if (child instanceof Text || child instanceof Combo
                    || (child instanceof Button && ((Button) child).getText().length() > 0)) {
                inputs.add(child);
            } else if (child instanceof Composite composite) {
                collectInputs(composite, inputs);
            }
        }
    }
}
