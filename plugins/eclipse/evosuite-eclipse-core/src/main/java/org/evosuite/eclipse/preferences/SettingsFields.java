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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ComboFieldEditor;
import org.eclipse.jface.preference.DirectoryFieldEditor;
import org.eclipse.jface.preference.FieldEditor;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.evosuite.eclipse.launch.GenerationOptions;

/**
 * The editors of one settings page, shared by the workspace preference pages and the project
 * property pages so that the two cannot drift apart. They are laid out in groups inside the
 * page's single field-editor parent, which is why the pages use the GRID style: in FLAT style
 * every call to getFieldEditorParent() makes a new composite without a layout, and groups in it
 * get no size.
 */
final class SettingsFields {

    /** The settings pages, each both a workspace preference page and a project property page. */
    enum Section {
        GENERAL("org.evosuite.eclipse.preferences.EvoSuitePreferencePage",
                "org.evosuite.eclipse.preferences.EvoSuitePropertyPage"),
        LLM("org.evosuite.eclipse.preferences.LlmPreferencePage",
                "org.evosuite.eclipse.preferences.LlmPropertyPage"),
        ADVANCED("org.evosuite.eclipse.preferences.AdvancedPreferencePage",
                "org.evosuite.eclipse.preferences.AdvancedPropertyPage");

        /** The page ids in plugin.xml. */
        final String preferencePageId;
        final String propertyPageId;

        Section(String preferencePageId, String propertyPageId) {
            this.preferencePageId = preferencePageId;
            this.propertyPageId = propertyPageId;
        }

        static String[] preferencePageIds() {
            return Arrays.stream(values()).map(s -> s.preferencePageId).toArray(String[]::new);
        }
    }

    /** An editor and the composite it lives in, which enabling it requires. */
    static final class Field {
        final FieldEditor editor;
        final Composite parent;

        Field(FieldEditor editor, Composite parent) {
            this.editor = editor;
            this.parent = parent;
        }
    }

    /**
     * The most controls any editor has: label, text and button of the Java home. Controls that
     * span the page span this many columns, which a grid layout cuts down to the columns it has.
     */
    private static final int COLUMNS = 3;

    private final List<Field> fields = new ArrayList<>();
    private final List<Composite> groups = new ArrayList<>();

    private SettingsFields() {
    }

    /**
     * Creates the editors of {@code section} in {@code parent} and hands each to {@code add}.
     *
     * @param workspace whether this is a workspace page; only the workspace has the API key,
     *                  which is stored once for all projects
     */
    static SettingsFields create(Section section, Composite parent, boolean workspace, Consumer<FieldEditor> add) {
        SettingsFields settings = new SettingsFields();
        switch (section) {
            case GENERAL -> settings.general(parent);
            case LLM -> settings.llm(parent, workspace);
            case ADVANCED -> settings.advanced(parent);
        }
        // Every field editor sets a new layout on its parent with its own number of columns,
        // so the groups get the page's back once all editors exist. The page takes the most
        // controls of any editor as its number of columns, and spans each editor's last control
        // over the columns it does not use.
        int columns = settings.fields.stream().mapToInt(f -> f.editor.getNumberOfControls()).max().orElse(1);
        for (Composite group : settings.groups) {
            ((GridLayout) group.getLayout()).numColumns = columns;
        }
        settings.fields.forEach(f -> add.accept(f.editor));
        return settings;
    }

    List<Field> fields() {
        return fields;
    }

    /** Enables or disables every editor, as the project pages' switch does. */
    void setEnabled(boolean enabled) {
        for (Field field : fields) {
            field.editor.setEnabled(enabled, field.parent);
        }
    }

    private void general(Composite parent) {
        Composite search = group(parent, "Search");
        IntegerFieldEditor budget = new IntegerFieldEditor(EvoSuitePreferences.SEARCH_BUDGET,
                "Search &budget per class (seconds):", search);
        budget.setValidRange(1, 24 * 60 * 60);
        add(budget, search);
        add(new StringFieldEditor(EvoSuitePreferences.CRITERION,
                "Coverage &criteria (empty for EvoSuite's default):", search), search);
        add(new BooleanFieldEditor(EvoSuitePreferences.ASSERTIONS, "Generate &assertions", search), search);
        add(new ComboFieldEditor(EvoSuitePreferences.ASSERTION_STRATEGY, "Assertions &kept:",
                new String[][]{{"Those that detect faults (fewer)", "MUTATION"},
                        {"All observed values (more)", "ALL"}}, search), search);
        add(new BooleanFieldEditor(EvoSuitePreferences.MINIMIZE, "&Minimize tests", search), search);

        Composite output = group(parent, "Generated tests");
        add(new ComboFieldEditor(EvoSuitePreferences.TEST_FORMAT, "Test &format:",
                new String[][]{{"JUnit 5", GenerationOptions.JUNIT5}, {"JUnit 4", GenerationOptions.JUNIT4}},
                output), output);
        add(new StringFieldEditor(EvoSuitePreferences.TEST_FOLDER,
                "Test &source folder (project-relative):", output), output);
        add(new StringFieldEditor(EvoSuitePreferences.TEST_SUFFIX, "Test class s&uffix:", output), output);
        add(new ComboFieldEditor(EvoSuitePreferences.TEST_NAMING, "Test &names:",
                new String[][]{{"Numbered (test0, test1, ...)", "NUMBERED"},
                        {"Descriptive (from what each test covers)", "COVERAGE"}}, output), output);
        add(new ComboFieldEditor(EvoSuitePreferences.VARIABLE_NAMING, "&Variable names:",
                new String[][]{{"From the type (int0, string0, ...)", "TYPE_BASED"},
                        {"From how values are used", "HEURISTICS_BASED"}}, output), output);
        add(new BooleanFieldEditor(EvoSuitePreferences.TEST_COMMENTS,
                "Comment each test with the &goals it covers", output), output);
        IntegerFieldEditor timeout = new IntegerFieldEditor(EvoSuitePreferences.TEST_TIMEOUT,
                "Time limit per test in ms (0 for EvoSuite's 3000):", output);
        timeout.setValidRange(0, 24 * 60 * 60 * 1000);
        add(timeout, output);
        add(new BooleanFieldEditor(EvoSuitePreferences.OPEN_GENERATED_TESTS,
                "&Open generated tests in an editor", output), output);
    }

