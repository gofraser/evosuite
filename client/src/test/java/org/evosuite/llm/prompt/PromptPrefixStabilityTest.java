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
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that with {@code LLM_PROMPT_CACHE_STATIC_CONTEXT} enabled, the SUT
 * context and dependency summary render once per run into the system message
 * (a stable, cacheable prefix), while everything dynamic stays in the user
 * message; and that disabling the property reproduces today's behaviour.
 */
class PromptPrefixStabilityTest {

    private LlmSutContextMode originalMode;
    private String originalTargetClass;
    private boolean originalPromptCacheStaticContext;

    @BeforeEach
    void saveProperties() {
        originalMode = Properties.LLM_SUT_CONTEXT_MODE;
        originalTargetClass = Properties.TARGET_CLASS;
        originalPromptCacheStaticContext = Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT;
        Properties.LLM_SUT_CONTEXT_MODE = LlmSutContextMode.SIGNATURE_ONLY;
        Properties.TARGET_CLASS = "com.example.Foo";
        StaticPromptContext.resetInstanceForTesting();
        SutContextProviderFactory.resetInstanceForTesting();
    }

    @AfterEach
    void restoreProperties() {
        Properties.LLM_SUT_CONTEXT_MODE = originalMode;
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT = originalPromptCacheStaticContext;
        StaticPromptContext.resetInstanceForTesting();
        SutContextProviderFactory.resetInstanceForTesting();
    }

