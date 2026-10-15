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

import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.ui.IWorkbenchPropertyPage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

/**
 * Project-specific settings, stored in the project's {@code .settings} folder. When enabled they
 * replace the workspace preferences entirely; settings left unchanged take EvoSuite's defaults.
 */
public class EvoSuitePropertyPage extends FieldEditorPreferencePage implements IWorkbenchPropertyPage {

    private IAdaptable element;
    private BooleanFieldEditor useProjectSettings;
    private List<FieldEditor> settings = List.of();

    public EvoSuitePropertyPage() {
        super(GRID);
    }

    @Override
    public IAdaptable getElement() {
        return element;
    }

    @Override
    public void setElement(IAdaptable element) {
        this.element = element;
        IProject project = Adapters.adapt(element, IProject.class);
        setPreferenceStore(new ScopedPreferenceStore(new ProjectScope(project), EvoSuitePreferences.QUALIFIER));
    }

    @Override
    protected void createFieldEditors() {
        useProjectSettings = new BooleanFieldEditor(EvoSuitePreferences.USE_PROJECT_SETTINGS,
                "Enable pro&ject-specific settings", getFieldEditorParent());
        addField(useProjectSettings);
        settings = SettingsFields.create(getFieldEditorParent(), this::addField);
    }

    @Override
    protected void initialize() {
        super.initialize();
        updateEnablement(useProjectSettings.getBooleanValue());
    }

    @Override
    protected void performDefaults() {
        // Restoring the checkbox's default fires no change event, so update the fields here.
        super.performDefaults();
        updateEnablement(useProjectSettings.getBooleanValue());
    }

    @Override
    public void propertyChange(PropertyChangeEvent event) {
        super.propertyChange(event);
        if (event.getSource() == useProjectSettings && FieldEditor.VALUE.equals(event.getProperty())) {
            updateEnablement((Boolean) event.getNewValue());
        }
    }

    private void updateEnablement(boolean enabled) {
        for (FieldEditor editor : settings) {
            editor.setEnabled(enabled, getFieldEditorParent());
        }
    }
}
