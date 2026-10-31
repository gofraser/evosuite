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
import org.evosuite.setup.TestCluster;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two runs in one JVM with different prompt settings must not share cache entries: the
 * process-scoped caches are keyed by the {@link PromptSettings} snapshot, and the collaborators
 * render from the snapshot they were given rather than from the live {@link Properties}. The
 * caches are deliberately not reset between the "runs" in these tests.
 */
class PromptSettingsIsolationTest {

    private LlmSutContextMode originalMode;
    private boolean originalFallback;
    private int originalContextMaxChars;
    private boolean originalCache;
    private boolean originalCompact;
    private boolean originalScaling;
    private String originalTargetClass;

    @BeforeEach
    void saveProperties() {
        originalMode = Properties.LLM_SUT_CONTEXT_MODE;
        originalFallback = Properties.LLM_CONTEXT_FALLBACK_ENABLED;
        originalContextMaxChars = Properties.LLM_CONTEXT_MAX_CHARS;
        originalCache = Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT;
        originalCompact = Properties.LLM_CLUSTER_SUMMARY_COMPACT_SIGNATURES;
        originalScaling = Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_SCALING;
        originalTargetClass = Properties.TARGET_CLASS;
        StaticPromptContext.resetForRunCompletion();
        SutContextProviderFactory.resetInstanceForTesting();
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_SUT_CONTEXT_MODE = originalMode;
        Properties.LLM_CONTEXT_FALLBACK_ENABLED = originalFallback;
        Properties.LLM_CONTEXT_MAX_CHARS = originalContextMaxChars;
        Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT = originalCache;
        Properties.LLM_CLUSTER_SUMMARY_COMPACT_SIGNATURES = originalCompact;
        Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_SCALING = originalScaling;
        Properties.TARGET_CLASS = originalTargetClass;
        StaticPromptContext.resetForRunCompletion();
        SutContextProviderFactory.resetInstanceForTesting();
    }

    private static SutContextProvider provider(Optional<String> text) {
        return new SutContextProvider() {
            @Override
            public Optional<String> getContext(String className, TestCluster cluster) {
                return text;
            }
        };
    }

    @Test
    void fallbackSettingOfTheSecondRunIsHonouredByASharedFactory() {
        SutContextProvider signature = provider(Optional.of("class Foo { /* signatures */ }"));
        SutContextProvider unavailable = provider(Optional.empty());
        SutContextProviderFactory shared =
                new SutContextProviderFactory(signature, unavailable, unavailable, unavailable);
        PromptSettings.Builder base = PromptSettings.builderFromProperties()
                .sutContextMode(LlmSutContextMode.BYTECODE_DISASSEMBLED);

        SutContextProviderFactory.ContextResult strict =
                shared.getContext("com.example.Foo", null, base.contextFallbackEnabled(false).build());
        SutContextProviderFactory.ContextResult lenient =
                shared.getContext("com.example.Foo", null, base.contextFallbackEnabled(true).build());

        assertTrue(strict.getInfo().isContextUnavailable());
        assertEquals("", strict.getText());
        assertFalse(lenient.getInfo().isContextUnavailable(),
                "A run that allows the fallback must not be served the strict run's unavailable context");
        assertEquals(LlmSutContextMode.SIGNATURE_ONLY, lenient.getInfo().getSutContextMode());
        assertTrue(lenient.getText().contains("signatures"));
    }

    @Test
    void contextLimitOfTheSecondRunProducesDifferentText() {
        StringBuilder big = new StringBuilder("class Foo {\n");
        for (int i = 0; i < 200; i++) {
            big.append("    void method").append(i).append("() {}\n");
        }
        big.append("}\n");
        SutContextProvider source = provider(Optional.of(big.toString()));
        SutContextProviderFactory shared = new SutContextProviderFactory(source, source, source, source);
        PromptSettings.Builder base = PromptSettings.builderFromProperties()
                .sutContextMode(LlmSutContextMode.BYTECODE_DISASSEMBLED);

        String roomy = shared.getContext("com.example.Foo", null, base.contextMaxChars(100_000).build()).getText();
        String tight = shared.getContext("com.example.Foo", null, base.contextMaxChars(300).build()).getText();

        assertEquals(big.toString(), roomy);
        assertTrue(tight.length() < roomy.length());
    }

    @Test
    void dependencySummaryIsNotSharedBetweenRunsWithDifferentBudgets() {
        Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT = true;
        Properties.LLM_CLUSTER_SUMMARY_DYNAMIC_SCALING = false;
        TestCluster cluster = clusterWithDependency(GenericClassFactory.get(SignatureContextProvider.class));
        SutContextProvider stub = provider(Optional.of("public class Foo {}"));
        SutContextProviderFactory factory = new SutContextProviderFactory(stub, stub, stub, stub);
        BudgetEchoSummarizer summarizer = new BudgetEchoSummarizer();

        PromptSettings small = PromptSettings.builderFromProperties()
                .sutContextMode(LlmSutContextMode.SIGNATURE_ONLY).clusterSummaryMaxChars(1111).build();
        PromptSettings large = PromptSettings.builderFromProperties()
                .sutContextMode(LlmSutContextMode.SIGNATURE_ONLY).clusterSummaryMaxChars(2222).build();

        String first = systemMessage(factory, summarizer, small, cluster);
        String second = systemMessage(factory, summarizer, large, cluster);
        String firstAgain = systemMessage(factory, summarizer, small, cluster);

        assertTrue(first.contains("budget=1111"), first);
        assertTrue(second.contains("budget=2222"),
                "A run with another summary budget must render its own dependency summary");
        assertEquals(first, firstAgain);
        assertEquals(2, summarizer.invocationCount, "equal settings still share one render");
    }

