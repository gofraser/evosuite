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
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.evosuite.ide.GenerationOptions;

/**
 * The LLM API key, kept in Eclipse's secure storage rather than with the other settings: those
 * can be committed with a project, and a key must never be. Each provider has its own workspace key.
 */
public final class LlmApiKey {

    private static final String NODE = Activator.PLUGIN_ID;
    private static final String KEY = "llmApiKey";

    /** Where a key is kept. */
    interface Store {
        /** The stored key, or an empty string. */
        String read(String provider) throws StorageException;

        /** Stores a key, or removes the stored key if {@code key} is empty. */
        void write(String provider, String key) throws StorageException;
    }

    /** Secure storage; tests replace it, so that they neither touch it nor meet its prompts. */
    static Store store = new SecureStore();

    private LlmApiKey() {
    }

    /** The stored key, or an empty string, also if secure storage cannot be read. */
    public static String get(String provider) {
        try {
            return read(provider);
        } catch (StorageException e) {
            Activator.logError("Cannot read the LLM API key from secure storage", e);
            return "";
        }
    }

    /**
     * The stored key, or an empty string.
     *
     * @throws StorageException if secure storage cannot be read, eg because the user cancelled
     *                          the prompt for its password
     */
    static String read(String provider) throws StorageException {
        if (!GenerationOptions.LLM_OPENAI.equals(provider) && !GenerationOptions.LLM_ANTHROPIC.equals(provider)) {
            return "";
        }
        return store.read(provider);
    }

    /** Stores {@code key}, or removes the stored key if it is empty. */
    public static void set(String provider, String key) throws StorageException {
        store.write(provider, key == null ? "" : key.trim());
    }

    private static final class SecureStore implements Store {

        @Override
        public synchronized String read(String provider) throws StorageException {
            ISecurePreferences node = node();
            if (node == null) return "";
            String key = node.get(KEY + "." + provider, null);
            if (key != null) return key;
            String legacy = node.get(KEY, "");
            if (legacy.isBlank()) return "";
            String legacyProvider = node.get(KEY + ".provider", "");
            if (legacyProvider.isBlank()) {
                legacyProvider = InstanceScope.INSTANCE.getNode(NODE)
                        .get(EvoSuitePreferences.LLM_PROVIDER, GenerationOptions.LLM_NONE);
                node.put(KEY + ".provider", legacyProvider, false);
                flush(node);
            }
            return provider.equals(legacyProvider) ? legacy : "";
        }

        @Override
        public synchronized void write(String provider, String key) throws StorageException {
            ISecurePreferences node = node();
            if (node == null) {
                throw new StorageException(StorageException.INTERNAL_ERROR, "Secure storage is not available");
            }
            node.put(KEY + "." + provider, key, !key.isEmpty());
            if (provider.equals(node.get(KEY + ".provider", ""))) {
                node.remove(KEY);
                node.remove(KEY + ".provider");
            }
            flush(node);
        }

        private static void flush(ISecurePreferences node) throws StorageException {
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
}
