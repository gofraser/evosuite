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

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BytecodeContextProviderTest {

    @Test
    void returnsEmptyForNullClassName() {
        BytecodeContextProvider provider = new BytecodeContextProvider();
        assertEquals(Optional.empty(), provider.getContext(null, null));
    }

    @Test
    void returnsEmptyForEmptyClassName() {
        BytecodeContextProvider provider = new BytecodeContextProvider();
        assertEquals(Optional.empty(), provider.getContext("  ", null));
    }

    @Test
    void disassemblesStandardLibraryClass() {
        BytecodeContextProvider provider = new BytecodeContextProvider();
        Optional<String> result = provider.getContext("java.util.ArrayList", null);
        assertTrue(result.isPresent(), "Should disassemble ArrayList");
        assertTrue(result.get().contains("ArrayList"), "Output should mention ArrayList");
    }

    @Test
    void returnsEmptyForNonExistentClass() {
        BytecodeContextProvider provider = new BytecodeContextProvider();
        Optional<String> result = provider.getContext("com.nonexistent.NoSuchClass12345", null);
        assertFalse(result.isPresent());
    }

    @Test
    void modeLabelIsCorrect() {
        BytecodeContextProvider provider = new BytecodeContextProvider();
        assertEquals("Disassembled bytecode", provider.modeLabel());
    }
}