    @Test
    void sourcePathAndDecompilerTimeoutReachTheProviderAndKeyTheCache() {
        SutContextProvider echo = new SutContextProvider() {
            @Override
            public Optional<String> getContext(String className, TestCluster cluster) {
                throw new AssertionError("the factory must pass its settings snapshot");
            }

            @Override
            public Optional<String> getContext(String className, TestCluster cluster, PromptSettings settings) {
                return Optional.of("path=" + settings.sourcePath() + " timeout=" + settings.decompilerTimeoutSeconds());
            }
        };
        SutContextProviderFactory shared = new SutContextProviderFactory(echo, echo, echo, echo);
        PromptSettings.Builder base = PromptSettings.builderFromProperties()
                .sutContextMode(LlmSutContextMode.SOURCE_CODE);

        String first = shared.getContext("com.example.Foo", null,
                base.sourcePath("/a").decompilerTimeoutSeconds(5).build()).getText();
        String second = shared.getContext("com.example.Foo", null,
                base.sourcePath("/b").decompilerTimeoutSeconds(9).build()).getText();

        assertEquals("path=/a timeout=5", first);
        assertEquals("path=/b timeout=9", second);
    }

    @Test
    void thePromptRendersFromItsSnapshotNotFromLaterPropertyChanges() {
        Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT = false;
        Properties.TARGET_CLASS = "com.example.Foo";
        SutContextProvider stub = provider(Optional.of("public class Foo {}"));
        SutContextProviderFactory factory = new SutContextProviderFactory(stub, stub, stub, stub);
        PromptSettings uncached = PromptSettings.fromProperties();
        PromptBuilder builder = new PromptBuilder(new SystemPromptProvider(), new TestClusterSummarizer(uncached),
                new CoverageGoalFormatter(PromptSettings.fromProperties()), new TestCaseFormatter(), factory, uncached);

        Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT = true;
        PromptResult result = builder.withSystemPrompt().withSutContext("com.example.Foo", null).buildWithMetadata();

        assertFalse(result.getMessages().get(0).getContent().contains("CLASS UNDER TEST (SUT): com.example.Foo"));
        assertTrue(result.getMessages().get(1).getContent().contains("CLASS UNDER TEST (SUT): com.example.Foo"));
    }

    @Test
    void settingsAreEqualExactlyWhenTheyRenderTheSameText() {
        PromptSettings a = PromptSettings.fromProperties();
        PromptSettings b = PromptSettings.fromProperties();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, PromptSettings.builderFromProperties()
                .contextFallbackEnabled(!a.contextFallbackEnabled()).build());
        assertNotEquals(a, PromptSettings.builderFromProperties()
                .clusterSummaryCompactSignatures(!a.clusterSummaryCompactSignatures()).build());
        assertNotEquals(a, PromptSettings.builderFromProperties()
                .goalFormat(a.goalFormat() == Properties.LlmGoalFormat.RAW
                        ? Properties.LlmGoalFormat.LLM_FRIENDLY : Properties.LlmGoalFormat.RAW).build());
        assertNotEquals(a, PromptSettings.builderFromProperties()
                .promptTechnique(a.promptTechnique() == Properties.LlmPromptTechnique.FEW_SHOT
                        ? Properties.LlmPromptTechnique.NONE : Properties.LlmPromptTechnique.FEW_SHOT).build());
    }

    private static String systemMessage(SutContextProviderFactory factory, TestClusterSummarizer summarizer,
                                        PromptSettings settings, TestCluster cluster) {
        return new PromptBuilder(new SystemPromptProvider(), summarizer, new CoverageGoalFormatter(PromptSettings.fromProperties()),
                new TestCaseFormatter(), factory, settings)
                .withSystemPrompt()
                .withSutContext("com.example.Foo", cluster)
                .build().get(0).getContent();
    }

    private static TestCluster clusterWithDependency(GenericClass<?> depType) {
        TestCluster cluster = mock(TestCluster.class);
        when(cluster.getModifiers()).thenReturn(Collections.<GenericAccessibleObject<?>>emptySet());
        Map<GenericClass<?>, Set<GenericAccessibleObject<?>>> generatorsByType = new LinkedHashMap<>();
        generatorsByType.put(depType, Collections.<GenericAccessibleObject<?>>emptySet());
        when(cluster.getGeneratorsByType()).thenReturn(generatorsByType);
        return cluster;
    }

    /** Renders the budget it was asked for, so the text shows which settings produced it. */
    private static final class BudgetEchoSummarizer extends TestClusterSummarizer {
        private int invocationCount;

        @Override
        public DependencySummaryResult summarizeDependencies(TestCluster cluster, String targetClassName,
                                                              int maxChars) {
            invocationCount++;
            return new DependencySummaryResult("dep budget=" + maxChars, false, 0, 0, false, 1,
                    new DependencySummaryStats());
        }
    }
}
