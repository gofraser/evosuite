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

import static org.evosuite.eclipse.preferences.SettingsPagesLayoutTest.onUiThread;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.preference.PreferenceDialog;
import org.evosuite.eclipse.TestProjects;
import org.evosuite.eclipse.preferences.SettingsFields.Section;
import org.junit.jupiter.api.Test;

/** The project property pages share one switch for project-specific settings. */
public class ProjectSettingsSwitchTest {

    @Test
    public void tickingTheSwitchOnOnePageTicksItOnAll() throws Exception {
        IProject project = TestProjects.create("switch", "src", null).getProject();
        onUiThread(() -> {
            PreferenceDialog dialog = SettingsDialogs.properties(project, Section.GENERAL);
            List<EvoSuitePropertyPage> pages = allPages(dialog);
            pages.forEach(page -> assertFalse(page.usesProjectSettings() || page.settingsEnabled()));

            // Ticked on a sub-page, while the first page, which the dialog stores first, is open too.
            pages.get(1).setUseProjectSettings(true);
            pages.forEach(page -> assertTrue(page.usesProjectSettings() && page.settingsEnabled(),
                    page.getClass().getSimpleName()));
            SettingsDialogs.ok(dialog);
        });
        assertTrue(EvoSuitePreferences.usesProjectSettings(project));

        onUiThread(() -> {
            PreferenceDialog dialog = SettingsDialogs.properties(project, Section.ADVANCED);
            List<EvoSuitePropertyPage> pages = allPages(dialog);
            pages.forEach(page -> assertTrue(page.usesProjectSettings() && page.settingsEnabled()));
            pages.get(2).setUseProjectSettings(false);
            pages.forEach(page -> assertFalse(page.usesProjectSettings() || page.settingsEnabled(),
                    page.getClass().getSimpleName()));
            SettingsDialogs.ok(dialog);
        });
        assertFalse(EvoSuitePreferences.usesProjectSettings(project));
    }

    @Test
    public void restoringTheDefaultsOnOnePageSwitchesAllOff() throws Exception {
        IProject project = TestProjects.create("switchDefaults", "src", null).getProject();
        onUiThread(() -> {
            PreferenceDialog dialog = SettingsDialogs.properties(project, Section.GENERAL);
            try {
                List<EvoSuitePropertyPage> pages = allPages(dialog);
                pages.get(0).setUseProjectSettings(true);
                pages.get(1).performDefaults();
                pages.forEach(page -> assertFalse(page.usesProjectSettings() || page.settingsEnabled(),
                        page.getClass().getSimpleName()));
            } finally {
                dialog.close();
            }
        });
    }

    /** The three pages, each shown once so that the dialog creates it. */
    private static List<EvoSuitePropertyPage> allPages(PreferenceDialog dialog) {
        return List.of(Section.values()).stream()
                .map(section -> (EvoSuitePropertyPage) SettingsDialogs.show(dialog, section.propertyPageId))
                .toList();
    }
}
