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
package org.evosuite.llm.prompt;

import org.evosuite.Properties;
import org.evosuite.Properties.LlmSutContextMode;
import org.evosuite.llm.LlmMessage;
import org.evosuite.setup.TestCluster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;

class PromptBuilderSutContextTest {

    private LlmSutContextMode originalMode;
    private boolean originalFallback;
    private int originalMaxChars;
    private int originalClusterMaxChars;
    private boolean originalClusterDynamicScaling;
    private double originalClusterDynamicRatio;
    private int originalClusterDynamicMinChars;
    private int originalClusterDynamicMaxChars;
    private int originalClusterAbsoluteOverride;
    private String originalTargetClass;

    @BeforeEach
    void saveProperties() {
        originalMode = Properties.LLM_SUT_CONTEXT_MODE;
        originalFallback = Properties.LLM_CONTEXT_FALLBACK_ENABLED;
        originalMaxChars = Properties.LLM_CONTEXT_MAX_CHARS;
        originalClusterMaxChars = Properties.LLM_CLUSTER_SUMMARY_MAX_CHARS;
        originalClusterDynamicScaling = Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_SCALING;
        originalClusterDynamicRatio = Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_RATIO;
        originalClusterDynamicMinChars = Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_MIN_CHARS;
        originalClusterDynamicMaxChars = Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_MAX_CHARS;
        originalClusterAbsoluteOverride = Properties.LLM_CLUSTER_SUMMARY_ABSOLUTE_OVERRIDE_CHARS;
        originalTargetClass = Properties.TARGET_CLASS;
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_SUT_CONTEXT_MODE = originalMode;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = originalFallback;
        Properties.LLM_CONTEXT_MAX_CHARS = originalMaxChars;
        Properties.LLM_CLUSTER_SUMMARY_MAX_CHARS = originalClusterMaxChars;
        Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_SCALING = originalClusterDynamicScaling;
        Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_RATIO = originalClusterDynamicRatio;
        Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_MIN_CHARS = originalClusterDynamicMinChars;
        Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_MAX_CHARS = originalClusterDynamicMaxChars;
        Properties.LLM_CLUSTER_SUMMARY_ABSOLUTE_OVERRIDE_CHARS = originalClusterAbsoluteOverride;
        Properties.TARGET_CLASS = originalTargetClass;
    }

    @Test
    void withSutContextIncludesContextInPrompt() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.BYTECODE_DISASSEMBLED;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = true;

        SutContextProvider stubProvider = new StubSutContextProvider("bytecode content");
        SutContextProviderFactory factory = new SutContextProviderFactory(
                stubProvider, stubProvider, stubProvider, stubProvider);

        PromptBuilder builder = new PromptBuilder(
                new SystemPromptProvider(),
                new TestClusterSummarizer(),
                new SourceCodeProvider(),
                new CoverageGoalFormatter(),
                new TestCaseFormatter(),
                factory);

        List<LlmMessage> messages = builder
                .withSystemPrompt()
                .withSutContext("com.example.Foo", null)
                .withInstruction("Generate tests.")
                .build();

