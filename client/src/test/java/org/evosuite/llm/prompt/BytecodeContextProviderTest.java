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
