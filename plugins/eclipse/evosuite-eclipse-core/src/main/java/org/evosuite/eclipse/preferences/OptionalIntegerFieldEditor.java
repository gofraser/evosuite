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

import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;

/**
 * A number for which 0 means EvoSuite's default. The field shows 0 as empty, so that its hint,
 * which says what the default is, shows instead, and stores an empty field as 0.
 */
class OptionalIntegerFieldEditor extends IntegerFieldEditor {

    OptionalIntegerFieldEditor(String name, String label, Composite parent, int max, String hint) {
        super(name, label, parent);
        setValidRange(0, max);
        setEmptyStringAllowed(true);
        getTextControl(parent).setMessage(hint);
    }

    @Override
    protected boolean checkState() {
        if (getTextControl() != null && getTextControl().getText().isBlank()) {
            clearErrorMessage();
            return true;
        }
        return super.checkState();
    }

    @Override
    protected void doLoad() {
        super.doLoad();
        blankZero();
    }

    @Override
    protected void doLoadDefault() {
        super.doLoadDefault();
        blankZero();
    }

    @Override
    protected void doStore() {
        Text text = getTextControl();
        if (text != null && text.getText().isBlank()) {
            getPreferenceStore().setValue(getPreferenceName(), 0);
        } else {
            super.doStore();
        }
    }

    @Override
    public int getIntValue() throws NumberFormatException {
        return getStringValue().isBlank() ? 0 : super.getIntValue();
    }

    private void blankZero() {
        Text text = getTextControl();
        if (text != null && "0".equals(text.getText().trim())) {
            text.setText("");
            oldValue = "";
        }
    }
}