        String userPrompt = messages.get(1).getContent();
        assertTrue(userPrompt.contains("BYTECODE_DISASSEMBLED context:"));
        assertTrue(userPrompt.contains("bytecode content"));
    }

    @Test
    void withSutContextExposesMetadata() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.SOURCE_CODE;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = false;

        SutContextProvider failingProvider = new FailingSutContextProvider();
        SutContextProviderFactory factory = new SutContextProviderFactory(
                failingProvider, failingProvider, failingProvider, failingProvider);

        PromptBuilder builder = new PromptBuilder(
                new SystemPromptProvider(),
                new TestClusterSummarizer(),
                new SourceCodeProvider(),
                new CoverageGoalFormatter(),
                new TestCaseFormatter(),
                factory);

        builder.withSutContext("com.example.Foo", null);

        assertEquals(LlmSutContextMode.SOURCE_CODE, builder.getSutContextModeUsed());
        assertTrue(builder.isSutContextUnavailable());
    }

    @Test
    void withSutContextFallbackExposesSignatureMode() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.DECOMPILED_SOURCE;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = true;

        SutContextProvider failingProvider = new FailingSutContextProvider();
        SutContextProvider signatureProvider = new StubSutContextProvider("sig");
        SutContextProviderFactory factory = new SutContextProviderFactory(
                signatureProvider, failingProvider, failingProvider, failingProvider);

        PromptBuilder builder = new PromptBuilder(
                new SystemPromptProvider(),
                new TestClusterSummarizer(),
                new SourceCodeProvider(),
                new CoverageGoalFormatter(),
                new TestCaseFormatter(),
                factory);

        builder.withSutContext("com.example.Foo", null);

        assertEquals(LlmSutContextMode.SIGNATURE_ONLY, builder.getSutContextModeUsed());
        assertFalse(builder.isSutContextUnavailable());
    }

    @Test
    void truncationAppliedViaFactory() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.SIGNATURE_ONLY;
        Properties.LLM_CONTEXT_MAX_CHARS = 5;

        SutContextProvider stubProvider = new StubSutContextProvider("abcdefghij");
        SutContextProviderFactory factory = new SutContextProviderFactory(
                stubProvider, stubProvider, stubProvider, stubProvider);

        PromptBuilder builder = new PromptBuilder(
                new SystemPromptProvider(),
                new TestClusterSummarizer(),
                new SourceCodeProvider(),
                new CoverageGoalFormatter(),
                new TestCaseFormatter(),
                factory);

        List<LlmMessage> messages = builder
                .withSutContext("com.example.Foo", null)
                .build();

        String userPrompt = messages.get(1).getContent();
        assertTrue(userPrompt.contains("abcde"));
        assertTrue(userPrompt.contains("(truncated)"));
        assertFalse(userPrompt.contains("fghij"));
    }

    @Test
    void withSutContextAutomaticallyAddsDependencySignatures() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.SIGNATURE_ONLY;
        Properties.TARGET_CLASS = "com.example.Foo";

        SutContextProvider stubProvider = new StubSutContextProvider("public class Foo {}");
        SutContextProviderFactory factory = new SutContextProviderFactory(
                stubProvider, stubProvider, stubProvider, stubProvider);

        PromptBuilder builder = new PromptBuilder(
                new SystemPromptProvider(),
                new TestClusterSummarizer(),
                new SourceCodeProvider(),
                new CoverageGoalFormatter(),
                new TestCaseFormatter(),
                factory);

        TestCluster cluster = mock(TestCluster.class);
        when(cluster.getModifiers()).thenReturn(java.util.Collections.<GenericAccessibleObject<?>>emptySet());
        GenericClass<?> depType = GenericClassFactory.get(SignatureContextProvider.class);
        Map<GenericClass<?>, Set<GenericAccessibleObject<?>>> generatorsByType = new LinkedHashMap<>();
        generatorsByType.put(depType, java.util.Collections.<GenericAccessibleObject<?>>emptySet());
        when(cluster.getGeneratorsByType()).thenReturn(generatorsByType);

        List<LlmMessage> messages = builder
                .withSutContext("com.example.Foo", cluster)
                .build();

        String userPrompt = messages.get(1).getContent();
        assertTrue(userPrompt.contains("Available dependency types"),
                "Dependency signatures should be included automatically");
    }

    @Test
    void withSutContextAndExplicitClusterContextDoNotDuplicateDependencySection() {
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.SIGNATURE_ONLY;
        Properties.TARGET_CLASS = "com.example.Foo";

        SutContextProvider stubProvider = new StubSutContextProvider("public class Foo {}");
        SutContextProviderFactory factory = new SutContextProviderFactory(
                stubProvider, stubProvider, stubProvider, stubProvider);

        PromptBuilder builder = new PromptBuilder(
                new SystemPromptProvider(),
                new TestClusterSummarizer(),
                new SourceCodeProvider(),
                new CoverageGoalFormatter(),
                new TestCaseFormatter(),
                factory);

        TestCluster cluster = mock(TestCluster.class);
        when(cluster.getModifiers()).thenReturn(java.util.Collections.<GenericAccessibleObject<?>>emptySet());
        GenericClass<?> depType = GenericClassFactory.get(SignatureContextProvider.class);
        Map<GenericClass<?>, Set<GenericAccessibleObject<?>>> generatorsByType = new LinkedHashMap<>();
        generatorsByType.put(depType, java.util.Collections.<GenericAccessibleObject<?>>emptySet());
        when(cluster.getGeneratorsByType()).thenReturn(generatorsByType);

        List<LlmMessage> messages = builder
                .withSutContext("com.example.Foo", cluster)
                .withTestClusterContext("com.example.Foo", cluster)
                .build();

        String userPrompt = messages.get(1).getContent();
        int first = userPrompt.indexOf("Available dependency types");
        int second = userPrompt.indexOf("Available dependency types", first + 1);
        assertTrue(first >= 0, "Dependency section should be present");
        assertEquals(-1, second, "Dependency section should not be duplicated");
    }

    @Test
    void dependencyBudgetScalesWithContextBudget() {
        Properties.LLM_CONTEXT_MAX_CHARS = 100_000;
        Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_SCALING = true;
        Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_RATIO = 0.10;
        Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_MIN_CHARS = 4_000;
        Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_MAX_CHARS = 20_000;
        Properties.LLM_CLUSTER_SUMMARY_ABSOLUTE_OVERRIDE_CHARS = 0;

        RecordingSummarizer summarizer = new RecordingSummarizer();

        PromptBuilder builder = new PromptBuilder(
                new SystemPromptProvider(),
                summarizer,
                new SourceCodeProvider(),
                new CoverageGoalFormatter(),
                new TestCaseFormatter(),
                SutContextProviderFactory.getInstance());

        TestCluster cluster = mock(TestCluster.class);
        when(cluster.getModifiers()).thenReturn(java.util.Collections.<GenericAccessibleObject<?>>emptySet());
        when(cluster.getGeneratorsByType()).thenReturn(java.util.Collections.emptyMap());

        builder.withTestClusterContext("com.example.Foo", cluster).build();

        assertEquals(10_000, summarizer.lastMaxChars);
    }

    @Test
    void dependencyBudgetAbsoluteOverrideWins() {
        Properties.LLM_CONTEXT_MAX_CHARS = 100_000;
        Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_SCALING = true;
        Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_RATIO = 0.10;
        Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_MIN_CHARS = 4_000;
        Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_MAX_CHARS = 20_000;
        Properties.LLM_CLUSTER_SUMMARY_ABSOLUTE_OVERRIDE_CHARS = 7_777;

        RecordingSummarizer summarizer = new RecordingSummarizer();

        PromptBuilder builder = new PromptBuilder(
                new SystemPromptProvider(),
                summarizer,
                new SourceCodeProvider(),
                new CoverageGoalFormatter(),
                new TestCaseFormatter(),
                SutContextProviderFactory.getInstance());

        TestCluster cluster = mock(TestCluster.class);
        when(cluster.getModifiers()).thenReturn(java.util.Collections.<GenericAccessibleObject<?>>emptySet());
        when(cluster.getGeneratorsByType()).thenReturn(java.util.Collections.emptyMap());

        builder.withTestClusterContext("com.example.Foo", cluster).build();

        assertEquals(7_777, summarizer.lastMaxChars);
    }

    @Test
    void dependencyBudgetUsesDynamicMaxWhenContextUnlimited() {
        Properties.LLM_CONTEXT_MAX_CHARS = 0;
        Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_SCALING = true;
        Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_MAX_CHARS = 12_345;
        Properties.LLM_CLUSTER_SUMMARY_ABSOLUTE_OVERRIDE_CHARS = 0;

        RecordingSummarizer summarizer = new RecordingSummarizer();

        PromptBuilder builder = new PromptBuilder(
                new SystemPromptProvider(),
                summarizer,
                new SourceCodeProvider(),
                new CoverageGoalFormatter(),
                new TestCaseFormatter(),
                SutContextProviderFactory.getInstance());

        TestCluster cluster = mock(TestCluster.class);
        when(cluster.getModifiers()).thenReturn(java.util.Collections.<GenericAccessibleObject<?>>emptySet());
        when(cluster.getGeneratorsByType()).thenReturn(java.util.Collections.emptyMap());

        builder.withTestClusterContext("com.example.Foo", cluster).build();

        assertEquals(12_345, summarizer.lastMaxChars);
    }

    // --- Test helpers ---

    private static class StubSutContextProvider implements SutContextProvider {
        private final String output;
        StubSutContextProvider(String output) { this.output = output; }
        @Override
        public Optional<String> getContext(String className, TestCluster cluster) {
            return Optional.of(output);
        }
        @Override
        public String modeLabel() { return "stub"; }
    }

    private static class FailingSutContextProvider implements SutContextProvider {
        @Override
        public Optional<String> getContext(String className, TestCluster cluster) {
            return Optional.empty();
        }
        @Override
        public String modeLabel() { return "failing"; }
    }

    private static class RecordingSummarizer extends TestClusterSummarizer {
        private int lastMaxChars = -1;

        @Override
        public DependencySummaryResult summarizeDependencies(TestCluster cluster, String targetClassName, int maxChars) {
            this.lastMaxChars = maxChars;
            return new DependencySummaryResult.Builder()
                    .text("")
                    .truncated(false)
                    .totalCharsBeforeTruncation(0)
                    .build();
        }
    }
}