    private static TestCase makeTestCase(String marker) {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new StringPrimitiveStatement(tc, marker));
        return tc;
    }

    private static SutContextProviderFactory stubFactory(String output) {
        SutContextProvider stub = new SutContextProvider() {
            @Override
            public Optional<String> getContext(String className, TestCluster cluster) {
                return Optional.of(output);
            }

            @Override
            public String modeLabel() {
                return "stub";
            }
        };
        return new SutContextProviderFactory(stub, stub, stub, stub);
    }

    private static TestCluster clusterWithDependency(GenericClass<?> depType) {
        TestCluster cluster = mock(TestCluster.class);
        when(cluster.getModifiers()).thenReturn(Collections.<GenericAccessibleObject<?>>emptySet());
        Map<GenericClass<?>, Set<GenericAccessibleObject<?>>> generatorsByType = new LinkedHashMap<>();
        generatorsByType.put(depType, Collections.<GenericAccessibleObject<?>>emptySet());
        when(cluster.getGeneratorsByType()).thenReturn(generatorsByType);
        return cluster;
    }

    private static PromptBuilder newBuilder(SutContextProviderFactory factory) {
        return new PromptBuilder(new SystemPromptProvider(), new TestClusterSummarizer(),
                new SourceCodeProvider(), new CoverageGoalFormatter(), new TestCaseFormatter(), factory);
    }

    @Test
    void samePrefixHashAndSystemMessageDespiteDifferentDynamicContent() {
        Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT = true;
        SutContextProviderFactory factory = stubFactory("public class Foo {}");
        TestCluster cluster = clusterWithDependency(GenericClassFactory.get(SignatureContextProvider.class));

        PromptResult result1 = newBuilder(factory)
                .withSystemPrompt()
                .withSutContext("com.example.Foo", cluster)
                .withExistingTest(makeTestCase("first"))
                .withInstruction("Cover branch A.")
                .buildWithMetadata();

        PromptResult result2 = newBuilder(factory)
                .withSystemPrompt()
                .withSutContext("com.example.Foo", cluster)
                .withExistingTest(makeTestCase("second, totally different content"))
                .withInstruction("Cover a completely different branch B instead.")
                .buildWithMetadata();

        assertEquals(result1.getPrefixHash(), result2.getPrefixHash(),
                "Prefix hash must be stable across prompts that differ only in dynamic content");
        assertEquals(result1.getMessages().get(0).getContent(), result2.getMessages().get(0).getContent(),
                "System message must be identical across prompts that differ only in dynamic content");

        String systemMessage = result1.getMessages().get(0).getContent();
        String userMessage = result1.getMessages().get(1).getContent();
        assertTrue(systemMessage.contains("CLASS UNDER TEST (SUT): com.example.Foo"));
        assertTrue(systemMessage.contains(StaticPromptContext.DEPENDENCY_SECTION_HEADER));
        assertFalse(userMessage.contains("CLASS UNDER TEST (SUT): com.example.Foo"),
                "Static SUT section must not also be duplicated into the user message");
        assertFalse(userMessage.contains(StaticPromptContext.DEPENDENCY_SECTION_HEADER),
                "Static dependency section must not also be duplicated into the user message");
    }

    @Test
    void staticContextCacheDisabledKeepsSectionsInUserMessage() {
        Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT = false;
        SutContextProviderFactory factory = stubFactory("public class Foo {}");
        TestCluster cluster = clusterWithDependency(GenericClassFactory.get(SignatureContextProvider.class));

        PromptResult result = newBuilder(factory)
                .withSystemPrompt()
                .withSutContext("com.example.Foo", cluster)
                .withInstruction("Cover branch A.")
                .buildWithMetadata();

        String systemMessage = result.getMessages().get(0).getContent();
        String userMessage = result.getMessages().get(1).getContent();
        assertFalse(systemMessage.contains("CLASS UNDER TEST (SUT): com.example.Foo"));
        assertTrue(userMessage.contains("CLASS UNDER TEST (SUT): com.example.Foo"));
        assertTrue(userMessage.contains(StaticPromptContext.DEPENDENCY_SECTION_HEADER));
        assertEquals(PrefixHash.ofText(systemMessage), result.getPrefixHash());
    }

    @Test
    void dependencySummarizerRendersOncePerRunThenAgainAfterReset() {
        Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT = true;
        SutContextProviderFactory factory = stubFactory("public class Foo {}");
        TestCluster cluster = clusterWithDependency(GenericClassFactory.get(SignatureContextProvider.class));
        CountingSummarizer summarizer = new CountingSummarizer();

        new PromptBuilder(new SystemPromptProvider(), summarizer, new SourceCodeProvider(),
                new CoverageGoalFormatter(), new TestCaseFormatter(), factory)
                .withSystemPrompt()
                .withSutContext("com.example.Foo", cluster)
                .build();
        new PromptBuilder(new SystemPromptProvider(), summarizer, new SourceCodeProvider(),
                new CoverageGoalFormatter(), new TestCaseFormatter(), factory)
                .withSystemPrompt()
                .withSutContext("com.example.Foo", cluster)
                .build();

        assertEquals(1, summarizer.invocationCount,
                "Two prompts in the same run must share one dependency-summary render");

        StaticPromptContext.resetForRunCompletion();

        new PromptBuilder(new SystemPromptProvider(), summarizer, new SourceCodeProvider(),
                new CoverageGoalFormatter(), new TestCaseFormatter(), factory)
                .withSystemPrompt()
                .withSutContext("com.example.Foo", cluster)
                .build();

        assertEquals(2, summarizer.invocationCount,
                "A new run must re-render the dependency summary");
    }

    @Test
    void clusterGrowthAfterMemoisationIsSurfacedInUserMessageOnly() {
        Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT = true;
        SutContextProviderFactory factory = stubFactory("public class Foo {}");
        GenericClass<?> initialType = GenericClassFactory.get(SignatureContextProvider.class);
        TestCluster cluster = clusterWithDependency(initialType);

        PromptResult result1 = newBuilder(factory)
                .withSystemPrompt()
                .withSutContext("com.example.Foo", cluster)
                .buildWithMetadata();
        String systemMessage1 = result1.getMessages().get(0).getContent();
        String prefixHash1 = result1.getPrefixHash();
        assertTrue(systemMessage1.contains(StaticPromptContext.DEPENDENCY_SECTION_HEADER));

        // Simulate cluster expansion (e.g. ClusterExpansionManager.tryExpandFrom)
        // adding a class the memoised summary never saw.
        GenericClass<?> newType = GenericClassFactory.get(TestCaseFormatter.class);
        Map<GenericClass<?>, Set<GenericAccessibleObject<?>>> grown = new LinkedHashMap<>();
        grown.put(initialType, Collections.<GenericAccessibleObject<?>>emptySet());
        grown.put(newType, Collections.<GenericAccessibleObject<?>>emptySet());
        when(cluster.getGeneratorsByType()).thenReturn(grown);

        PromptResult result2 = newBuilder(factory)
                .withSystemPrompt()
                .withSutContext("com.example.Foo", cluster)
                .buildWithMetadata();
        String systemMessage2 = result2.getMessages().get(0).getContent();
        String userMessage2 = result2.getMessages().get(1).getContent();

        assertEquals(systemMessage1, systemMessage2,
                "Memoised system message must not change when the cluster grows");
        assertEquals(prefixHash1, result2.getPrefixHash(),
                "Prefix hash must not change when the cluster grows");
        assertTrue(userMessage2.contains(ClusterGrowthDelta.HEADER),
                "Growth-delta header should introduce the newly added dependency class");
        assertTrue(userMessage2.contains(newType.getClassName())
                        || userMessage2.contains(newType.getRawClass().getSimpleName() + "("),
                "Newly added dependency class should be named or signatured in the user message");
        assertTrue(userMessage2.contains("TestCaseFormatter()"),
                "Growth delta should include the added class' constructor signature: " + userMessage2);
        assertFalse(systemMessage2.contains(newType.getClassName()),
                "Newly added dependency class must not leak into the cached system message");
    }

    @Test
    void clusterGrowthSurfacesEvenWhenInitialDependencySummaryWasEmpty() {
        Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT = true;
        SutContextProviderFactory factory = stubFactory("public class Foo {}");
        TestCluster cluster = mock(TestCluster.class);
        when(cluster.getModifiers()).thenReturn(Collections.<GenericAccessibleObject<?>>emptySet());
        when(cluster.getGeneratorsByType())
                .thenReturn(Collections.<GenericClass<?>, Set<GenericAccessibleObject<?>>>emptyMap());

        PromptResult result1 = newBuilder(factory)
                .withSystemPrompt()
                .withSutContext("com.example.Foo", cluster)
                .buildWithMetadata();
        String systemMessage1 = result1.getMessages().get(0).getContent();
        String prefixHash1 = result1.getPrefixHash();
        assertFalse(systemMessage1.contains(StaticPromptContext.DEPENDENCY_SECTION_HEADER),
                "No dependency section should be cached when the cluster starts with nothing to summarize");

        // Simulate cluster expansion adding a class, even though the memoised
        // (empty) summary never produced a dependency section at all.
        GenericClass<?> newType = GenericClassFactory.get(TestCaseFormatter.class);
        Map<GenericClass<?>, Set<GenericAccessibleObject<?>>> grown = new LinkedHashMap<>();
        grown.put(newType, Collections.<GenericAccessibleObject<?>>emptySet());
        when(cluster.getGeneratorsByType()).thenReturn(grown);

        PromptResult result2 = newBuilder(factory)
                .withSystemPrompt()
                .withSutContext("com.example.Foo", cluster)
                .buildWithMetadata();
        String systemMessage2 = result2.getMessages().get(0).getContent();
        String userMessage2 = result2.getMessages().get(1).getContent();

        assertEquals(systemMessage1, systemMessage2,
                "Memoised (empty) system message must stay empty of dependency context");
        assertEquals(prefixHash1, result2.getPrefixHash(),
                "Prefix hash must not change when the cluster grows");
        assertTrue(userMessage2.contains(ClusterGrowthDelta.HEADER),
                "Growth delta must appear even though the initial summary was empty: " + userMessage2);
        assertTrue(userMessage2.contains("TestCaseFormatter()"),
                "Growth delta should include the added class' constructor signature: " + userMessage2);
    }

    private static final class CountingSummarizer extends TestClusterSummarizer {
        private int invocationCount;

        @Override
        public DependencySummaryResult summarizeDependencies(TestCluster cluster, String targetClassName,
                                                              int maxChars) {
            invocationCount++;
            return super.summarizeDependencies(cluster, targetClassName, maxChars);
        }
    }
}
