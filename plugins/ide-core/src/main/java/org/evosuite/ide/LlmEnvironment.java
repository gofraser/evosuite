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
package org.evosuite.ide;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Keys never belong in persisted options. The adapters supply their secure stores. */
public final class LlmEnvironment {
    private LlmEnvironment() {}

    public static Map<String, String> resolve(
            GenerationOptions options,
            Supplier<String> stored,
            Supplier<String> maven,
            Map<String, String> inherited,
            Consumer<String> console) {
        String provider = options.effectiveLlmProvider();
        if (provider.isBlank() || GenerationOptions.LLM_NONE.equals(provider)) return Map.of();
        // The engine resolves EVOSUITE_LLM_MODEL and provider defaults, including local models.
        String key = options.effectiveLlmApiKey();
        if (key.isBlank()) key = options.llmApiKeyServerId.isBlank() ? "" : maven.get();
        if ((key == null || key.isBlank()) && GenerationOptions.LLM_OLLAMA.equals(provider))
            return Map.of(); // A local provider does not need the shared cloud-provider credential.
        if (key == null || key.isBlank()) {
            try {
                key = stored.get();
            } catch (RuntimeException ex) {
                console.accept(
                        "Secure storage could not be read; using the inherited LLM environment if"
                            + " set.");
            }
        }
        if (key != null && !key.isBlank()) return Map.of(EvoSuiteCommand.API_KEY_VARIABLE, key);
        if (!GenerationOptions.LLM_OLLAMA.equals(provider)
                && inherited.getOrDefault(EvoSuiteCommand.API_KEY_VARIABLE, "").isBlank())
            console.accept(
                    "No LLM API key is set. Configure one in Tools > EvoSuite, or set "
                            + EvoSuiteCommand.API_KEY_VARIABLE
                            + ".");
        return Map.of();
    }
}
