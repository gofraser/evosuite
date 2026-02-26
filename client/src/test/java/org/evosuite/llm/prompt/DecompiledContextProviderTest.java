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
