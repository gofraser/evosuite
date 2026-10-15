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

import org.eclipse.jface.preference.ComboFieldEditor;
import org.eclipse.swt.widgets.Composite;

/**
 * A combo box whose current value other editors can depend on. {@link ComboFieldEditor} keeps
 * its value to itself and tells listeners only when the user changes it, not when it loads.
 */
class ChoiceFieldEditor extends ComboFieldEditor {

    private final String[][] entries;
    private String value;

    /** @param entries pairs of the name shown and the value stored */
    ChoiceFieldEditor(String name, String label, String[][] entries, Composite parent) {
        super(name, label, entries, parent);
        this.entries = entries;
        this.value = entries[0][1];
    }

    /** The value selected, which is what {@link #store()} would store. */
    String getValue() {
        return value;
    }

    @Override
    protected void doLoad() {
        super.doLoad();
        value = known(getPreferenceStore().getString(getPreferenceName()));
    }

    @Override
    protected void doLoadDefault() {
        super.doLoadDefault();
        value = known(getPreferenceStore().getDefaultString(getPreferenceName()));
    }

    @Override
    protected void fireValueChanged(String property, Object oldValue, Object newValue) {
        if (VALUE.equals(property)) {
            value = (String) newValue;
        }
        super.fireValueChanged(property, oldValue, newValue);
    }

    /** What the combo box shows for a stored value: the first entry for an unknown value. */
    private String known(String stored) {
        return Arrays.stream(entries).map(e -> e[1]).filter(v -> v.equals(stored)).findFirst().orElse(entries[0][1]);
    }
}
