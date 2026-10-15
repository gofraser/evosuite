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

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.evosuite.eclipse.TestProjects;
import org.evosuite.eclipse.preferences.SettingsFields.Section;
import org.junit.jupiter.api.Test;

/**
 * The settings pages are registered where a user looks for them and lay their editors out where
 * a user can see them. A unit test of the editors alone missed that the groups holding them had
 * no size, which left the pages empty, so these tests open the pages in the workbench's dialogs.
 */
public class SettingsPagesLayoutTest {

    /**
     * Larger pages would not fit on a laptop screen with the dialog's tree and buttons. The
     * height leaves room for the dialog's title, description and buttons below 800 pixels.
     */
    private static final int MAX_PAGE_WIDTH = 700;
    private static final int MAX_PAGE_HEIGHT = 650;

    @Test
    public void everyWorkspacePageShowsEveryEditor() throws Exception {
        onUiThread(() -> {
            // Budget, test folder, suffix and time limit.
            assertPage(SettingsDialogs.preferences(Section.GENERAL), EvoSuitePreferencePage.class, 4, "general");
            // Model, base URL, API key and calls.
            assertPage(SettingsDialogs.preferences(Section.LLM), LlmPreferencePage.class, 4, "llm");
            // Java home, heap and arguments.
            assertPage(SettingsDialogs.preferences(Section.ADVANCED), AdvancedPreferencePage.class, 3, "advanced");
        });
    }

    @Test
    public void everyProjectPageShowsEveryEditor() throws Exception {
        IProject project = TestProjects.create("layout", "src", null).getProject();
        onUiThread(() -> {
            assertPage(SettingsDialogs.properties(project, Section.GENERAL), EvoSuitePropertyPage.class, 4,
                    "project-general");
            // The project pages leave out the API key, which belongs to the workspace.
            assertPage(SettingsDialogs.properties(project, Section.LLM), LlmPropertyPage.class, 3, "project-llm");
            assertPage(SettingsDialogs.properties(project, Section.ADVANCED), AdvancedPropertyPage.class, 3,
                    "project-advanced");
        });
    }

    private static void assertPage(PreferenceDialog dialog, Class<?> type, int textFields, String screenshot) {
        try {
            IPreferencePage page = (IPreferencePage) dialog.getSelectedPage();
            assertEquals(type, page.getClass());
            String name = type.getSimpleName();
            Point preferred = page.getControl().computeSize(SWT.DEFAULT, SWT.DEFAULT);
            assertTrue(preferred.x < MAX_PAGE_WIDTH && preferred.y < MAX_PAGE_HEIGHT, name + " is " + preferred);

            // The dialog remembers its size from the last time it was open, so it gets the size
            // it would like before the editors are checked.
            SettingsDialogs.fit(dialog);
            SettingsDialogs.screenshot(dialog, screenshot);
            List<Control> inputs = new ArrayList<>();
            collectInputs((Composite) page.getControl(), inputs);
            List<String> invisible = new ArrayList<>();
            // The page is as large as it wants to be; the dialog cuts off what does not fit.
            Shell shell = dialog.getShell();
            Rectangle visible = shell.getClientArea();
            for (Control input : inputs) {
                Point size = input.getSize();
                Rectangle bounds = shell.getDisplay().map(input.getParent(), shell, input.getBounds());
                if (size.x <= 0 || size.y <= 0 || !input.isVisible() || !visible.contains(bounds.x + bounds.width - 1,
                        bounds.y + bounds.height - 1)) {
                    invisible.add(input.getClass().getSimpleName() + " " + input.getBounds());
                }
            }
            assertEquals(List.of(), invisible, "editors without room on, or sticking out of, " + name);
            assertEquals(textFields, inputs.stream().filter(c -> c instanceof Text).count(), "text fields on " + name);
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

    static void onUiThread(Runnable test) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Display.getDefault().syncExec(() -> {
            try {
                test.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }
}
