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

import java.util.function.Consumer;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.ui.IWorkbenchPropertyPage;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.evosuite.eclipse.launch.GenerationOptions;
import org.evosuite.eclipse.preferences.SettingsFields.Section;

/**
 * Project-specific settings, stored in the project's {@code .settings} folder. When enabled they
 * replace the workspace preferences entirely; settings left unchanged take EvoSuite's defaults.
 *
 * <p>Like the workspace preferences, the settings are spread over this page and its sub-pages.
 * One switch enables them all: each page shows its checkbox, and they all show and store the
 * same value.
 */
public class EvoSuitePropertyPage extends FieldEditorPreferencePage implements IWorkbenchPropertyPage {

    private final Section section;
    private IAdaptable element;
    private IProject project;
    private ProjectSettingsSwitch projectSettings;
    private Button useProjectSettings;
    private final Consumer<Boolean> switchListener = this::projectSettingsSwitched;
    private SettingsFields settings;

    public EvoSuitePropertyPage() {
        this(Section.GENERAL);
    }

    EvoSuitePropertyPage(Section section) {
        super(GRID);
        this.section = section;
    }

    @Override
    public IAdaptable getElement() {
        return element;
    }

    @Override
    public void setElement(IAdaptable element) {
        this.element = element;
        project = Adapters.adapt(element, IProject.class);
        setPreferenceStore(new ScopedPreferenceStore(new ProjectScope(project), EvoSuitePreferences.QUALIFIER));
    }

    @Override
    protected void createFieldEditors() {
        Composite parent = getFieldEditorParent();
        if (section == Section.GENERAL) {
            GenerationOptions resolved = EvoSuitePreferences.resolve(project, null);
            if (!resolved.fromPom.isEmpty()) {
                Label pom = new Label(parent, SWT.WRAP);
                pom.setText("pom.xml configures the EvoSuite Maven plugin, and these take precedence over the "
                        + "settings below: " + String.join("; ", resolved.fromPom) + ".");
                GridData layout = new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1);
                layout.widthHint = 500;
                pom.setLayoutData(layout);
            }
        }
        createSwitch(parent);
        settings = SettingsFields.create(section, parent, false, this::addField);
    }

    /**
     * The checkbox and, as on JDT's project pages, a link to the workspace settings. The checkbox
     * is not a field editor, because it shows the switch shared with the other pages.
     */
    private void createSwitch(Composite parent) {
        projectSettings = ProjectSettingsSwitch.of(parent.getShell(), project,
                getPreferenceStore().getBoolean(EvoSuitePreferences.USE_PROJECT_SETTINGS));
        projectSettings.addListener(switchListener);

        Composite row = new Composite(parent, SWT.NONE);
        row.setFont(parent.getFont());
        GridLayout rowLayout = new GridLayout(2, false);
        rowLayout.marginWidth = 0;
        rowLayout.marginHeight = 0;
        row.setLayout(rowLayout);
        row.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1));

        useProjectSettings = new Button(row, SWT.CHECK);
        useProjectSettings.setFont(parent.getFont());
        useProjectSettings.setText("Enable pro&ject-specific settings");
        useProjectSettings.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        useProjectSettings.setSelection(projectSettings.isEnabled());
        useProjectSettings.addSelectionListener(SelectionListener.widgetSelectedAdapter(
                e -> projectSettings.setEnabled(useProjectSettings.getSelection())));

        Link workspace = new Link(row, SWT.NONE);
        workspace.setFont(parent.getFont());
        workspace.setText("<a>Configure Workspace Settings...</a>");
        workspace.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        workspace.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> PreferencesUtil
                .createPreferenceDialogOn(getShell(), section.preferencePageId, Section.preferencePageIds(), null)
                .open()));

        Label separator = new Label(parent, SWT.SEPARATOR | SWT.HORIZONTAL);
        separator.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1));
    }

    @Override
    protected void initialize() {
        super.initialize();
        updateEnablement();
    }

    @Override
    protected void performDefaults() {
        super.performDefaults();
        // As on JDT's pages, restoring the defaults also switches project-specific settings off.
        projectSettings.setEnabled(false);
        updateEnablement();
    }

    @Override
    public boolean performOk() {
        getPreferenceStore().setValue(EvoSuitePreferences.USE_PROJECT_SETTINGS, projectSettings.isEnabled());
        return super.performOk();
    }

    @Override
    public void dispose() {
        if (projectSettings != null) {
            projectSettings.removeListener(switchListener);
        }
        super.dispose();
    }

    private void projectSettingsSwitched(boolean enabled) {
        if (!useProjectSettings.isDisposed()) {
            useProjectSettings.setSelection(enabled);
            updateEnablement();
        }
    }

    /** What ticking or clearing the checkbox does; for tests. */
    void setUseProjectSettings(boolean use) {
        useProjectSettings.setSelection(use);
        projectSettings.setEnabled(use);
    }

    /** Whether the checkbox is ticked; for tests. */
    boolean usesProjectSettings() {
        return useProjectSettings.getSelection();
    }

    /** Whether the settings below the checkbox are editable; for tests. */
    boolean settingsEnabled() {
        SettingsFields.Field first = settings.fields().get(0);
        return first.editor.getLabelControl(first.parent).isEnabled();
    }

    SettingsFields settings() {
        return settings;
    }

    private void updateEnablement() {
        settings.setEnabled(projectSettings.isEnabled());
    }
}
