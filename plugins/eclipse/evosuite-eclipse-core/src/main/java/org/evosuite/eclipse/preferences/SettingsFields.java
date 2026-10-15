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
 * The settings editors, shared by the workspace preference page and the project property page
 * so that the two cannot drift apart. They are laid out in groups inside the page's single
 * field-editor parent, which is why the pages use the GRID style: in FLAT style every call to
 * getFieldEditorParent() makes a new composite without a layout, and groups in it get no size.
 */
final class SettingsFields {

    /** An editor and the composite it lives in, which enabling it requires. */
    static final class Field {
        final FieldEditor editor;
        final Composite parent;

        Field(FieldEditor editor, Composite parent) {
            this.editor = editor;
            this.parent = parent;
        }
    }

    private SettingsFields() {
    }

    /**
     * @param withApiKey whether to include the API key, which is stored per workspace and so
     *                   only belongs on the workspace page
     */
    static List<Field> create(Composite parent, boolean withApiKey, Consumer<FieldEditor> add) {
        List<Field> fields = new ArrayList<>();

        Composite search = group(parent, "Search");
        IntegerFieldEditor budget = new IntegerFieldEditor(EvoSuitePreferences.SEARCH_BUDGET,
                "Search &budget per class (seconds):", search);
        budget.setValidRange(1, 24 * 60 * 60);
        fields.add(new Field(budget, search));
        fields.add(new Field(new StringFieldEditor(EvoSuitePreferences.CRITERION,
                "Coverage &criteria (empty for EvoSuite's default):", search), search));
        fields.add(new Field(new BooleanFieldEditor(EvoSuitePreferences.ASSERTIONS,
                "Generate &assertions", search), search));
        fields.add(new Field(new BooleanFieldEditor(EvoSuitePreferences.MINIMIZE,
                "&Minimize tests", search), search));
        fields.add(new Field(new BooleanFieldEditor(EvoSuitePreferences.USE_POM_SETTINGS,
                "In Maven projects, take the budget, criteria, test folder and arguments from the "
                        + "EvoSuite plugin in pom.&xml", search), search));

        Composite output = group(parent, "Generated tests");
        fields.add(new Field(new ComboFieldEditor(EvoSuitePreferences.TEST_FORMAT, "Test &format:",
                new String[][]{{"JUnit 5", GenerationOptions.JUNIT5}, {"JUnit 4", GenerationOptions.JUNIT4}},
                output), output));
        fields.add(new Field(new StringFieldEditor(EvoSuitePreferences.TEST_FOLDER,
                "Test &source folder (project-relative):", output), output));
        fields.add(new Field(new StringFieldEditor(EvoSuitePreferences.TEST_SUFFIX,
                "Test class &suffix:", output), output));
        fields.add(new Field(new BooleanFieldEditor(EvoSuitePreferences.CONFIGURE_BUILD_PATH,
                "Add the test folder, JUnit and the EvoSuite runtime to the build &path", output), output));
        fields.add(new Field(new BooleanFieldEditor(EvoSuitePreferences.OPEN_GENERATED_TESTS,
                "&Open generated tests in an editor", output), output));

        Composite llm = group(parent, "Large language model");
        fields.add(new Field(new ComboFieldEditor(EvoSuitePreferences.LLM_PROVIDER, "&Provider:",
                new String[][]{{"None (no LLM)", GenerationOptions.LLM_NONE},
                        {"OpenAI", GenerationOptions.LLM_OPENAI},
                        {"Anthropic", GenerationOptions.LLM_ANTHROPIC},
                        {"Ollama (local)", GenerationOptions.LLM_OLLAMA}}, llm), llm));
        fields.add(new Field(new StringFieldEditor(EvoSuitePreferences.LLM_MODEL,
                "Mo&del:", llm), llm));
        fields.add(new Field(new StringFieldEditor(EvoSuitePreferences.LLM_BASE_URL,
                "Base &URL (empty for the provider's default):", llm), llm));
        if (withApiKey) {
            fields.add(new Field(new ApiKeyFieldEditor(
                    "API &key (kept in secure storage):", llm), llm));
        }
        fields.add(new Field(new ComboFieldEditor(EvoSuitePreferences.LLM_APPROACH, "Appro&ach:",
                new String[][]{{"Search, assisted by the LLM", GenerationOptions.APPROACH_SEARCH},
                        {"LLM only, one prompt", GenerationOptions.APPROACH_LLM_SINGLE_PROMPT},
                        {"LLM only, iterating on uncovered goals", GenerationOptions.APPROACH_LLM_ITERATIVE}},
                llm), llm));
        fields.add(new Field(new ComboFieldEditor(EvoSuitePreferences.LLM_SEEDING, "Seed the search with LLM &input:",
                new String[][]{{"Off", "OFF"},
                        {"Minimal: classes to cast to", "MIN"},
                        {"Standard: constants and objects too", "STANDARD"},
                        {"Full: also initial tests", "FULL"}}, llm), llm));
        fields.add(new Field(new BooleanFieldEditor(EvoSuitePreferences.LLM_ON_STAGNATION,
                "Ask the LLM for tests when the search s&tagnates", llm), llm));
        fields.add(new Field(new BooleanFieldEditor(EvoSuitePreferences.LLM_OPERATORS,
                "Use LLM-based mutation and c&rossover", llm), llm));
        fields.add(new Field(new BooleanFieldEditor(EvoSuitePreferences.LLM_POSTPROCESSING,
                "Improve test names, comments and assertions &with the LLM", llm), llm));
        IntegerFieldEditor calls = new IntegerFieldEditor(EvoSuitePreferences.LLM_MAX_CALLS,
                "LLM ca&lls per class (0 for no limit):", llm);
        calls.setValidRange(0, 1_000_000);
        fields.add(new Field(calls, llm));
        note(llm, "Further llm_* options can go into the additional arguments below.");

        Composite runtime = group(parent, "Running EvoSuite");
        IntegerFieldEditor memory = new IntegerFieldEditor(EvoSuitePreferences.CLIENT_MEMORY,
                "Client &heap in MB (0 for EvoSuite's default):", runtime);
        memory.setValidRange(0, 1024 * 1024);
        fields.add(new Field(memory, runtime));
        DirectoryFieldEditor javaHome = new DirectoryFieldEditor(EvoSuitePreferences.JAVA_HOME,
                "&Java home (empty for the project's JRE):", runtime);
        javaHome.setEmptyStringAllowed(true);
        fields.add(new Field(javaHome, runtime));
        fields.add(new Field(new StringFieldEditor(EvoSuitePreferences.EXTRA_ARGUMENTS,
                "Additional &EvoSuite arguments:", runtime), runtime));

        // Every field editor sets a new layout on its parent with its own number of columns,
        // so the groups get theirs back once all editors exist. The page then spans each
        // editor's last control over the columns it does not use.
        for (Composite group : List.of(search, output, llm, runtime)) {
            GridLayout layout = (GridLayout) group.getLayout();
            layout.numColumns = COLUMNS;
        }
        fields.forEach(f -> add.accept(f.editor));
        return fields;
    }

    /** The most controls any editor here has: label, text and button of the Java home. */
    private static final int COLUMNS = 3;

    private static Composite group(Composite parent, String title) {
        Group group = new Group(parent, SWT.NONE);
        group.setFont(parent.getFont());
        group.setText(title);
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, COLUMNS, 1));
        group.setLayout(new GridLayout(COLUMNS, false));
        return group;
    }

    private static void note(Composite parent, String text) {
        Label label = new Label(parent, SWT.WRAP);
        label.setFont(parent.getFont());
        label.setText(text);
        label.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, COLUMNS, 1));
    }
}
