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

import java.util.Arrays;
import org.eclipse.jface.preference.FieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.*;

/** A persisted choice that retains unknown values for validation and exposes its current value. */
class ChoiceFieldEditor extends FieldEditor {
    private final String[][] entries;
    private Combo combo;
    private String value = "";
    private boolean valid = true;
    ChoiceFieldEditor(String name, String label, String[][] entries, Composite parent) {
        this.entries = entries;
        init(name, label);
        createControl(parent);
    }
    String getValue() { return value; }
    @Override protected void adjustForNumColumns(int columns) {
        ((GridData) combo.getLayoutData()).horizontalSpan = columns - 1;
    }
    @Override protected void doFillIntoGrid(Composite parent, int columns) {
        getLabelControl(parent);
        combo = new Combo(parent, SWT.READ_ONLY);
        combo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, columns - 1, 1));
        combo.setItems(Arrays.stream(entries).map(e -> e[0]).toArray(String[]::new));
        combo.addListener(SWT.Selection, e -> {
            String old = value;
            if (combo.getSelectionIndex() < entries.length) value = entries[combo.getSelectionIndex()][1];
            setPresentsDefaultValue(false);
            validate();
            fireValueChanged(VALUE, old, value);
        });
    }
    private void show(String stored) {
        value = stored;
        combo.setItems(Arrays.stream(entries).map(e -> e[0]).toArray(String[]::new));
        int selected = -1;
        for (int i = 0; i < entries.length; i++) if (entries[i][1].equals(stored)) selected = i;
        if (selected < 0) { combo.add("Unsupported: " + stored); selected = entries.length; }
        combo.select(selected);
        validate();
    }
    private void validate() {
        boolean old = valid;
        valid = Arrays.stream(entries).anyMatch(e -> e[1].equals(value));
        if (valid) clearErrorMessage(); else showErrorMessage("Choose a supported value for " + getLabelText());
        if (old != valid) fireStateChanged(IS_VALID, old, valid);
    }
    @Override protected void doLoad() { show(getPreferenceStore().getString(getPreferenceName())); }
    @Override protected void doLoadDefault() { show(getPreferenceStore().getDefaultString(getPreferenceName())); }
    @Override protected void doStore() { getPreferenceStore().setValue(getPreferenceName(), value); }
    @Override public int getNumberOfControls() { return 2; }
    @Override public boolean isValid() { return valid; }
    @Override public void setFocus() { combo.setFocus(); }
    @Override public void setEnabled(boolean enabled, Composite parent) { super.setEnabled(enabled, parent); combo.setEnabled(enabled); }
}