    private void llm(Composite parent, boolean workspace) {
        Composite model = group(parent, "Model");
        add(new ComboFieldEditor(EvoSuitePreferences.LLM_PROVIDER, "&Provider:",
                new String[][]{{"None (no LLM)", GenerationOptions.LLM_NONE},
                        {"OpenAI", GenerationOptions.LLM_OPENAI},
                        {"Anthropic", GenerationOptions.LLM_ANTHROPIC},
                        {"Ollama (local)", GenerationOptions.LLM_OLLAMA}}, model), model);
        add(new StringFieldEditor(EvoSuitePreferences.LLM_MODEL, "Mo&del:", model), model);
        add(new StringFieldEditor(EvoSuitePreferences.LLM_BASE_URL,
                "Base &URL (empty for the provider's default):", model), model);
        if (workspace) {
            add(new ApiKeyFieldEditor("API &key (kept in secure storage):", model), model);
        }

        Composite use = group(parent, "Test generation");
        add(new ComboFieldEditor(EvoSuitePreferences.LLM_APPROACH, "Appro&ach:",
                new String[][]{{"Search only (LLM off, settings kept)", GenerationOptions.APPROACH_SEARCH_ONLY},
                        {"Search, assisted by the LLM", GenerationOptions.APPROACH_SEARCH},
                        {"LLM only, one prompt", GenerationOptions.APPROACH_LLM_SINGLE_PROMPT},
                        {"LLM only, iterating on uncovered goals", GenerationOptions.APPROACH_LLM_ITERATIVE}},
                use), use);
        add(new ComboFieldEditor(EvoSuitePreferences.LLM_SEEDING, "Seed the search with LLM &input:",
                new String[][]{{"Off", "OFF"},
                        {"Minimal: classes to cast to", "MIN"},
                        {"Standard: constants and objects too", "STANDARD"},
                        {"Full: also initial tests", "FULL"}}, use), use);
        add(new BooleanFieldEditor(EvoSuitePreferences.LLM_ON_STAGNATION,
                "Ask the LLM for tests when the search s&tagnates", use), use);
        add(new BooleanFieldEditor(EvoSuitePreferences.LLM_OPERATORS,
                "Use LLM-based mutation and c&rossover", use), use);
        add(new BooleanFieldEditor(EvoSuitePreferences.LLM_POSTPROCESSING,
                "Improve test names, comments and assertions &with the LLM", use), use);
        IntegerFieldEditor calls = new IntegerFieldEditor(EvoSuitePreferences.LLM_MAX_CALLS,
                "LLM ca&lls per class (0 for no limit):", use);
        calls.setValidRange(0, 1_000_000);
        add(calls, use);
        note(parent, workspace ? "More llm_* options can go into the additional arguments under Advanced."
                : "More llm_* options can go into the additional arguments under Advanced. The API key "
                        + "is a workspace setting.");
    }

    private void advanced(Composite parent) {
        Composite runtime = group(parent, "Running EvoSuite");
        DirectoryFieldEditor javaHome = new DirectoryFieldEditor(EvoSuitePreferences.JAVA_HOME,
                "&Java home (empty for the project's JRE):", runtime);
        javaHome.setEmptyStringAllowed(true);
        add(javaHome, runtime);
        IntegerFieldEditor memory = new IntegerFieldEditor(EvoSuitePreferences.CLIENT_MEMORY,
                "Client &heap in MB (0 for EvoSuite's default):", runtime);
        memory.setValidRange(0, 1024 * 1024);
        add(memory, runtime);
        add(new StringFieldEditor(EvoSuitePreferences.EXTRA_ARGUMENTS,
                "Additional &EvoSuite arguments:", runtime), runtime);

        Composite project = group(parent, "Project setup");
        add(new BooleanFieldEditor(EvoSuitePreferences.USE_POM_SETTINGS,
                "Take settings from the EvoSuite Maven plugin in pom.&xml", project), project);
        add(new BooleanFieldEditor(EvoSuitePreferences.CONFIGURE_BUILD_PATH,
                "Add the test folder, JUnit and the EvoSuite runtime to the build &path", project), project);
    }

    private void add(FieldEditor editor, Composite parent) {
        fields.add(new Field(editor, parent));
    }

    private Composite group(Composite parent, String title) {
        Group group = new Group(parent, SWT.NONE);
        group.setFont(parent.getFont());
        group.setText(title);
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, COLUMNS, 1));
        group.setLayout(new GridLayout(COLUMNS, false));
        groups.add(group);
        return group;
    }

    private static void note(Composite parent, String text) {
        Label label = new Label(parent, SWT.WRAP);
        label.setFont(parent.getFont());
        label.setText(text);
        GridData layout = new GridData(SWT.FILL, SWT.TOP, true, false, COLUMNS, 1);
        layout.widthHint = 400;
        label.setLayoutData(layout);
    }
}
