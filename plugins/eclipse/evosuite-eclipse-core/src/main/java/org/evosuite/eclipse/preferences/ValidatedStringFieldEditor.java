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

import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.widgets.Composite;

import java.util.function.Predicate;

/** A text setting whose domain is more specific than a nonempty string. */
final class ValidatedStringFieldEditor extends StringFieldEditor {
    private final Predicate<String> validator;

    ValidatedStringFieldEditor(
            String key, String label, Composite parent, Predicate<String> validator, String error) {
        super(key, label, parent);
        this.validator = validator;
        setErrorMessage(error);
        setValidateStrategy(VALIDATE_ON_KEY_STROKE);
        refreshValidState();
    }

    @Override
    protected boolean doCheckState() {
        return validator == null || validator.test(getStringValue());
    }
}
