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

import static org.evosuite.eclipse.preferences.SettingsEnablementTest.control;
import static org.evosuite.eclipse.preferences.SettingsEnablementTest.page;
import static org.evosuite.eclipse.preferences.SettingsEnablementTest.select;
import static org.evosuite.eclipse.preferences.SettingsPagesLayoutTest.onUiThread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Text;
import org.evosuite.eclipse.preferences.SettingsFields.Section;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The editors show hints in empty fields and store what the user means by them. */
public class SettingsEditorsTest {

    private final IEclipsePreferences workspace = InstanceScope.INSTANCE.getNode(EvoSuitePreferences.QUALIFIER);

    @AfterEach
    public void clear() throws Exception {
        workspace.clear();
    }

    @Test
    public void theModelAndBaseUrlHintsFollowTheProvider() {
        onUiThread(() -> {
            PreferenceDialog dialog = SettingsDialogs.preferences(Section.LLM);
            try {
                Composite page = page(dialog);
                Text model = (Text) control(page, "Model");
                Text baseUrl = (Text) control(page, "Base URL");
                select(page, "Provider", "Ollama (local)");
                assertEquals("e.g. qwen3-coder", model.getMessage());
                assertEquals("http://localhost:11434", baseUrl.getMessage());
                select(page, "Provider", "Anthropic");
                assertEquals("e.g. claude-sonnet-5-5", model.getMessage());
                assertEquals("Provider default", baseUrl.getMessage());
                select(page, "Provider", "OpenAI");
                assertEquals("gpt-4o (EvoSuite default)", model.getMessage());
            } finally {
                dialog.close();
            }
        });
    }

    @Test
    public void anEmptyNumberIsEvoSuitesDefaultAndShowsItsHint() {
        workspace.putInt(EvoSuitePreferences.TEST_TIMEOUT, 500);
        onUiThread(() -> {
            PreferenceDialog dialog = SettingsDialogs.preferences(Section.GENERAL);
            try {
                Text timeout = (Text) control(page(dialog), "Time limit per test");
                assertEquals("500", timeout.getText());
                assertEquals("EvoSuite default (3000)", timeout.getMessage());
                type(timeout, "");
                assertTrue(((EvoSuitePreferencePage) dialog.getSelectedPage()).isValid());
                SettingsDialogs.ok(dialog);
            } finally {
                dialog.close();
            }
        });
        // The store removes a value equal to the default.
        assertEquals(null, workspace.get(EvoSuitePreferences.TEST_TIMEOUT, null));

        onUiThread(() -> {
            PreferenceDialog dialog = SettingsDialogs.preferences(Section.GENERAL);
            try {
                Text timeout = (Text) control(page(dialog), "Time limit per test");
                // 0 shows as an empty field, with the hint.
                assertEquals("", timeout.getText());
                type(timeout, "x");
                assertFalse(((EvoSuitePreferencePage) dialog.getSelectedPage()).isValid());
                type(timeout, "250");
                SettingsDialogs.ok(dialog);
            } finally {
                dialog.close();
            }
        });
        assertEquals(250, workspace.getInt(EvoSuitePreferences.TEST_TIMEOUT, -1));
    }

