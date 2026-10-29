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
import org.eclipse.jface.util.PropertyChangeEvent;
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
    private org.eclipse.jface.preference.IPreferenceStore projectDraft;
    private boolean previewWorkspace;
    private boolean refreshing;

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
    protected org.eclipse.swt.widgets.Control createContents(org.eclipse.swt.widgets.Composite parent) {
        var scroll = new org.eclipse.swt.custom.ScrolledComposite(parent, org.eclipse.swt.SWT.V_SCROLL) {
            @Override public org.eclipse.swt.graphics.Point computeSize(int w, int h, boolean changed) {
                return new org.eclipse.swt.graphics.Point(w == org.eclipse.swt.SWT.DEFAULT ? 620 : w,
                        h == org.eclipse.swt.SWT.DEFAULT ? 450 : h);
            }
        };
        scroll.setExpandHorizontal(true);
        scroll.setExpandVertical(true);
        var content = super.createContents(scroll);
        scroll.setContent(content);
        scroll.setMinSize(content.computeSize(600, org.eclipse.swt.SWT.DEFAULT));
        scroll.addListener(org.eclipse.swt.SWT.Resize, e -> scroll.setMinSize(
                content.computeSize(scroll.getClientArea().width, org.eclipse.swt.SWT.DEFAULT)));
        return scroll;
    }

    @Override
    protected void createFieldEditors() {
        Composite parent = getFieldEditorParent();
        if (section == Section.GENERAL) {
            String note = EvoSuitePreferences.pomNote(project);
            if (note != null) {
                Label pom = new Label(parent, SWT.WRAP);
                pom.setText(note);
                GridData layout = new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1);
                layout.widthHint = 500;
                pom.setLayoutData(layout);
            }
        }
        createSwitch(parent);
        settings = SettingsFields.create(section, parent, false, this::addField);
        org.eclipse.swt.widgets.Button copy = new org.eclipse.swt.widgets.Button(parent, SWT.PUSH);
        copy.setText("Copy workspace defaults into project profile");
        copy.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false, 3, 1));
        copy.addListener(SWT.Selection, e -> {
            projectSettings.setEnabled(true);
            settings.loadFrom(new ScopedPreferenceStore(org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE,
                    EvoSuitePreferences.QUALIFIER));
            settings.refresh(true);
        });
        org.eclipse.swt.widgets.Button inspect = new org.eclipse.swt.widgets.Button(parent, SWT.PUSH);
        inspect.setText("Effective configuration / check project setup (applied settings)");
        inspect.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false, 3, 1));
        inspect.addListener(SWT.Selection, e -> org.evosuite.eclipse.launch.ConfigurationCheck.show(
                org.eclipse.jdt.core.JavaCore.create(project), getShell()));
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
        refresh();
    }

    @Override
    protected void performDefaults() {
        refreshing = true;
        try { super.performDefaults(); }
        finally { refreshing = false; }
        previewWorkspace = false;
        projectDraft = null;
        // As on JDT's pages, restoring the defaults also switches project-specific settings off.
        projectSettings.setEnabled(false);
        refresh();
    }

    @Override
    public void propertyChange(PropertyChangeEvent event) {
        super.propertyChange(event);
        refresh();
    }

    @Override
    public boolean performOk() {
        if (previewWorkspace && projectDraft != null) settings.loadFrom(projectDraft);
        getPreferenceStore().setValue(EvoSuitePreferences.USE_PROJECT_SETTINGS, projectSettings.isEnabled());
        boolean result = super.performOk();
        previewWorkspace = false;
        refresh();
        return result;
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
            refresh();
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

    private void refresh() {
        if (refreshing || settings == null) return;
        refreshing = true;
        try {
            boolean enabled = projectSettings.isEnabled();
            if (!enabled && !previewWorkspace) {
                projectDraft = settings.snapshot();
                settings.loadFrom(new ScopedPreferenceStore(org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE,
                        EvoSuitePreferences.QUALIFIER));
                previewWorkspace = true;
            } else if (enabled && previewWorkspace) {
                if (projectDraft != null) settings.loadFrom(projectDraft);
                previewWorkspace = false;
            }
            settings.refresh(enabled);
        } finally { refreshing = false; }
    }
}
