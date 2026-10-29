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
import org.eclipse.jface.util.PropertyChangeEvent;
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
    private SettingsFields settings;

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
        settings = SettingsFields.create(section, getFieldEditorParent(), true, this::addField);
    }

    @Override
    protected void initialize() {
        super.initialize();
        settings.refresh(true);
    }

    @Override
    protected void performDefaults() {
        // Editors need not report the defaults they load, so the enablement is updated here too.
        super.performDefaults();
        settings.refresh(true);
    }

    @Override
    public void propertyChange(PropertyChangeEvent event) {
        super.propertyChange(event);
        settings.refresh(true);
    }

    @Override
    public boolean performOk() {
        return settings.saveKeys() && super.performOk();
    }

    SettingsFields settings() {
        return settings;
    }
}