    @Test
    public void allDefaultCriteriaAreEvoSuitesDefaultAndOtherEntriesSurvive() {
        Set<String> all = CriteriaFieldEditor.DEFAULTS.keySet();
        assertEquals(List.of(), CriteriaFieldEditor.parse(""));
        assertEquals("", CriteriaFieldEditor.format(List.of(), all, List.of()));
        assertEquals("", CriteriaFieldEditor.format(CriteriaFieldEditor.parse(String.join(":", all)), all,
                List.of()));
        assertEquals("LINE:BRANCH", CriteriaFieldEditor.format(List.of(), Set.of("BRANCH", "LINE"), List.of()));
        // The order and spelling of a typed list are kept, as far as the criteria are known.
        List<String> typed = CriteriaFieldEditor.parse(" branch : StrongMutation:line:branch ");
        assertEquals(List.of("BRANCH", "StrongMutation", "LINE"), typed);
        assertEquals("BRANCH:StrongMutation:LINE",
                CriteriaFieldEditor.format(typed, Set.of("LINE", "BRANCH"), List.of("StrongMutation")));
        assertEquals("BRANCH:StrongMutation:METHOD",
                CriteriaFieldEditor.format(typed, Set.of("BRANCH", "METHOD"), List.of("StrongMutation")));
        // With another criterion, ticking all default ones is not EvoSuite's default.
        assertEquals("LINE:STRONGMUTATION:BRANCH:EXCEPTION:WEAKMUTATION:OUTPUT:METHOD:METHODNOEXCEPTION:CBRANCH",
                CriteriaFieldEditor.format(List.of("LINE", "STRONGMUTATION"), all, List.of("STRONGMUTATION")));
    }

    @Test
    public void theCriteriaCheckboxesEditTheStoredList() {
        workspace.put(EvoSuitePreferences.CRITERION, "line:STRONGMUTATION");
        onUiThread(() -> {
            PreferenceDialog dialog = SettingsDialogs.preferences(Section.GENERAL);
            try {
                Composite page = page(dialog);
                assertEquals(List.of("Lines"), ticked(page));
                Link others = (Link) control(page, "Also STRONGMUTATION");
                assertTrue(others.isVisible());
                click(page, "Branches");
                SettingsDialogs.ok(dialog);
            } finally {
                dialog.close();
            }
        });
        assertEquals("LINE:STRONGMUTATION:BRANCH", workspace.get(EvoSuitePreferences.CRITERION, null));

        onUiThread(() -> {
            PreferenceDialog dialog = SettingsDialogs.preferences(Section.GENERAL);
            try {
                Composite page = page(dialog);
                Link others = (Link) control(page, "Also STRONGMUTATION");
                others.notifyListeners(SWT.Selection, new Event());
                assertFalse(others.isVisible());
                for (String name : CriteriaFieldEditor.DEFAULTS.values()) {
                    if (!ticked(page).contains(name)) {
                        click(page, name);
                    }
                }
                SettingsDialogs.ok(dialog);
            } finally {
                dialog.close();
            }
        });
        // All of EvoSuite's default criteria are its default, which the store does not keep.
        assertEquals(null, workspace.get(EvoSuitePreferences.CRITERION, null));

        onUiThread(() -> {
            PreferenceDialog dialog = SettingsDialogs.preferences(Section.GENERAL);
            try {
                Composite page = page(dialog);
                EvoSuitePreferencePage preferences = (EvoSuitePreferencePage) dialog.getSelectedPage();
                assertEquals(List.copyOf(CriteriaFieldEditor.DEFAULTS.values()), ticked(page));
                CriteriaFieldEditor.DEFAULTS.values().forEach(name -> click(page, name));
                assertFalse(preferences.isValid());
                assertEquals("Select at least one coverage criterion.", preferences.getErrorMessage());
                SettingsDialogs.restoreDefaults(dialog);
                assertTrue(preferences.isValid());
                assertEquals(List.copyOf(CriteriaFieldEditor.DEFAULTS.values()), ticked(page));
            } finally {
                dialog.close();
            }
        });
    }

    /** The names of the ticked criteria. */
    private static List<String> ticked(Composite page) {
        return CriteriaFieldEditor.DEFAULTS.values().stream()
                .filter(name -> ((Button) control(page, name)).getSelection())
                .toList();
    }

    private static void click(Composite page, String label) {
        Button button = (Button) control(page, label);
        button.setSelection(!button.getSelection());
        button.notifyListeners(SWT.Selection, new Event());
        SettingsDialogs.pump();
    }

    /** Replaces the text as typing does; the editors check what is typed when a key goes up. */
    private static void type(Text text, String value) {
        text.setText(value);
        text.notifyListeners(SWT.KeyUp, new Event());
        SettingsDialogs.pump();
    }
}
