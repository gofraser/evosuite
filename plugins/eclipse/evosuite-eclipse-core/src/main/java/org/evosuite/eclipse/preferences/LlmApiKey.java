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

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.evosuite.eclipse.Activator;

/**
 * The LLM API key, kept in Eclipse's secure storage rather than with the other settings: those
 * can be committed with a project, and a key must never be. One key serves the workspace.
 */
public final class LlmApiKey {

    private static final String NODE = Activator.PLUGIN_ID;
    private static final String KEY = "llmApiKey";

    private LlmApiKey() {
    }

    /** The stored key, or an empty string. */
    public static String get() {
        try {
            ISecurePreferences node = node();
            return node == null ? "" : node.get(KEY, "");
        } catch (StorageException e) {
            Activator.logError("Cannot read the LLM API key from secure storage", e);
            return "";
        }
    }

    /** Stores {@code key}, or removes the stored key if it is empty. */
    public static void set(String key) throws StorageException {
        ISecurePreferences node = node();
        if (node == null) {
            throw new StorageException(StorageException.INTERNAL_ERROR, "Secure storage is not available");
        }
        if (key == null || key.isBlank()) {
            node.remove(KEY);
        } else {
            node.put(KEY, key.trim(), true);
        }
        try {
            node.flush();
        } catch (java.io.IOException e) {
            throw new StorageException(StorageException.INTERNAL_ERROR, e);
        }
    }

    private static ISecurePreferences node() {
        ISecurePreferences root = SecurePreferencesFactory.getDefault();
        return root == null ? null : root.node(NODE);
    }
}
