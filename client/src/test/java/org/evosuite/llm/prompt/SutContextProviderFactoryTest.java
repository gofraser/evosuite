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
import org.evosuite.Properties.LlmSutContextMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SutContextProviderFactoryTest {

    private LlmSutContextMode originalMode;
    private boolean originalFallback;
    private int originalMaxChars;

    @BeforeEach
    void saveProperties() {
        originalMode = Properties.LLM_SUT_CONTEXT_MODE;
        originalFallback = Properties.LLM_CONTEXT_FALLBACK_ENABLED;
        originalMaxChars = Properties.LLM_CONTEXT_MAX_CHARS;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_SUT_CONTEXT_MODE = originalMode;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = originalFallback;
        Properties.LLM_CONTEXT_MAX_CHARS = originalMaxChars;
    }

    @Test
    void selectsSignatureProviderForSignatureOnlyMode() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.SIGNATURE_ONLY;
        SutContextProviderFactory factory = new SutContextProviderFactory();
        SutContextProvider provider = factory.providerFor(LlmSutContextMode.SIGNATURE_ONLY);
        assertTrue(provider instanceof SignatureContextProvider);
    }

    @Test
    void selectsBytecodeProviderForBytecodeMode() {
        SutContextProviderFactory factory = new SutContextProviderFactory();
        SutContextProvider provider = factory.providerFor(LlmSutContextMode.BYTECODE_DISASSEMBLED);
        assertTrue(provider instanceof BytecodeContextProvider);
    }

    @Test
    void selectsDecompiledProviderForDecompiledMode() {
        SutContextProviderFactory factory = new SutContextProviderFactory();
        SutContextProvider provider = factory.providerFor(LlmSutContextMode.DECOMPILED_SOURCE);
        assertTrue(provider instanceof DecompiledContextProvider);
    }

    @Test
    void selectsSourceCodeProviderForSourceMode() {
        SutContextProviderFactory factory = new SutContextProviderFactory();
        SutContextProvider provider = factory.providerFor(LlmSutContextMode.SOURCE_CODE);
        assertTrue(provider instanceof SourceCodeContextProvider);
    }

    @Test
    void fallbackToSignatureWhenPrimaryFails() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.SOURCE_CODE;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = true;

        SutContextProvider failingProvider = new FailingProvider();
        SutContextProvider signatureProvider = new StubProvider("signature output");

        SutContextProviderFactory factory = new SutContextProviderFactory(
                signatureProvider, failingProvider, failingProvider, failingProvider);

        SutContextProviderFactory.ContextResult result = factory.getContext("com.example.Foo", null);
        assertEquals(LlmSutContextMode.SIGNATURE_ONLY, result.getModeUsed());
        assertEquals("signature output", result.getText());
        assertFalse(result.isContextUnavailable());
    }

    @Test
    void strictModeMarksContextUnavailableWhenPrimaryFails() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.SOURCE_CODE;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = false;

        SutContextProvider failingProvider = new FailingProvider();

        SutContextProviderFactory factory = new SutContextProviderFactory(
                failingProvider, failingProvider, failingProvider, failingProvider);

        SutContextProviderFactory.ContextResult result = factory.getContext("com.example.Foo", null);
        assertEquals(LlmSutContextMode.SOURCE_CODE, result.getModeUsed());
        assertTrue(result.isContextUnavailable());
        assertEquals("", result.getText());
    }

    @Test
    void truncatesContextToMaxChars() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.BYTECODE_DISASSEMBLED;
        Properties.LLM_CONTEXT_MAX_CHARS = 10;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = true;

        String longContext = "abcdefghijklmnopqrstuvwxyz";
        SutContextProvider stubProvider = new StubProvider(longContext);

        SutContextProviderFactory factory = new SutContextProviderFactory(
                stubProvider, stubProvider, stubProvider, stubProvider);

        SutContextProviderFactory.ContextResult result = factory.getContext("com.example.Foo", null);
        assertTrue(result.getText().startsWith("abcdefghij"));
        assertTrue(result.getText().contains("(truncated)"));
        assertEquals(10 + "\n... (truncated)".length(), result.getText().length());
    }

    @Test
    void noTruncationWhenMaxCharsIsZero() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.BYTECODE_DISASSEMBLED;
        Properties.LLM_CONTEXT_MAX_CHARS = 0;

        String context = "short";
        SutContextProvider stubProvider = new StubProvider(context);
        SutContextProviderFactory factory = new SutContextProviderFactory(
                stubProvider, stubProvider, stubProvider, stubProvider);

        SutContextProviderFactory.ContextResult result = factory.getContext("com.example.Foo", null);
        assertEquals("short", result.getText());
    }

    @Test
    void primarySuccessReturnsModeWithoutFallback() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.DECOMPILED_SOURCE;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = true;

        SutContextProvider stubDecompiled = new StubProvider("decompiled output");
        SutContextProvider stubSignature = new StubProvider("signature output");

        SutContextProviderFactory factory = new SutContextProviderFactory(
                stubSignature, stubSignature, stubDecompiled, stubSignature);

        SutContextProviderFactory.ContextResult result = factory.getContext("com.example.Foo", null);
        assertEquals(LlmSutContextMode.DECOMPILED_SOURCE, result.getModeUsed());
        assertEquals("decompiled output", result.getText());
        assertFalse(result.isContextUnavailable());
    }

    @Test
    void exceptionInProviderTreatedAsUnavailable() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.BYTECODE_DISASSEMBLED;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = true;

        SutContextProvider throwingProvider = new ThrowingProvider();
        SutContextProvider stubSignature = new StubProvider("fallback");

        SutContextProviderFactory factory = new SutContextProviderFactory(
                stubSignature, throwingProvider, throwingProvider, throwingProvider);

        SutContextProviderFactory.ContextResult result = factory.getContext("com.example.Foo", null);
        assertEquals(LlmSutContextMode.SIGNATURE_ONLY, result.getModeUsed());
        assertEquals("fallback", result.getText());
    }

    @Test
    void signatureOnlyModeDoesNotFallbackFurther() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.SIGNATURE_ONLY;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = true;

        SutContextProvider failingProvider = new FailingProvider();
        SutContextProviderFactory factory = new SutContextProviderFactory(
                failingProvider, failingProvider, failingProvider, failingProvider);

        SutContextProviderFactory.ContextResult result = factory.getContext("com.example.Foo", null);
        assertEquals(LlmSutContextMode.SIGNATURE_ONLY, result.getModeUsed());
        assertEquals("", result.getText());
        assertFalse(result.isContextUnavailable());
    }

    @Test
    void signatureOnlyStrictModeMarksUnavailable() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.SIGNATURE_ONLY;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = false;

        SutContextProvider failingProvider = new FailingProvider();
        SutContextProviderFactory factory = new SutContextProviderFactory(
                failingProvider, failingProvider, failingProvider, failingProvider);

        SutContextProviderFactory.ContextResult result = factory.getContext("com.example.Foo", null);
        assertTrue(result.isContextUnavailable());
    }

    @Test
    void cachesResultAcrossMultipleCalls() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.BYTECODE_DISASSEMBLED;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = true;

        CountingProvider countingProvider = new CountingProvider("bytecode");
        SutContextProviderFactory factory = new SutContextProviderFactory(
                countingProvider, countingProvider, countingProvider, countingProvider);

        factory.getContext("com.example.Foo", null);
        factory.getContext("com.example.Foo", null);
        factory.getContext("com.example.Foo", null);

        assertEquals(1, countingProvider.callCount, "Provider should only be called once due to caching");
    }

    @Test
    void clearCacheAllowsRecomputation() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.SOURCE_CODE;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = true;

        CountingProvider countingProvider = new CountingProvider("source");
        SutContextProviderFactory factory = new SutContextProviderFactory(
                countingProvider, countingProvider, countingProvider, countingProvider);

        factory.getContext("com.example.Foo", null);
        factory.clearCache();
        factory.getContext("com.example.Foo", null);

        assertEquals(2, countingProvider.callCount, "Provider should be called again after cache clear");
    }

    // --- Test helpers ---

    private static class StubProvider implements SutContextProvider {
        private final String output;
        StubProvider(String output) { this.output = output; }
        @Override
        public Optional<String> getContext(String className, org.evosuite.setup.TestCluster cluster) {
            return Optional.of(output);
        }
        @Override
        public String modeLabel() { return "stub"; }
    }

    private static class FailingProvider implements SutContextProvider {
        @Override
        public Optional<String> getContext(String className, org.evosuite.setup.TestCluster cluster) {
            return Optional.empty();
        }
        @Override
        public String modeLabel() { return "failing"; }
    }

    private static class ThrowingProvider implements SutContextProvider {
        @Override
        public Optional<String> getContext(String className, org.evosuite.setup.TestCluster cluster) {
            throw new RuntimeException("provider crashed");
        }
        @Override
        public String modeLabel() { return "throwing"; }
    }

    private static class CountingProvider implements SutContextProvider {
        private final String output;
        int callCount = 0;
        CountingProvider(String output) { this.output = output; }
        @Override
        public Optional<String> getContext(String className, org.evosuite.setup.TestCluster cluster) {
            callCount++;
            return Optional.of(output);
        }
        @Override
        public String modeLabel() { return "counting"; }
    }
}
