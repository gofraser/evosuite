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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.evosuite.eclipse.TestProjects;
import org.evosuite.eclipse.launch.GenerationOptions;
import org.evosuite.eclipse.preferences.SettingsFields.Section;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Settings that do not apply, given the values of others, are disabled as the user changes the
 * others, when the page opens and after the defaults are restored. The tests look at the widgets
 * a user sees, found by their labels.
 */
public class SettingsEnablementTest {

    private static final String PROVIDER = "Provider";
    private static final String APPROACH = "Approach";
    private static final String MODEL = "Model";
    private static final String KEY = "API key";
    private static final String SEEDING = "Seed the search";
    private static final String STAGNATION = "Ask the LLM for tests when the search stagnates";
    private static final String OPERATORS = "Use LLM-based mutation and crossover";
    private static final String POSTPROCESSING = "Improve test names, comments and assertions with the LLM";
    private static final String CALLS = "LLM calls per class";

    private static final List<String> BEYOND_PROVIDER = List.of(APPROACH, MODEL, "Base URL", KEY, SEEDING,
            STAGNATION, OPERATORS, POSTPROCESSING, CALLS);
    private static final List<String> SEARCH_INTEGRATION = List.of(SEEDING, STAGNATION, OPERATORS);

    private final IEclipsePreferences workspace = InstanceScope.INSTANCE.getNode(EvoSuitePreferences.QUALIFIER);

    @AfterEach
    public void clear() throws Exception {
        workspace.clear();
    }

    @Test
    public void withoutAProviderOnlyTheProviderIsEnabled() {
        onUiThread(() -> {
            PreferenceDialog dialog = SettingsDialogs.preferences(Section.LLM);
            try {
                Composite page = page(dialog);
                assertEnabled(page, true, PROVIDER);
                assertEnabled(page, false, BEYOND_PROVIDER);

                select(page, PROVIDER, "Anthropic");
                assertEnabled(page, true, BEYOND_PROVIDER);
                ((Text) control(page, MODEL)).setText("claude-sonnet-5-5");

                select(page, PROVIDER, "None (no LLM)");
                assertEnabled(page, false, BEYOND_PROVIDER);
                // Disabled settings keep their values.
                assertEquals("claude-sonnet-5-5", ((Text) control(page, MODEL)).getText());
            } finally {
                dialog.close();
            }
        });
    }

    @Test
    public void searchOnlySwitchesTheRestOfTheLlmSettingsOff() {
        onUiThread(() -> {
            PreferenceDialog dialog = SettingsDialogs.preferences(Section.LLM);
            try {
                Composite page = page(dialog);
                select(page, PROVIDER, "OpenAI");
                select(page, APPROACH, "Search only (LLM off, settings kept)");
                assertEnabled(page, true, PROVIDER, APPROACH);
                assertEnabled(page, false, without(BEYOND_PROVIDER, APPROACH));
            } finally {
                dialog.close();
            }
        });
    }

    @Test
    public void llmOnlyApproachesSwitchTheSearchIntegrationOff() {
        onUiThread(() -> {
            PreferenceDialog dialog = SettingsDialogs.preferences(Section.LLM);
            try {
                Composite page = page(dialog);
                select(page, PROVIDER, "OpenAI");
                for (String llmOnly : List.of("LLM only, one prompt", "LLM only, iterating on uncovered goals")) {
                    select(page, APPROACH, llmOnly);
                    assertEnabled(page, false, SEARCH_INTEGRATION);
                    assertEnabled(page, true, MODEL, KEY, POSTPROCESSING, CALLS);
                }
                select(page, APPROACH, "Search, assisted by the LLM");
                assertEnabled(page, true, SEARCH_INTEGRATION);
            } finally {
                dialog.close();
            }
        });
    }

    @Test
    public void onlyOpenAiAndAnthropicTakeAnApiKey() {
        onUiThread(() -> {
            PreferenceDialog dialog = SettingsDialogs.preferences(Section.LLM);
            try {
                Composite page = page(dialog);
                Map<String, Boolean> expected = new LinkedHashMap<>();
                expected.put("OpenAI", true);
                expected.put("Ollama (local)", false);
                expected.put("Anthropic", true);
                expected.put("None (no LLM)", false);
                expected.forEach((provider, takesKey) -> {
                    select(page, PROVIDER, provider);
                    assertEnabled(page, takesKey, KEY);
                });
            } finally {
                dialog.close();
            }
        });
    }

