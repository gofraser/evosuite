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

import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.widgets.Composite;
import org.evosuite.eclipse.Activator;

/**
 * Edits the LLM API key in secure storage. It ignores the page's preference store, so that the
 * key never lands in a preferences file, and masks what is typed.
 */
class ApiKeyFieldEditor extends StringFieldEditor {

    ApiKeyFieldEditor(String label, Composite parent) {
        super("llmApiKey.secure", label, parent);
        getTextControl(parent).setEchoChar('•');
    }

    @Override
    protected void doLoad() {
        getTextControl().setText(LlmApiKey.get());
    }

    @Override
    protected void doLoadDefault() {
        getTextControl().setText("");
    }

    /** Always stores: the default implementation would reset the page's store instead. */
    @Override
    public void store() {
        doStore();
    }

    @Override
    protected void doStore() {
        try {
            LlmApiKey.set(getStringValue());
        } catch (StorageException e) {
            Activator.logError("Cannot store the LLM API key in secure storage", e);
        }
    }
}
