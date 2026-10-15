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

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.evosuite.eclipse.preferences.SettingsFields.Section;

/**
 * Workspace-wide defaults, used by every project without project-specific settings. This page
 * holds the general settings; its sub-pages hold the others.
 */
public class EvoSuitePreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    private final Section section;

    public EvoSuitePreferencePage() {
        this(Section.GENERAL, "Defaults for EvoSuite test generation. Projects can override them under "
                + "Project > Properties > EvoSuite.");
    }

    EvoSuitePreferencePage(Section section, String description) {
        super(GRID);
        this.section = section;
        setDescription(description);
    }

    @Override
    public void init(IWorkbench workbench) {
        setPreferenceStore(new ScopedPreferenceStore(InstanceScope.INSTANCE, EvoSuitePreferences.QUALIFIER));
    }

    @Override
    protected void createFieldEditors() {
        SettingsFields.create(section, getFieldEditorParent(), true, this::addField);
    }
}