    @Test
    public void theStoredValuesDecideOnOpeningAndTheDefaultsAfterRestoring() {
        workspace.put(EvoSuitePreferences.LLM_PROVIDER, GenerationOptions.LLM_OLLAMA);
        workspace.put(EvoSuitePreferences.LLM_APPROACH, GenerationOptions.APPROACH_LLM_ITERATIVE);
        onUiThread(() -> {
            PreferenceDialog dialog = SettingsDialogs.preferences(Section.LLM);
            try {
                Composite page = page(dialog);
                assertEnabled(page, true, APPROACH, MODEL, POSTPROCESSING, CALLS);
                assertEnabled(page, false, KEY);
                assertEnabled(page, false, SEARCH_INTEGRATION);

                SettingsDialogs.restoreDefaults(dialog);
                assertEnabled(page, false, BEYOND_PROVIDER);
            } finally {
                dialog.close();
            }
        });
    }

    @Test
    public void assertionsKeptDependsOnGeneratingAssertions() {
        workspace.putBoolean(EvoSuitePreferences.ASSERTIONS, false);
        onUiThread(() -> {
            PreferenceDialog dialog = SettingsDialogs.preferences(Section.GENERAL);
            try {
                Composite page = page(dialog);
                assertEnabled(page, false, "Assertions kept");
                click(page, "Generate assertions");
                assertEnabled(page, true, "Assertions kept");
                click(page, "Generate assertions");
                assertEnabled(page, false, "Assertions kept");
                SettingsDialogs.restoreDefaults(dialog);
                assertEnabled(page, true, "Assertions kept");
            } finally {
                dialog.close();
            }
        });
    }

    @Test
    public void theProjectSwitchDisablesEverythingAndOtherwiseTheRulesApply() throws Exception {
        IProject project = TestProjects.create("enablement", "src", null).getProject();
        IEclipsePreferences own = new ProjectScope(project).getNode(EvoSuitePreferences.QUALIFIER);
        own.put(EvoSuitePreferences.LLM_PROVIDER, GenerationOptions.LLM_ANTHROPIC);
        onUiThread(() -> {
            PreferenceDialog dialog = SettingsDialogs.properties(project, Section.LLM);
            try {
                Composite page = page(dialog);
                EvoSuitePropertyPage properties = (EvoSuitePropertyPage) dialog.getSelectedPage();
                assertEnabled(page, false, PROVIDER, MODEL, CALLS);

                properties.setUseProjectSettings(true);
                assertEnabled(page, true, PROVIDER, MODEL, CALLS);
                select(page, APPROACH, "LLM only, one prompt");
                assertEnabled(page, false, SEARCH_INTEGRATION);
                select(page, PROVIDER, "None (no LLM)");
                assertEnabled(page, false, APPROACH, MODEL, CALLS);

                properties.setUseProjectSettings(false);
                select(page, PROVIDER, "Ollama (local)");
                assertEnabled(page, false, PROVIDER, MODEL, CALLS);
                properties.setUseProjectSettings(true);
                assertEnabled(page, true, PROVIDER, MODEL, CALLS);
            } finally {
                dialog.close();
            }
        });
    }

    static Composite page(PreferenceDialog dialog) {
        return (Composite) ((IPreferencePage) dialog.getSelectedPage()).getControl();
    }

    private static List<String> without(List<String> labels, String label) {
        List<String> result = new ArrayList<>(labels);
        result.remove(label);
        return result;
    }

    private static void assertEnabled(Composite page, boolean enabled, String... labels) {
        assertEnabled(page, enabled, List.of(labels));
    }

    private static void assertEnabled(Composite page, boolean enabled, List<String> labels) {
        List<String> wrong = labels.stream().filter(l -> control(page, l).isEnabled() != enabled).toList();
        assertEquals(List.of(), wrong, enabled ? "disabled" : "enabled");
    }

    /** Selects an entry in a combo box as a user does. */
    static void select(Composite page, String label, String entry) {
        Combo combo = (Combo) control(page, label);
        int index = combo.indexOf(entry);
        assertTrue(index >= 0, entry);
        combo.select(index);
        combo.notifyListeners(SWT.Selection, new Event());
        SettingsDialogs.pump();
    }

    private static void click(Composite page, String label) {
        Button button = (Button) control(page, label);
        button.setSelection(!button.getSelection());
        button.notifyListeners(SWT.Selection, new Event());
        SettingsDialogs.pump();
    }

    /** The checkbox whose text starts with {@code label}, or the control after such a label. */
    static Control control(Composite page, String label) {
        Control found = find(page, label);
        if (found == null) {
            throw new AssertionError("no control labelled " + label);
        }
        return found;
    }

    private static Control find(Composite parent, String label) {
        Control[] children = parent.getChildren();
        for (int i = 0; i < children.length; i++) {
            Control child = children[i];
            if (child instanceof Button button && button.getText().replace("&", "").startsWith(label)) {
                return button;
            }
            if (child instanceof Label text && text.getText().replace("&", "").startsWith(label)
                    && i + 1 < children.length) {
                return children[i + 1];
            }
            if (child instanceof Composite composite) {
                Control found = find(composite, label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
