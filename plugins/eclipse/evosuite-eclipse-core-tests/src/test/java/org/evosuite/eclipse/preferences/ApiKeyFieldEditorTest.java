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

import static org.evosuite.eclipse.preferences.SettingsEnablementTest.control;
import static org.evosuite.eclipse.preferences.SettingsEnablementTest.page;
import static org.evosuite.eclipse.preferences.SettingsEnablementTest.select;
import static org.evosuite.eclipse.preferences.SettingsPagesLayoutTest.onUiThread;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.widgets.Text;
import org.evosuite.eclipse.preferences.SettingsFields.Section;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The API key field only writes to secure storage what the user changed. */
public class ApiKeyFieldEditorTest {

    /** In place of secure storage, which may prompt for a password. */
    private static final class FakeStore implements LlmApiKey.Store {
        String key = "";
        boolean unreadable;
        final List<String> writes = new ArrayList<>();

        @Override
        public String read() throws StorageException {
            if (unreadable) {
                throw new StorageException(StorageException.NO_PASSWORD, "cancelled");
            }
            return key;
        }

        @Override
        public void write(String key) {
            writes.add(key);
            this.key = key;
        }
    }

    private final IEclipsePreferences workspace = InstanceScope.INSTANCE.getNode(EvoSuitePreferences.QUALIFIER);
    private final FakeStore store = new FakeStore();
    private LlmApiKey.Store previous;

    @BeforeEach
    public void useFakeStore() {
        previous = LlmApiKey.store;
        LlmApiKey.store = store;
    }

    @AfterEach
    public void restore() throws Exception {
        LlmApiKey.store = previous;
        workspace.clear();
    }

    /** Opens the LLM page with OpenAI, which takes a key, runs {@code edit} and presses OK. */
    private static void edit(Consumer<PreferenceDialog> edit) {
        onUiThread(() -> {
            PreferenceDialog dialog = SettingsDialogs.preferences(Section.LLM);
            try {
                select(page(dialog), "Provider", "OpenAI");
                edit.accept(dialog);
                SettingsDialogs.ok(dialog);
            } finally {
                dialog.close();
            }
        });
    }

    private static Text key(PreferenceDialog dialog) {
        return (Text) control(page(dialog), "API key");
    }

    @Test
    public void aKeyThatCannotBeReadIsNotRemoved() {
        store.key = "sk-stored";
        store.unreadable = true;
        edit(dialog -> {
            assertEquals("", key(dialog).getText());
            assertEquals("The stored key cannot be read; enter a key to replace it", key(dialog).getMessage());
            // Changing another setting stores the page.
            ((Text) control(page(dialog), "Model")).setText("gpt-5");
        });
        assertEquals(List.of(), store.writes);
        assertEquals("gpt-5", workspace.get(EvoSuitePreferences.LLM_MODEL, null));

        edit(SettingsDialogs::restoreDefaults);
        assertEquals(List.of(), store.writes, "nor by restoring the defaults");

        edit(dialog -> key(dialog).setText("sk-new"));
        assertEquals(List.of("sk-new"), store.writes, "a new key replaces it");
    }

    @Test
    public void restoringTheDefaultsKeepsTheKey() {
        store.key = "sk-stored";
        edit(dialog -> {
            key(dialog).setText("sk-typed");
            SettingsDialogs.restoreDefaults(dialog);
            assertEquals("sk-stored", key(dialog).getText());
        });
        assertEquals(List.of(), store.writes);
    }

    @Test
    public void onlyAChangedKeyIsWritten() {
        store.key = "sk-stored";
        edit(dialog -> assertEquals("sk-stored", key(dialog).getText()));
        assertEquals(List.of(), store.writes, "unchanged");

        edit(dialog -> key(dialog).setText(" sk-other "));
        edit(dialog -> key(dialog).setText(""));
        assertEquals(List.of("sk-other", ""), store.writes, "a new key, then removing it");
    }
}
