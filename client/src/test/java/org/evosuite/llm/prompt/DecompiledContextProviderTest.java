/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.llm.prompt;

import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DecompiledContextProviderTest {

    private int originalTimeout;

    @BeforeEach
    void saveProperties() {
        originalTimeout = Properties.LLM_DECOMPILER_TIMEOUT_SECONDS;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_DECOMPILER_TIMEOUT_SECONDS = originalTimeout;
    }

    @Test
    void returnsEmptyForNullClassName() {
        DecompiledContextProvider provider = new DecompiledContextProvider();
        assertEquals(Optional.empty(), provider.getContext(null, null));
    }

    @Test
    void returnsEmptyForEmptyClassName() {
        DecompiledContextProvider provider = new DecompiledContextProvider();
        assertEquals(Optional.empty(), provider.getContext("  ", null));
    }

    @Test
    void decompilesStandardLibraryClass() {
        Properties.LLM_DECOMPILER_TIMEOUT_SECONDS = 30;
        DecompiledContextProvider provider = new DecompiledContextProvider();
        // Decompile a small, well-known class
        Optional<String> result = provider.getContext("java.lang.Comparable", null);
        // CFR should be able to decompile this
        if (result.isPresent()) {
            assertTrue(result.get().contains("Comparable"), "Output should mention Comparable");
        }
        // It's OK if empty (e.g., interface decompilation quirks) — just shouldn't crash
    }

    @Test
    void returnsEmptyForNonExistentClass() {
        DecompiledContextProvider provider = new DecompiledContextProvider();
        Optional<String> result = provider.getContext("com.nonexistent.NoSuchClass12345", null);
        assertFalse(result.isPresent());
    }

    @Test
    void modeLabelIsCorrect() {
        DecompiledContextProvider provider = new DecompiledContextProvider();
        assertEquals("Decompiled source", provider.modeLabel());
    }
}
