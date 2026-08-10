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
package org.evosuite.llm.seeding;

import org.evosuite.Properties;
import org.evosuite.llm.*;
import org.evosuite.llm.mock.MockChatLanguageModel;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.seeding.ConstantPoolManager;
import org.evosuite.setup.TestCluster;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmConstantPoolEnricherTest {

    private Properties.LlmPromptTechnique savedTechnique;
    private boolean savedUseParsed;
    private boolean savedUseArchive;

    @BeforeEach
    void setUp() {
        savedTechnique = Properties.LLM_PROMPT_TECHNIQUE;
        savedUseParsed = Properties.LLM_FEW_SHOT_USE_PARSED_JUNIT;
        savedUseArchive = Properties.LLM_FEW_SHOT_USE_ARCHIVE;
        Properties.LLM_PROVIDER = Properties.LlmProvider.NONE;
        LlmService.resetInstanceForTesting();
        LlmStatistics.resetSeedingCounters();
        ConstantPoolManager.getInstance().reset();
    }

    @AfterEach
    void tearDown() {
        Properties.LLM_PROMPT_TECHNIQUE = savedTechnique;
        Properties.LLM_FEW_SHOT_USE_PARSED_JUNIT = savedUseParsed;
        Properties.LLM_FEW_SHOT_USE_ARCHIVE = savedUseArchive;
        LlmService.resetInstanceForTesting();
        LlmStatistics.resetSeedingCounters();
        ConstantPoolManager.getInstance().reset();
    }

    // ---- Constant parsing tests ----

    @Test
    void parseConstants_extractsStrings() {
        String response = "\"hello world\"\n\"edge \\\"case\\\"\"\n\"\"";
        List<Object> constants = LlmConstantPoolEnricher.parseConstants(response);
        assertTrue(constants.stream().anyMatch(c -> "hello world".equals(c)));
        assertTrue(constants.stream().anyMatch(c -> "".equals(c)));
        assertTrue(constants.stream().anyMatch(c -> "edge \"case\"".equals(c)));
    }

    @Test
    void parseConstants_extractsIntegers() {
        String response = "0\n-1\n42\n2147483647\n-2147483648\n";
        List<Object> constants = LlmConstantPoolEnricher.parseConstants(response);
        assertTrue(constants.contains(0));
        assertTrue(constants.contains(-1));
        assertTrue(constants.contains(42));
        assertTrue(constants.contains(Integer.MAX_VALUE));
        assertTrue(constants.contains(Integer.MIN_VALUE));
    }

    @Test
    void parseConstants_extractsLongs() {
        String response = "0L\n-1L\n9223372036854775807L\n";
        List<Object> constants = LlmConstantPoolEnricher.parseConstants(response);
        assertTrue(constants.contains(0L));
        assertTrue(constants.contains(-1L));
        assertTrue(constants.contains(Long.MAX_VALUE));
    }

    @Test
    void parseConstants_intOverflowFallsBackToLong() {
        // Integer-shaped literal (no L suffix) that doesn't fit in int range
        // should be kept as Long rather than silently dropped.
        String response = "2147483648\n-2147483649\n9999999999\n";
        List<Object> constants = LlmConstantPoolEnricher.parseConstants(response);
        assertTrue(constants.contains(2147483648L));
        assertTrue(constants.contains(-2147483649L));
        assertTrue(constants.contains(9999999999L));
    }

    @Test
    void parseConstants_extractsDoubles() {
        String response = "3.14\n0.0\n-1.5\n1.0e10\n";
        List<Object> constants = LlmConstantPoolEnricher.parseConstants(response);
        assertTrue(constants.contains(3.14));
        assertTrue(constants.contains(0.0));
        assertTrue(constants.contains(-1.5));
    }

    @Test
    void parseConstants_extractsFloats() {
        String response = "3.14f\n0.0f\n-1.5f\n";
        List<Object> constants = LlmConstantPoolEnricher.parseConstants(response);
        assertTrue(constants.contains(3.14f));
        assertTrue(constants.contains(0.0f));
        assertTrue(constants.contains(-1.5f));
    }

    @Test
    void parseConstants_extractsSpecialDoubles() {
        String response = "Double.NaN\nDouble.POSITIVE_INFINITY\nDouble.NEGATIVE_INFINITY\n";
        List<Object> constants = LlmConstantPoolEnricher.parseConstants(response);
        assertTrue(constants.stream().anyMatch(c -> c instanceof Double && Double.isNaN((Double) c)));
        assertTrue(constants.contains(Double.POSITIVE_INFINITY));
        assertTrue(constants.contains(Double.NEGATIVE_INFINITY));
    }

    @Test
    void parseConstants_negativeInfinityDoesNotAddPositiveInfinity() {
        String response = "-Infinity\n";
        List<Object> constants = LlmConstantPoolEnricher.parseConstants(response);
        assertTrue(constants.contains(Double.NEGATIVE_INFINITY));
        assertFalse(constants.contains(Double.POSITIVE_INFINITY));
    }

    @Test
    void parseConstants_handlesEmptyResponse() {
        assertTrue(LlmConstantPoolEnricher.parseConstants("").isEmpty());
        assertTrue(LlmConstantPoolEnricher.parseConstants(null).isEmpty());
    }

    @Test
    void parseConstants_handlesMalformedInput() {
        String response = "not a number\nfoo\nbar\n";
        List<Object> constants = LlmConstantPoolEnricher.parseConstants(response);
        // Should not throw, may extract partial results
        assertNotNull(constants);
    }

    @Test
    void parseConstants_mixedTypes() {
        String response =
                "\"test string\"\n42\n3.14\n100L\n0.5f\n\"another\"\n-7\n";
        List<Object> constants = LlmConstantPoolEnricher.parseConstants(response);
        assertTrue(constants.stream().anyMatch(c -> "test string".equals(c)));
        assertTrue(constants.stream().anyMatch(c -> "another".equals(c)));
        assertTrue(constants.contains(42));
        assertTrue(constants.contains(3.14));
        assertTrue(constants.contains(100L));
        assertTrue(constants.contains(0.5f));
        assertTrue(constants.contains(-7));
    }

    // ---- Unescape tests ----

    @Test
    void unescapeJavaString_handlesEscapes() {
        assertEquals("hello\nworld", LlmConstantPoolEnricher.unescapeJavaString("hello\\nworld"));
        assertEquals("tab\there", LlmConstantPoolEnricher.unescapeJavaString("tab\\there"));
        assertEquals("quote\"s", LlmConstantPoolEnricher.unescapeJavaString("quote\\\"s"));
        assertEquals("back\\slash", LlmConstantPoolEnricher.unescapeJavaString("back\\\\slash"));
        assertEquals("", LlmConstantPoolEnricher.unescapeJavaString(null));
    }

    // ---- Async/failure behavior tests ----

    @Test
    void enrichAsync_returnsFailureOnUnavailableService() throws Exception {
        LlmService service = createUnavailableService();
        LlmConstantPoolEnricher enricher = new LlmConstantPoolEnricher(service);

        CompletableFuture<LlmConstantPoolEnricher.EnrichmentResult> future =
                enricher.enrichAsync("com.example.Foo", null);

        LlmConstantPoolEnricher.EnrichmentResult result = future.get(5, TimeUnit.SECONDS);
        assertFalse(result.isSuccess());
        assertEquals(0, result.getConstantsAdded());
    }

    @Test
    void enrichAsync_completesWithinTimeout() throws Exception {
        // Create a service that will throw (simulating unavailable model)
        LlmService service = createUnavailableService();
        LlmConstantPoolEnricher enricher = new LlmConstantPoolEnricher(service);

        CompletableFuture<LlmConstantPoolEnricher.EnrichmentResult> future =
                enricher.enrichAsync("com.example.Foo", null);

        // Should complete quickly, not hang
        LlmConstantPoolEnricher.EnrichmentResult result = future.get(5, TimeUnit.SECONDS);
        assertNotNull(result);
    }

    @Test
    void doEnrich_recordsConstantsAtInsertionTime() {
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.CONSTANT_POOL_ENRICHMENT, "\"edge\"\n42\n7L\n");
        LlmConstantPoolEnricher enricher = new LlmConstantPoolEnricher(createService(model, 1));

        LlmConstantPoolEnricher.EnrichmentResult result =
                enricher.doEnrich("com.example.Foo", null);

        assertEquals(3, result.getSutConstantsAdded());
        assertEquals(3L, LlmStatistics.getSutConstantsAdded());
        assertEquals(0L, LlmStatistics.getNonSutConstantsAdded());
    }

    @Test
    void enrichResult_skipped() {
        LlmConstantPoolEnricher.EnrichmentResult result =
                LlmConstantPoolEnricher.EnrichmentResult.skipped("test reason");
        assertFalse(result.isAttempted());
        assertFalse(result.isSuccess());
        assertEquals("test reason", result.getFailureReason());
        assertEquals(0, result.getConstantsAdded());
        assertEquals(0, result.getSutConstantsAdded());
        assertEquals(0, result.getNonSutConstantsAdded());
        assertEquals(0, result.getConstantsParsed());
    }

    @Test
    void enrichResult_failure() {
        LlmConstantPoolEnricher.EnrichmentResult result =
                LlmConstantPoolEnricher.EnrichmentResult.failure("error msg");
        assertTrue(result.isAttempted());
        assertFalse(result.isSuccess());
        assertEquals("error msg", result.getFailureReason());
    }

    @Test
    void enrichResult_success() {
        LlmConstantPoolEnricher.EnrichmentResult result =
                new LlmConstantPoolEnricher.EnrichmentResult(true, 5, 2, 10, null);
        assertTrue(result.isAttempted());
        assertTrue(result.isSuccess());
        assertEquals(7, result.getConstantsAdded());
        assertEquals(5, result.getSutConstantsAdded());
        assertEquals(2, result.getNonSutConstantsAdded());
        assertEquals(10, result.getConstantsParsed());
        assertNull(result.getFailureReason());
    }

    @Test
    void cancellationAfterInterruptIgnoringQueryDoesNotMutateConstantPool() throws Exception {
        CountDownLatch queryEntered = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        CountDownLatch queryFinished = new CountDownLatch(1);
        LlmService.ChatLanguageModel model = (messages, feature) -> {
            queryEntered.countDown();
            boolean interrupted = false;
            while (releaseQuery.getCount() != 0) {
                try {
                    releaseQuery.await();
                } catch (InterruptedException ignored) {
                    // Simulate a transport that does not honour cancellation.
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            queryFinished.countDown();
            return LlmService.LlmResponse.fromText("\"late-constant-marker\"");
        };
        LlmService service = createService(model, 4);
        LlmConstantPoolEnricher enricher = new LlmConstantPoolEnricher(service);

        try {
            CompletableFuture<LlmConstantPoolEnricher.EnrichmentResult> future =
                    enricher.enrichAsync("com.example.Target", null);
            assertTrue(queryEntered.await(5, TimeUnit.SECONDS));

            enricher.cancel();
            future.cancel(true);
            releaseQuery.countDown();
            assertTrue(queryFinished.await(5, TimeUnit.SECONDS));

            Thread.sleep(100L);
            assertFalse(ConstantPoolManager.getInstance().getSUTConstantPool().getStrings()
                            .contains("late-constant-marker"),
                    "a response that returns after cancellation must not mutate the constant pool");
        } finally {
            releaseQuery.countDown();
            service.close();
        }
    }

    // ---- Prompt building test ----

    @Test
    void buildPrompt_producesNonEmptyMessages() {
        LlmService service = createUnavailableService();
        LlmConstantPoolEnricher enricher = new LlmConstantPoolEnricher(service);

        // buildPrompt doesn't require a real cluster
        PromptResult result = enricher.buildPrompt("com.example.MyClass", null);
        assertNotNull(result);
        assertFalse(result.getMessages().isEmpty());
        // Should contain the instruction about constants
        String combined = result.getMessages().stream()
                .map(LlmMessage::getContent)
                .reduce("", String::concat);
        assertTrue(combined.contains("constant") || combined.contains("edge case") || combined.contains("boundary"));
    }

    @Test
    void collectNonSutDependencyClasses_excludesSutAndJdkAndRespectsCap() {
        boolean oldEnrich = Properties.LLM_ENRICH_NON_SUT_CONSTANT_POOL;
        int oldMaxClasses = Properties.LLM_NON_SUT_CONSTANT_CLASSES_MAX;
        Properties.LLM_ENRICH_NON_SUT_CONSTANT_POOL = true;
        Properties.LLM_NON_SUT_CONSTANT_CLASSES_MAX = 2;
        try {
            TestCluster cluster = mock(TestCluster.class);
            Set<Class<?>> analyzed = new LinkedHashSet<>();
            analyzed.add(String.class);                 // JDK, excluded
            analyzed.add(java.util.ArrayList.class);    // SUT, excluded
            analyzed.add(java.util.HashMap.class);      // JDK, excluded
            // Two non-JDK domain classes (reuse types from this test class's
            // package — anything non-JDK qualifies as "dependency")
            analyzed.add(LlmConstantPoolEnricherTest.class);
            analyzed.add(org.evosuite.llm.LlmService.class);
            when(cluster.getAnalyzedClasses()).thenReturn(analyzed);

            LlmConstantPoolEnricher enricher = new LlmConstantPoolEnricher(createUnavailableService());
            List<String> deps = enricher.collectNonSutDependencyClasses(
                    java.util.ArrayList.class.getName(), cluster);

            assertEquals(2, deps.size(), "cap respected");
            assertFalse(deps.contains(String.class.getName()), "JDK java.lang excluded");
            assertFalse(deps.contains(java.util.HashMap.class.getName()), "JDK java.util excluded");
            assertFalse(deps.contains(java.util.ArrayList.class.getName()), "SUT excluded");
        } finally {
            Properties.LLM_ENRICH_NON_SUT_CONSTANT_POOL = oldEnrich;
            Properties.LLM_NON_SUT_CONSTANT_CLASSES_MAX = oldMaxClasses;
        }
    }

    @Test
    void capDependencyConstants_respectsConfiguredLimit() {
        int oldMax = Properties.LLM_NON_SUT_CONSTANTS_PER_CLASS_MAX;
        Properties.LLM_NON_SUT_CONSTANTS_PER_CLASS_MAX = 3;
        try {
            LlmConstantPoolEnricher enricher = new LlmConstantPoolEnricher(createUnavailableService());
            List<Object> capped = enricher.capDependencyConstants(
                    java.util.Arrays.<Object>asList(1, 2, 3, 4, 5));
            assertEquals(3, capped.size());
            assertEquals(1, capped.get(0));
            assertEquals(3, capped.get(2));
        } finally {
            Properties.LLM_NON_SUT_CONSTANTS_PER_CLASS_MAX = oldMax;
        }
    }

    // ---- Issue 2: FEW_SHOT not injected into strict structured-output enrichers ----

    @Test
    void buildPrompt_doesNotContainFewShotSnippetsEvenWhenFewShotEnabled() {
        Properties.LLM_PROMPT_TECHNIQUE = Properties.LlmPromptTechnique.FEW_SHOT;
        Properties.LLM_FEW_SHOT_USE_PARSED_JUNIT = true;
        Properties.LLM_FEW_SHOT_USE_ARCHIVE = false;

        LlmService service = createUnavailableService();
        LlmConstantPoolEnricher enricher = new LlmConstantPoolEnricher(service);

        PromptResult result = enricher.buildPrompt("com.example.MyClass", null);
        String combined = result.getMessages().stream()
                .map(LlmMessage::getContent)
                .reduce("", String::concat);
        assertFalse(combined.contains("Existing tests:"),
                "Constant pool enricher prompt must not contain FEW_SHOT examples");
    }

    // ---- Prompt sharpening: structured-data persona, no test-code reminders, no dep summary ----

    @Test
    void buildPrompt_usesEnrichmentSystemPromptNotTestGenerationPersona() {
        LlmConstantPoolEnricher enricher = new LlmConstantPoolEnricher(createUnavailableService());
        PromptResult result = enricher.buildPrompt("com.example.MyClass", null);

        String systemContent = result.getMessages().get(0).getContent();
        assertTrue(systemContent.contains("data-extraction assistant"),
                "Expected enrichment system prompt, got: " + systemContent);
        assertFalse(systemContent.contains("expert Java test generation assistant"),
                "Test-generation persona must not leak into enrichment prompts: " + systemContent);
        assertFalse(systemContent.contains("Generate only valid Java JUnit"),
                "Test-code generation directive must not appear in enrichment prompts");
    }

    @Test
    void buildPrompt_omitsTestGenerationRemindersAndDependencyContext() {
        // Mock a non-null cluster so the dependency-context code path would normally run
        TestCluster cluster = mock(TestCluster.class);
        when(cluster.getTestCalls()).thenReturn(new ArrayList<GenericAccessibleObject<?>>());
        when(cluster.getGeneratorsByType()).thenReturn(new java.util.HashMap<>());
        when(cluster.getAnalyzedClasses()).thenReturn(new LinkedHashSet<Class<?>>());

        LlmConstantPoolEnricher enricher = new LlmConstantPoolEnricher(createUnavailableService());
        PromptResult result = enricher.buildPrompt("com.example.MyClass", cluster);

        String userContent = result.getMessages().get(1).getContent();
        assertFalse(userContent.contains("Ensure that all generic types"),
                "Generic-type reminder is a test-code guardrail; should be omitted: " + userContent);
        assertFalse(userContent.contains("Do NOT access private or protected"),
                "Visibility reminder is a test-code guardrail; should be omitted: " + userContent);
        assertFalse(userContent.contains("Available dependency types"),
                "Dependency cluster summary should be omitted from constants prompt: " + userContent);
    }

    // ---- buildParameterTypeDigest tests ----

    @Test
    void buildParameterTypeDigest_includesMethodsWithConstantParams() throws Exception {
        TestCluster cluster = mock(TestCluster.class);

        // Mock a method void setValue(String)
        GenericAccessibleObject<?> call = mock(GenericAccessibleObject.class);
        when(call.isMethod()).thenReturn(true);
        Method method = String.class.getMethod("substring", int.class);
        doReturn(method).when(call).getAccessibleObject();

        List<GenericAccessibleObject<?>> testCalls = new ArrayList<>();
        testCalls.add(call);
        when(cluster.getTestCalls()).thenReturn(testCalls);

        LlmConstantPoolEnricher enricher = new LlmConstantPoolEnricher(createUnavailableService());
        String digest = enricher.buildParameterTypeDigest(cluster);

        assertTrue(digest.contains("substring"), "Expected method name: " + digest);
        assertTrue(digest.contains("int"), "Expected parameter type: " + digest);
        assertTrue(digest.contains("CUT methods"), "Expected header: " + digest);
    }

    @Test
    void buildParameterTypeDigest_excludesNonConstantParams() throws Exception {
        TestCluster cluster = mock(TestCluster.class);

        // Mock a method with only non-constant params (e.g., Object param)
        GenericAccessibleObject<?> call = mock(GenericAccessibleObject.class);
        when(call.isMethod()).thenReturn(true);
        Method method = ArrayList.class.getMethod("addAll", java.util.Collection.class);
        doReturn(method).when(call).getAccessibleObject();

        List<GenericAccessibleObject<?>> testCalls = new ArrayList<>();
        testCalls.add(call);
        when(cluster.getTestCalls()).thenReturn(testCalls);

        LlmConstantPoolEnricher enricher = new LlmConstantPoolEnricher(createUnavailableService());
        String digest = enricher.buildParameterTypeDigest(cluster);

        assertEquals("", digest, "Should exclude methods with only non-constant params");
    }

    @Test
    void buildParameterTypeDigest_emptyOnNullCluster() {
        LlmConstantPoolEnricher enricher = new LlmConstantPoolEnricher(createUnavailableService());
        assertEquals("", enricher.buildParameterTypeDigest(null));
    }

    // ---- Helper methods ----

    private static LlmService createUnavailableService() {
        return createService(new MockChatLanguageModel(), 0);
    }

    private static LlmService createService(LlmService.ChatLanguageModel model, int budget) {
        LlmConfiguration configuration = new LlmConfiguration(
                Properties.LlmProvider.NONE,
                "mock",
                "",
                "",
                0.0,
                1024,
                2,
                0,
                1,
                false,
                Paths.get("target/llm-test-traces"),
                "test-constant-enrichment");
        return new LlmService(
                model,
                new LlmBudgetCoordinator.Local(budget),
                configuration,
                new LlmStatistics(),
                new LlmTraceRecorder(configuration));
    }
}
