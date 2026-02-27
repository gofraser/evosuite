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
package org.evosuite.llm;

import org.evosuite.Properties;
import org.evosuite.Properties.LlmSutContextMode;
import org.evosuite.llm.prompt.PromptBuilder;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.prompt.SutContextProvider;
import org.evosuite.llm.prompt.SutContextProviderFactory;
import org.evosuite.llm.prompt.SystemPromptProvider;
import org.evosuite.llm.prompt.TestClusterSummarizer;
import org.evosuite.llm.prompt.SourceCodeProvider;
import org.evosuite.llm.prompt.CoverageGoalFormatter;
import org.evosuite.llm.prompt.TestCaseFormatter;
import org.evosuite.setup.TestCluster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase 2c review-fix pass:
 * A) end-to-end trace propagation
 * B) shared caching across PromptBuilder instances
 * C) fallback failure semantics
 * D) safe property defaults
 */
class Phase2cReviewFixTest {

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
        SutContextProviderFactory.resetInstanceForTesting();
    }

    // --- D) Property defaults ---

    @Test
    void defaultContextModeIsSignatureOnly() {
        // Reset to class-level default by reading the annotation default
        // The field itself is what we test
        assertEquals(LlmSutContextMode.SIGNATURE_ONLY, originalMode,
                "Default LLM_SUT_CONTEXT_MODE should be SIGNATURE_ONLY for safe experiment baseline");
    }

    @Test
    void defaultMaxCharsIsNonZero() {
        assertTrue(originalMaxChars > 0,
                "Default LLM_CONTEXT_MAX_CHARS should be non-zero for cost control (was: " + originalMaxChars + ")");
        assertEquals(32000, originalMaxChars,
                "Default LLM_CONTEXT_MAX_CHARS should be 32000");
    }

    // --- C) Fallback failure semantics ---

    @Test
    void fallbackEnabledButBothFail_marksContextUnavailable() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.DECOMPILED_SOURCE;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = true;

        SutContextProvider failing = new FailingProvider();
        SutContextProviderFactory factory = new SutContextProviderFactory(
                failing, failing, failing, failing);

        SutContextProviderFactory.ContextResult result = factory.getContext("com.example.Foo", null);
        assertEquals(LlmSutContextMode.SIGNATURE_ONLY, result.getModeUsed());
        assertTrue(result.isContextUnavailable(),
                "When primary and fallback both fail, context should be marked unavailable");
        assertEquals("", result.getText());
    }

    @Test
    void fallbackEnabledPrimaryFailsFallbackSucceeds_notUnavailable() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.DECOMPILED_SOURCE;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = true;

        SutContextProvider failing = new FailingProvider();
        SutContextProvider sig = new StubProvider("sig output");
        SutContextProviderFactory factory = new SutContextProviderFactory(
                sig, failing, failing, failing);

        SutContextProviderFactory.ContextResult result = factory.getContext("com.example.Foo", null);
        assertEquals(LlmSutContextMode.SIGNATURE_ONLY, result.getModeUsed());
        assertFalse(result.isContextUnavailable());
        assertEquals("sig output", result.getText());
    }

    // --- B) Shared caching across PromptBuilder instances ---

    @Test
    void sharedFactoryInstanceAcrossPromptBuilders() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.BYTECODE_DISASSEMBLED;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = true;
        Properties.LLM_CONTEXT_MAX_CHARS = 0;

        CountingProvider counting = new CountingProvider("cached bytecode");
        SutContextProviderFactory shared = new SutContextProviderFactory(
                counting, counting, counting, counting);
        SutContextProviderFactory.setInstanceForTesting(shared);

        // Two different PromptBuilder instances (simulating two call sites)
        PromptBuilder builder1 = new PromptBuilder();
        builder1.withSutContext("com.example.Foo", null);

        PromptBuilder builder2 = new PromptBuilder();
        builder2.withSutContext("com.example.Foo", null);

        assertEquals(1, counting.callCount,
                "Provider should only be called once thanks to shared factory caching");
    }

    // --- A) End-to-end trace propagation ---

    @Test
    void buildWithMetadataCarriesContextMode() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.BYTECODE_DISASSEMBLED;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = true;
        Properties.LLM_CONTEXT_MAX_CHARS = 0;

        StubProvider stub = new StubProvider("bytecode content");
        SutContextProviderFactory factory = new SutContextProviderFactory(
                stub, stub, stub, stub);

        PromptBuilder builder = new PromptBuilder(
                new SystemPromptProvider(),
                new TestClusterSummarizer(),
                new SourceCodeProvider(),
                new CoverageGoalFormatter(),
                new TestCaseFormatter(),
                factory);

        PromptResult result = builder
                .withSystemPrompt()
                .withSutContext("com.example.Foo", null)
                .buildWithMetadata();

        assertNotNull(result.getMessages());
        assertEquals(2, result.getMessages().size());
        assertEquals(LlmSutContextMode.BYTECODE_DISASSEMBLED, result.getSutContextMode());
        assertFalse(result.isContextUnavailable());
    }

    @Test
    void queryWithPromptResultPropagatesMetadataToTrace(@TempDir Path tempDir) throws Exception {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.BYTECODE_DISASSEMBLED;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = true;
        Properties.LLM_CONTEXT_MAX_CHARS = 0;

        LlmConfiguration config = new LlmConfiguration(
                Properties.LlmProvider.OPENAI,
                "mock-model", "", "", 0.0, 256, 30, 0, 1,
                true, tempDir, "run-e2e-trace");

        LlmTraceRecorder recorder = new LlmTraceRecorder(config);

        LlmService.ChatLanguageModel model = new LlmService.ChatLanguageModel() {
            @Override
            public LlmService.LlmResponse generate(List<LlmMessage> messages, LlmFeature feature) {
                return new LlmService.LlmResponse("test response", 10, 5);
            }
        };

        LlmBudgetCoordinator.Local budget = new LlmBudgetCoordinator.Local(5);
        LlmService service = new LlmService(model, budget, config, new LlmStatistics(), recorder);

        try {
            // Build a PromptResult with metadata
            StubProvider stub = new StubProvider("bytecode data");
            SutContextProviderFactory factory = new SutContextProviderFactory(
                    stub, stub, stub, stub);

            PromptResult promptResult = new PromptBuilder(
                    new SystemPromptProvider(),
                    new TestClusterSummarizer(),
                    new SourceCodeProvider(),
                    new CoverageGoalFormatter(),
                    new TestCaseFormatter(),
                    factory)
                    .withSystemPrompt()
                    .withSutContext("com.example.Foo", null)
                    .buildWithMetadata();

            // Query using PromptResult overload
            String response = service.query(promptResult, LlmFeature.SEEDING);
            assertEquals("test response", response);

            // Verify trace file contains context mode
            String traceContent = new String(Files.readAllBytes(recorder.getTraceFile()), StandardCharsets.UTF_8);
            assertTrue(traceContent.contains("\"sut_context_mode\":\"BYTECODE_DISASSEMBLED\""),
                    "Trace should contain sut_context_mode=BYTECODE_DISASSEMBLED, was: " + traceContent);
            assertTrue(traceContent.contains("\"context_unavailable\":false"),
                    "Trace should contain context_unavailable=false");
        } finally {
            service.close();
        }
    }

    @Test
    void legacyQueryStillWritesEmptyContextMode(@TempDir Path tempDir) throws Exception {
        LlmConfiguration config = new LlmConfiguration(
                Properties.LlmProvider.OPENAI,
                "mock-model", "", "", 0.0, 256, 30, 0, 1,
                true, tempDir, "run-legacy");

        LlmTraceRecorder recorder = new LlmTraceRecorder(config);

        LlmService.ChatLanguageModel model = new LlmService.ChatLanguageModel() {
            @Override
            public LlmService.LlmResponse generate(List<LlmMessage> messages, LlmFeature feature) {
                return new LlmService.LlmResponse("ok", 1, 1);
            }
        };

        LlmBudgetCoordinator.Local budget = new LlmBudgetCoordinator.Local(5);
        LlmService service = new LlmService(model, budget, config, new LlmStatistics(), recorder);

        try {
            service.query(Collections.singletonList(LlmMessage.user("test")), LlmFeature.TEST_REPAIR);

            String traceContent = new String(Files.readAllBytes(recorder.getTraceFile()), StandardCharsets.UTF_8);
            assertTrue(traceContent.contains("\"sut_context_mode\":\"\""),
                    "Legacy query should write empty sut_context_mode");
        } finally {
            service.close();
        }
    }

    // --- Cache staleness after cluster expansion ---

    @Test
    void signatureOnlyModeNotCached() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.SIGNATURE_ONLY;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = true;
        Properties.LLM_CONTEXT_MAX_CHARS = 0;

        CountingProvider counting = new CountingProvider("sig");
        SutContextProviderFactory factory = new SutContextProviderFactory(
                counting, counting, counting, counting);

        factory.getContext("com.example.Foo", null);
        factory.getContext("com.example.Foo", null);

        assertEquals(2, counting.callCount,
                "SIGNATURE_ONLY should not be cached since TestCluster may change after expansion");
    }

    @Test
    void fallbackResultNotCached() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.BYTECODE_DISASSEMBLED;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = true;
        Properties.LLM_CONTEXT_MAX_CHARS = 0;

        CountingProvider sigProvider = new CountingProvider("sig");
        FailingProvider failingBytecode = new FailingProvider();
        SutContextProviderFactory factory = new SutContextProviderFactory(
                sigProvider, failingBytecode, failingBytecode, failingBytecode);

        SutContextProviderFactory.ContextResult r1 = factory.getContext("com.example.Foo", null);
        assertEquals(LlmSutContextMode.SIGNATURE_ONLY, r1.getModeUsed());

        SutContextProviderFactory.ContextResult r2 = factory.getContext("com.example.Foo", null);
        assertEquals(2, sigProvider.callCount,
                "Fallback results should not be cached since they depend on mutable TestCluster");
    }

    @Test
    void primarySuccessResultIsCached() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.BYTECODE_DISASSEMBLED;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = true;
        Properties.LLM_CONTEXT_MAX_CHARS = 0;

        CountingProvider counting = new CountingProvider("bytecode");
        SutContextProviderFactory factory = new SutContextProviderFactory(
                counting, counting, counting, counting);

        factory.getContext("com.example.Foo", null);
        factory.getContext("com.example.Foo", null);

        assertEquals(1, counting.callCount,
                "Non-signature primary success should still be cached");
    }

    // --- Null guard on query(PromptResult) ---

    @Test
    void queryWithNullPromptResultThrowsIllegalArgument() {
        LlmConfiguration config = new LlmConfiguration(
                Properties.LlmProvider.NONE,
                "mock", "", "", 0.0, 256, 30, 0, 1,
                false, java.nio.file.Paths.get("target/llm-test-traces"), "run-null");
        LlmService service = new LlmService(
                new LlmService.ChatLanguageModel() {
                    @Override
                    public LlmService.LlmResponse generate(List<LlmMessage> messages, LlmFeature feature) {
                        return new LlmService.LlmResponse("ok", 0, 0);
                    }
                },
                new LlmBudgetCoordinator.Local(5),
                config,
                new LlmStatistics(),
                new LlmTraceRecorder(config));
        try {
            assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() {
                    service.query((PromptResult) null, LlmFeature.SEEDING);
                }
            });
        } finally {
            service.close();
        }
    }

    // --- Test helpers ---

    private static class StubProvider implements SutContextProvider {
        private final String output;
        StubProvider(String output) { this.output = output; }
        @Override
        public Optional<String> getContext(String className, TestCluster cluster) {
            return Optional.of(output);
        }
        @Override
        public String modeLabel() { return "stub"; }
    }

    private static class FailingProvider implements SutContextProvider {
        @Override
        public Optional<String> getContext(String className, TestCluster cluster) {
            return Optional.empty();
        }
        @Override
        public String modeLabel() { return "failing"; }
    }

    private static class CountingProvider implements SutContextProvider {
        private final String output;
        int callCount = 0;
        CountingProvider(String output) { this.output = output; }
        @Override
        public Optional<String> getContext(String className, TestCluster cluster) {
            callCount++;
            return Optional.of(output);
        }
        @Override
        public String modeLabel() { return "counting"; }
    }
}
