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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.eclipse.jface.preference.BooleanFieldEditor;
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
import org.eclipse.swt.widgets.Text;
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

    /**
     * An editor, the composite it lives in, which enabling it requires, and whether the setting
     * applies, given the values of the others.
     */
    static final class Field {
        final FieldEditor editor;
        final Composite parent;
        final BooleanSupplier applies;

        Field(FieldEditor editor, Composite parent, BooleanSupplier applies) {
            this.editor = editor;
            this.parent = parent;
            this.applies = applies;
        }
    }

    /**
     * The most controls any editor has: label, text and button of the Java home. Controls that
     * span the page span this many columns, which a grid layout cuts down to the columns it has.
     */
    private static final int COLUMNS = 3;

    private final List<Field> fields = new ArrayList<>();
    private final List<Composite> groups = new ArrayList<>();
    private BooleanFieldEditor assertions;
    private BooleanFieldEditor minimize;
    private ChoiceFieldEditor provider;
    private ChoiceFieldEditor approach;
    private final List<ApiKeyFieldEditor> keys = new ArrayList<>();
    private boolean workspacePage;
    private Text modelName;
    private Text baseUrl;

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
        settings.workspacePage = workspace;
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

    /**
     * Updates what depends on the values the editors show. It enables the editors of the
     * settings that apply and disables the others, keeping their values, and shows the hints
     * for the provider chosen. Call it when the page opens, when an editor's value changes and
     * after the defaults are restored.
     *
     * @param pageEnabled whether the page's settings are editable at all, which on a project
     *                    page the switch for project-specific settings decides
     */
    void refresh(boolean pageEnabled) {
        for (Field field : fields) {
            field.editor.setEnabled(pageEnabled && field.applies.getAsBoolean(), field.parent);
        }
        if (provider != null) {
            modelName.setMessage(switch (provider.getValue()) {
                case GenerationOptions.LLM_OPENAI -> "gpt-4o (EvoSuite default)";
                case GenerationOptions.LLM_ANTHROPIC -> "e.g. claude-sonnet-5-5";
                case GenerationOptions.LLM_OLLAMA -> "e.g. qwen3-coder";
                default -> "";
            });
            baseUrl.setMessage(GenerationOptions.LLM_OLLAMA.equals(provider.getValue())
                    ? GenerationOptions.OLLAMA_DEFAULT_URL : "Provider default");
        }
    }

    /** Whether a provider is chosen; the approach applies only then. */
    private boolean llmConfigured() {
        return !GenerationOptions.LLM_NONE.equals(provider.getValue());
    }

    /** Whether the LLM is on; the remaining LLM settings apply only then. */
    private boolean llmOn() {
        return llmConfigured() && !GenerationOptions.APPROACH_SEARCH_ONLY.equals(approach.getValue());
    }

    /** Whether the LLM helps the search, rather than generating tests alone. */
    private boolean llmAssistsSearch() {
        return llmOn() && GenerationOptions.APPROACH_SEARCH.equals(approach.getValue());
    }

    /** Whether the provider takes an API key; a local Ollama does not. */
    private boolean llmNeedsKey() {
        return llmOn() && (GenerationOptions.LLM_OPENAI.equals(provider.getValue())
                || GenerationOptions.LLM_ANTHROPIC.equals(provider.getValue()));
    }

    private void general(Composite parent) {
        Composite search = group(parent, "Search");
        IntegerFieldEditor budget = new IntegerFieldEditor(EvoSuitePreferences.SEARCH_BUDGET,
                "Search &budget per class (seconds):", search);
        budget.setValidRange(1, 24 * 60 * 60);
        add(budget, search);
        StringFieldEditor seed = new ValidatedStringFieldEditor(EvoSuitePreferences.RANDOM_SEED,
                "Random &seed:", search, value -> {
                    if (value.isBlank()) return true;
                    try { Long.parseLong(value.trim()); return true; }
                    catch (NumberFormatException e) { return false; }
                }, "Random seed must be a signed 64-bit integer, or empty for a fresh seed.");
        seed.getTextControl(search).setMessage("Fresh seed per class (recorded in Runs)");
        add(seed, search);
        add(new CriteriaFieldEditor(EvoSuitePreferences.CRITERION, "Coverage &criteria:", search), search);
        assertions = new BooleanFieldEditor(EvoSuitePreferences.ASSERTIONS, "Generate &assertions", search);
        add(assertions, search);
        add(new ChoiceFieldEditor(EvoSuitePreferences.ASSERTION_STRATEGY, "Assertions &kept:",
                new String[][]{{"Those that detect faults (fewer)", "MUTATION"},
                        {"All observed values (more)", "ALL"}}, search), search, assertions::getBooleanValue);
        minimize = new BooleanFieldEditor(EvoSuitePreferences.MINIMIZE,
                "&Minimize tests (remove redundant tests and statements)", search);
        add(minimize, search);
        add(new BooleanFieldEditor(EvoSuitePreferences.MINIMIZE_VALUES,
                "Minimize &values (simplify constants)", search), search, minimize::getBooleanValue);

        Composite output = group(parent, "Generated tests");
        add(new ChoiceFieldEditor(EvoSuitePreferences.TEST_FORMAT, "Test &format:",
                new String[][]{{"Automatic (JUnit 4 or JUnit 5)", ""}, {"JUnit 5", GenerationOptions.JUNIT5}, {"JUnit 4", GenerationOptions.JUNIT4}},
                output), output);
        add(new BooleanFieldEditor(EvoSuitePreferences.NO_RUNTIME_DEPENDENCY,
                "Omit EvoSuite runtime &dependency", output), output);
        add(new ValidatedStringFieldEditor(EvoSuitePreferences.TEST_FOLDER,
                "Test &source folder (project-relative):", output, value -> {
                    try { var path = java.nio.file.Path.of(value).normalize();
                        return !value.isBlank() && !path.isAbsolute() && !path.startsWith("..") && !path.toString().equals("."); }
                    catch (RuntimeException e) { return false; }
                }, "Choose a test folder inside the project."), output);
        add(new ValidatedStringFieldEditor(EvoSuitePreferences.TEST_SUFFIX, "Test class s&uffix:", output,
                value -> value.matches("[A-Za-z_$][A-Za-z0-9_$]*"), "Test suffix must be a Java identifier."), output);
        add(new ChoiceFieldEditor(EvoSuitePreferences.TEST_NAMING, "Test &names:",
                new String[][]{{"Numbered (test0, test1, ...)", "NUMBERED"},
                        {"Descriptive (from what each test covers)", "COVERAGE"}}, output), output);
        add(new ChoiceFieldEditor(EvoSuitePreferences.VARIABLE_NAMING, "&Variable names:",
                new String[][]{{"From the type (int0, string0, ...)", "TYPE_BASED"},
                        {"From how values are used", "HEURISTICS_BASED"}}, output), output);
        add(new BooleanFieldEditor(EvoSuitePreferences.TEST_COMMENTS,
                "Comment tests with the &goals they cover", output), output);
        add(new OptionalIntegerFieldEditor(EvoSuitePreferences.TEST_TIMEOUT, "&Time limit per test (ms):",
                output, 24 * 60 * 60 * 1000, "EvoSuite default (3000)"), output);
        add(new BooleanFieldEditor(EvoSuitePreferences.OPEN_GENERATED_TESTS,
                "&Open generated tests in an editor", output), output);
    }

    private void llm(Composite parent, boolean workspace) {
        Composite model = group(parent, "Model");
        provider = new ChoiceFieldEditor(EvoSuitePreferences.LLM_PROVIDER, "&Provider:",
                new String[][]{{"None (no LLM)", GenerationOptions.LLM_NONE},
                        {"OpenAI", GenerationOptions.LLM_OPENAI},
                        {"Anthropic", GenerationOptions.LLM_ANTHROPIC},
                        {"Ollama (local)", GenerationOptions.LLM_OLLAMA}}, model);
        add(provider, model);
        StringFieldEditor modelEditor = new StringFieldEditor(EvoSuitePreferences.LLM_MODEL, "Mo&del:", model);
        modelName = modelEditor.getTextControl(model);
        add(modelEditor, model, this::llmOn);
        StringFieldEditor baseUrlEditor = new StringFieldEditor(EvoSuitePreferences.LLM_BASE_URL, "Base &URL:", model);
        baseUrl = baseUrlEditor.getTextControl(model);
        add(baseUrlEditor, model, this::llmOn);
        if (workspace) {
            for (String cloud : List.of(GenerationOptions.LLM_OPENAI, GenerationOptions.LLM_ANTHROPIC)) {
                ApiKeyFieldEditor key = new ApiKeyFieldEditor("API &key (" + cloud + "):", model, cloud);
                keys.add(key);
                add(key, model, () -> key.loaded() || (llmNeedsKey() && cloud.equals(provider.getValue())));
                org.eclipse.swt.widgets.Button load = new org.eclipse.swt.widgets.Button(model, SWT.PUSH);
                load.setText("Load stored " + cloud + " API key");
                load.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false, COLUMNS, 1));
                load.addListener(SWT.Selection, e -> { key.loadStored(); refresh(true); });
            }
            org.eclipse.swt.widgets.Button legacy = new org.eclipse.swt.widgets.Button(model, SWT.PUSH);
            legacy.setText("Import legacy shared key for selected provider");
            legacy.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false, COLUMNS, 1));
            legacy.addListener(SWT.Selection, e -> {
                if (!llmNeedsKey()) return;
                String value = LlmApiKey.get();
                for (ApiKeyFieldEditor key : keys) {
                    if (key.getLabelText().contains(provider.getValue())) key.setStringValue(value);
                }
            });
        }

        Composite use = group(parent, "Test generation");
        approach = new ChoiceFieldEditor(EvoSuitePreferences.LLM_APPROACH, "Appro&ach:",
                new String[][]{{"Search only (LLM off, settings kept)", GenerationOptions.APPROACH_SEARCH_ONLY},
                        {"Search, assisted by the LLM", GenerationOptions.APPROACH_SEARCH},
                        {"LLM only, one prompt", GenerationOptions.APPROACH_LLM_SINGLE_PROMPT},
                        {"LLM only, iterating on uncovered goals", GenerationOptions.APPROACH_LLM_ITERATIVE}},
                use);
        add(approach, use, this::llmConfigured);
        add(new ChoiceFieldEditor(EvoSuitePreferences.LLM_SEEDING, "Seed the search with LLM &input:",
                new String[][]{{"Off", "OFF"},
                        {"Minimal: classes to cast to", "MIN"},
                        {"Standard: constants and objects too", "STANDARD"},
                        {"Full: also initial tests", "FULL"}}, use), use, this::llmAssistsSearch);
        add(new BooleanFieldEditor(EvoSuitePreferences.LLM_ON_STAGNATION,
                "Ask the LLM for tests when the search s&tagnates", use), use, this::llmAssistsSearch);
        add(new BooleanFieldEditor(EvoSuitePreferences.LLM_OPERATORS,
                "Use LLM-based mutation and c&rossover", use), use, this::llmAssistsSearch);
        add(new BooleanFieldEditor(EvoSuitePreferences.LLM_POSTPROCESSING,
                "Improve test names, comments and assertions &with the LLM", use), use, this::llmOn);
        add(new OptionalIntegerFieldEditor(EvoSuitePreferences.LLM_MAX_CALLS, "LLM ca&lls per class:", use,
                1_000_000, "EvoSuite default (no limit)"), use, this::llmOn);
        Composite context = group(parent, "Model controls");
        add(new ChoiceFieldEditor(EvoSuitePreferences.LLM_CONTEXT_MODE, "Class &context:",
                new String[][]{{"Signatures only", "SIGNATURE_ONLY"}, {"Project source", "SOURCE_CODE"},
                        {"Decompiled source", "DECOMPILED_SOURCE"},
                        {"Disassembled bytecode", "BYTECODE_DISASSEMBLED"}}, context), context, this::llmOn);
        add(positiveInteger(EvoSuitePreferences.LLM_MAX_TOKENS, "Output token limit:", context), context, this::llmOn);
        add(positiveInteger(EvoSuitePreferences.LLM_TIMEOUT_SECONDS, "HTTP timeout (seconds):", context), context, this::llmOn);
        IntegerFieldEditor retries = new IntegerFieldEditor(EvoSuitePreferences.LLM_RETRY_MAX_ATTEMPTS,
                "Retry attempts:", context);
        retries.setValidRange(0, 1_000_000);
        add(retries, context, this::llmOn);
        add(new ValidatedStringFieldEditor(EvoSuitePreferences.LLM_TEMPERATURE, "Temperature:", context,
                value -> {
                    try { double n = Double.parseDouble(value); return Double.isFinite(n) && n >= 0 && n <= 2; }
                    catch (NumberFormatException e) { return false; }
                }, "Temperature must be between 0 and 2."), context, this::llmOn);
        org.eclipse.swt.widgets.Button check = new org.eclipse.swt.widgets.Button(parent, SWT.PUSH);
        check.setText("Test connection (metadata only)");
        check.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false, COLUMNS, 1));
        check.addListener(SWT.Selection, e -> testConnection(parent));
        note(parent, workspace ? "More llm_* options can go into the additional arguments under Advanced."
                : "More llm_* options can go into the additional arguments under Advanced. The API key "
                        + "is a workspace setting.");
    }

    private void advanced(Composite parent) {
        Composite runtime = group(parent, "Running EvoSuite");
        DirectoryFieldEditor javaHome = new DirectoryFieldEditor(EvoSuitePreferences.JAVA_HOME, "&Java home:",
                runtime);
        javaHome.setEmptyStringAllowed(true);
        javaHome.getTextControl(runtime).setMessage("The project's JRE");
        add(javaHome, runtime);
        add(new OptionalIntegerFieldEditor(EvoSuitePreferences.CLIENT_MEMORY, "Client &heap (MB):", runtime,
                1024 * 1024, "EvoSuite default"), runtime);
        StringFieldEditor arguments = new ValidatedStringFieldEditor(EvoSuitePreferences.EXTRA_ARGUMENTS,
                "Additional &arguments:", runtime, value -> org.evosuite.eclipse.launch.EvoSuiteCommand.splitArguments(value)
                        .stream().noneMatch(a -> a.startsWith("-Dllm_api_key=")),
                "Store API keys in secure storage on the LLM page.");
        arguments.getTextControl(runtime).setMessage("Passed last, e.g. -Dllm_temperature=0.2");
        add(arguments, runtime);

        Composite project = group(parent, "Project setup");
        add(new BooleanFieldEditor(EvoSuitePreferences.USE_POM_SETTINGS,
                "Take settings from the EvoSuite Maven plugin in pom.&xml", project), project);
        if (!workspacePage) add(new BooleanFieldEditor("delegateToMaven",
                "Run whole-class requests through the Maven plugin", project), project);
        add(new BooleanFieldEditor(EvoSuitePreferences.CONFIGURE_BUILD_PATH,
                "Add the test folder, JUnit and the EvoSuite runtime to the build &path", project), project);
    }

    void loadFrom(org.eclipse.jface.preference.IPreferenceStore store) {
        for (Field field : fields) {
            if (field.editor instanceof ApiKeyFieldEditor) continue;
            var previous = field.editor.getPreferenceStore();
            try { field.editor.setPreferenceStore(store); field.editor.load(); }
            finally { field.editor.setPreferenceStore(previous); }
        }
    }

    org.eclipse.jface.preference.IPreferenceStore snapshot() {
        var result = new org.eclipse.jface.preference.PreferenceStore();
        for (Field field : fields) {
            if (field.editor instanceof ApiKeyFieldEditor) continue;
            var previous = field.editor.getPreferenceStore();
            try { field.editor.setPreferenceStore(result); field.editor.store(); }
            finally { field.editor.setPreferenceStore(previous); }
        }
        return result;
    }

    boolean saveKeys() {
        keys.forEach(ApiKeyFieldEditor::store);
        return keys.stream().allMatch(ApiKeyFieldEditor::saved);
    }

    GenerationOptions draft() {
        org.eclipse.jface.preference.PreferenceStore draft = new org.eclipse.jface.preference.PreferenceStore();
        for (Field field : fields) {
            if (field.editor instanceof ApiKeyFieldEditor) continue;
            var previous = field.editor.getPreferenceStore();
            try { field.editor.setPreferenceStore(draft); field.editor.store(); }
            finally { field.editor.setPreferenceStore(previous); }
        }
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        for (String key : org.evosuite.eclipse.launch.SettingKeys.all())
            if (draft.contains(key)) values.put(key, draft.getString(key));
        return org.evosuite.eclipse.launch.SettingKeys.resolve(values);
    }

    private void testConnection(Composite parent) {
        GenerationOptions options;
        try { options = draft(); }
        catch (IllegalArgumentException e) { org.eclipse.jface.dialogs.MessageDialog.openError(parent.getShell(),
                "EvoSuite", e.getMessage()); return; }
        String typed = keys.stream().filter(k -> k.getLabelText().contains(options.llmProvider))
                .map(ApiKeyFieldEditor::getStringValue).findFirst().orElse("");
        org.eclipse.core.runtime.jobs.Job.create("Checking LLM metadata", monitor -> {
            String result;
            try {
                String key = typed;
                if (key.isBlank() && (GenerationOptions.LLM_OPENAI.equals(options.effectiveLlmProvider())
                        || GenerationOptions.LLM_ANTHROPIC.equals(options.effectiveLlmProvider()))) key = LlmApiKey.get(options.llmProvider);
                if (key.isBlank() && !GenerationOptions.LLM_OLLAMA.equals(options.effectiveLlmProvider())) key = System.getenv().getOrDefault(org.evosuite.eclipse.launch.EvoSuiteCommand.API_KEY_VARIABLE, "");
                result = org.evosuite.eclipse.launch.LlmConnectionCheck.probe(options, key, monitor);
            } catch (org.eclipse.core.runtime.OperationCanceledException e) { return; }
            catch (Exception e) { result = "Connection check failed. Check the endpoint, network and provider credentials. No generation request was sent."; }
            String message = result;
            parent.getDisplay().asyncExec(() -> { if (!parent.isDisposed()) org.eclipse.jface.dialogs.MessageDialog
                    .openInformation(parent.getShell(), "EvoSuite connection check", message); });
        }).schedule();
    }

    private static IntegerFieldEditor positiveInteger(String key, String label, Composite parent) {
        IntegerFieldEditor editor = new IntegerFieldEditor(key, label, parent);
        editor.setValidRange(1, 1_000_000);
        return editor;
    }

    private void add(FieldEditor editor, Composite parent) {
        add(editor, parent, () -> true);
    }

    private void add(FieldEditor editor, Composite parent, BooleanSupplier applies) {
        fields.add(new Field(editor, parent, applies));
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
