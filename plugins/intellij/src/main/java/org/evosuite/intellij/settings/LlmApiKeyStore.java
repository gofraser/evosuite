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
package org.evosuite.intellij.settings;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.ide.passwordSafe.PasswordSafe;

/** Call on a pooled thread: PasswordSafe may prompt or access the OS keychain. */
public final class LlmApiKeyStore {
    private static final CredentialAttributes ATTRIBUTES =
            new CredentialAttributes("EvoSuite LLM API key");

    private LlmApiKeyStore() {}

    public static String get() {
        String key = PasswordSafe.getInstance().getPassword(ATTRIBUTES);
        return key == null ? "" : key;
    }

    public static void set(String key) {
        PasswordSafe.getInstance().setPassword(ATTRIBUTES, key.isBlank() ? null : key);
    }
}
