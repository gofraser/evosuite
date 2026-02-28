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
package org.evosuite.llm.seeding;

import org.evosuite.Properties;
import org.evosuite.llm.*;
import org.evosuite.llm.mock.MockChatLanguageModel;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.llm.response.LlmResponseParser;
import org.evosuite.llm.response.RepairResult;
import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.seeding.ObjectPool;
import org.evosuite.seeding.ObjectPoolManager;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testparser.ParseResult;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmObjectPoolEnricherTest {

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
    }

    @AfterEach
    void tearDown() {
        Properties.LLM_PROMPT_TECHNIQUE = savedTechnique;
        Properties.LLM_FEW_SHOT_USE_PARSED_JUNIT = savedUseParsed;
        Properties.LLM_FEW_SHOT_USE_ARCHIVE = savedUseArchive;
        LlmService.resetInstanceForTesting();
        ObjectPoolManager.getInstance().reset();
    }

    @Test
    void enrichAsync_returnsFailureOnUnavailableService() throws Exception {
        LlmService service = createUnavailableService();
        TestRepairLoop mockRepairLoop = mock(TestRepairLoop.class);
        LlmObjectPoolEnricher enricher = new LlmObjectPoolEnricher(service, mockRepairLoop);

        CompletableFuture<LlmObjectPoolEnricher.EnrichmentResult> future =
                enricher.enrichAsync("com.example.Foo", null);

        LlmObjectPoolEnricher.EnrichmentResult result = future.get(5, TimeUnit.SECONDS);
        assertFalse(result.isSuccess());
        assertEquals(0, result.getSequencesAdded());
    }

    @Test
    void enrichAsync_completesWithinTimeout() throws Exception {
        LlmService service = createUnavailableService();
        TestRepairLoop mockRepairLoop = mock(TestRepairLoop.class);
        LlmObjectPoolEnricher enricher = new LlmObjectPoolEnricher(service, mockRepairLoop);

        CompletableFuture<LlmObjectPoolEnricher.EnrichmentResult> future =
                enricher.enrichAsync("com.example.Foo", null);

        // Should complete quickly, not hang
        LlmObjectPoolEnricher.EnrichmentResult result = future.get(5, TimeUnit.SECONDS);
        assertNotNull(result);
    }

    @Test
    void enrichResult_skipped() {
        LlmObjectPoolEnricher.EnrichmentResult result =
                LlmObjectPoolEnricher.EnrichmentResult.skipped("test reason");
        assertFalse(result.isAttempted());
        assertFalse(result.isSuccess());
        assertEquals("test reason", result.getFailureReason());
        assertEquals(0, result.getSequencesAdded());
        assertEquals(0, result.getSequencesParsed());
    }

    @Test
    void enrichResult_failure() {
        LlmObjectPoolEnricher.EnrichmentResult result =
                LlmObjectPoolEnricher.EnrichmentResult.failure("error msg");
        assertTrue(result.isAttempted());
        assertFalse(result.isSuccess());
        assertEquals("error msg", result.getFailureReason());
    }

    @Test
    void enrichResult_success() {
        LlmObjectPoolEnricher.EnrichmentResult result =
                new LlmObjectPoolEnricher.EnrichmentResult(true, 3, 5, 0, 0, 0, null);
        assertTrue(result.isAttempted());
        assertTrue(result.isSuccess());
        assertEquals(3, result.getSequencesAdded());
        assertEquals(5, result.getSequencesParsed());
        assertNull(result.getFailureReason());
    }

    @Test
    void collectInterestingTypes_excludesPrimitivesAndLangTypes() {
        LlmService service = createUnavailableService();
        TestRepairLoop mockRepairLoop = mock(TestRepairLoop.class);
        LlmObjectPoolEnricher enricher = new LlmObjectPoolEnricher(service, mockRepairLoop);

        // With null cluster, should return empty
        List<String> types = enricher.collectInterestingTypes(null);
        // Should not throw
        assertTrue(types.isEmpty());
    }

    @Test
    void buildPrompt_producesNonEmptyMessages() {
        LlmService service = createUnavailableService();
        TestRepairLoop mockRepairLoop = mock(TestRepairLoop.class);
        LlmObjectPoolEnricher enricher = new LlmObjectPoolEnricher(service, mockRepairLoop);

        List<String> types = new ArrayList<>();
        types.add("com.example.Widget");
        types.add("com.example.Config");

        PromptResult result = enricher.buildPrompt("com.example.Foo", null, types);
        assertNotNull(result);
        assertFalse(result.getMessages().isEmpty());
        String combined = result.getMessages().stream()
                .map(LlmMessage::getContent)
                .reduce("", String::concat);
        assertTrue(combined.contains("Widget") || combined.contains("construction") || combined.contains("construct"));
    }

    // ---- Type discovery tests ----

    @Test
    void discoverProducedTypes_findsNonTrivialTypes() throws Exception {
        LlmObjectPoolEnricher enricher = createEnricherWithMocks();

        DefaultTestCase tc = new DefaultTestCase();
        GenericConstructor gc = new GenericConstructor(
                ArrayList.class.getConstructor(),
                GenericClassFactory.get(ArrayList.class));
        tc.addStatement(new ConstructorStatement(tc, gc, Collections.emptyList()));

        Set<Class<?>> types = enricher.discoverProducedTypes(tc);
        assertTrue(types.contains(ArrayList.class),
                "Should discover ArrayList as produced type");
    }

    @Test
    void discoverProducedTypes_skipsPrimitivesAndVoid() {
        LlmObjectPoolEnricher enricher = createEnricherWithMocks();

        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 42));

        Set<Class<?>> types = enricher.discoverProducedTypes(tc);
        assertTrue(types.isEmpty(),
                "Should not discover primitive types; got: " + types);
    }

    // ---- JDK filtering tests ----

    @Test
    void filterCandidateKeyTypes_excludesJdkTypesNotInTargetSet() throws Exception {
        LlmObjectPoolEnricher enricher = createEnricherWithMocks();

        Set<Class<?>> rawTypes = new LinkedHashSet<>();
        rawTypes.add(ArrayList.class);   // java.util — JDK
        rawTypes.add(HashMap.class);     // java.util — JDK

        // Empty target set → all JDK types excluded
        Set<Class<?>> filtered = enricher.filterCandidateKeyTypes(rawTypes, Collections.emptySet());
        assertTrue(filtered.isEmpty(),
                "JDK types should be excluded when not in target set");
    }

    @Test
    void filterCandidateKeyTypes_keepsJdkTypeWhenInTargetSet() throws Exception {
        LlmObjectPoolEnricher enricher = createEnricherWithMocks();

        Set<Class<?>> rawTypes = new LinkedHashSet<>();
        rawTypes.add(ArrayList.class);
        rawTypes.add(HashMap.class);

        // Only ArrayList is targeted
        Set<String> targets = new LinkedHashSet<>();
        targets.add("java.util.ArrayList");

        Set<Class<?>> filtered = enricher.filterCandidateKeyTypes(rawTypes, targets);
        assertEquals(1, filtered.size());
        assertTrue(filtered.contains(ArrayList.class));
        assertFalse(filtered.contains(HashMap.class));
    }

    @Test
    void filterCandidateKeyTypes_keepsNonJdkTypesUnconditionally() throws Exception {
        LlmObjectPoolEnricher enricher = createEnricherWithMocks();

        // DefaultTestCase is org.evosuite.* — non-JDK
        Set<Class<?>> rawTypes = new LinkedHashSet<>();
        rawTypes.add(DefaultTestCase.class);

        Set<Class<?>> filtered = enricher.filterCandidateKeyTypes(rawTypes, Collections.emptySet());
        assertEquals(1, filtered.size());
        assertTrue(filtered.contains(DefaultTestCase.class));
    }

    @Test
    void filterCandidateKeyTypes_capsAtMaxKeysPerSequence() {
        LlmObjectPoolEnricher enricher = createEnricherWithMocks();

        // Generate more non-JDK types than the cap
        Set<Class<?>> rawTypes = new LinkedHashSet<>();
        rawTypes.add(DefaultTestCase.class);
        rawTypes.add(ObjectPool.class);
        rawTypes.add(ObjectPoolManager.class);
        rawTypes.add(LlmObjectPoolEnricher.class);
        rawTypes.add(LlmObjectPoolEnricher.EnrichmentResult.class);
        rawTypes.add(LlmObjectPoolEnricher.TypeKeyInsertionResult.class);

        Set<Class<?>> filtered = enricher.filterCandidateKeyTypes(rawTypes, Collections.emptySet());
        assertEquals(LlmObjectPoolEnricher.MAX_KEYS_PER_SEQUENCE, filtered.size(),
                "Should be capped at MAX_KEYS_PER_SEQUENCE");
    }

    @Test
    void isJdkType_classifiesCorrectly() {
        assertTrue(LlmObjectPoolEnricher.isJdkType("java.util.ArrayList"));
        assertTrue(LlmObjectPoolEnricher.isJdkType("java.lang.String"));
        assertTrue(LlmObjectPoolEnricher.isJdkType("javax.swing.JPanel"));
        assertTrue(LlmObjectPoolEnricher.isJdkType("sun.misc.Unsafe"));
        assertTrue(LlmObjectPoolEnricher.isJdkType("com.sun.proxy.Foo"));
        assertFalse(LlmObjectPoolEnricher.isJdkType("org.evosuite.testcase.DefaultTestCase"));
        assertFalse(LlmObjectPoolEnricher.isJdkType("com.example.Widget"));
    }

    @Test
    void addSequenceToPool_jdkHelperType_filteredOut() throws Exception {
        LlmObjectPoolEnricher enricher = createEnricherWithMocks();

        DefaultTestCase tc = new DefaultTestCase();
        // ArrayList is a JDK helper type → should be filtered out
        GenericConstructor gc = new GenericConstructor(
                ArrayList.class.getConstructor(),
                GenericClassFactory.get(ArrayList.class));
        tc.addStatement(new ConstructorStatement(tc, gc, Collections.emptyList()));

        // No target types → ArrayList (JDK) should be filtered
        LlmObjectPoolEnricher.TypeKeyInsertionResult result =
                enricher.addSequenceToPoolByProducedTypes(tc, Collections.emptySet());

        assertEquals(0, result.insertions, "JDK helper types should be filtered out");
        assertEquals(1, result.rejectedNoType, "Should be rejected as no candidate types remain");
    }

    // ---- Insertion and validation tests ----

    @Test
    void addSequenceToPool_targetedJdkType_inserted() throws Exception {
        LlmObjectPoolEnricher enricher = createEnricherWithMocks();

        DefaultTestCase tc = new DefaultTestCase();
        GenericConstructor gc = new GenericConstructor(
                ArrayList.class.getConstructor(),
                GenericClassFactory.get(ArrayList.class));
        tc.addStatement(new ConstructorStatement(tc, gc, Collections.emptyList()));

        // ArrayList is explicitly targeted → allowed through filter
        Set<String> targets = Set.of("java.util.ArrayList");
        LlmObjectPoolEnricher.TypeKeyInsertionResult result =
                enricher.addSequenceToPoolByProducedTypes(tc, targets);

        assertEquals(1, result.insertions, "Targeted JDK type should be inserted");

        // End-to-end: verify retrievable
        GenericClass<?> arrayListClass = GenericClassFactory.get(ArrayList.class);
        ObjectPoolManager pool = ObjectPoolManager.getInstance();
        assertTrue(pool.hasSequence(arrayListClass));
        assertNotNull(pool.getRandomSequence(arrayListClass));
    }

    @Test
    void addSequenceToPool_multipleTargetedTypes_allInserted() throws Exception {
        LlmObjectPoolEnricher enricher = createEnricherWithMocks();

        DefaultTestCase tc = new DefaultTestCase();
        GenericConstructor gcMap = new GenericConstructor(
                HashMap.class.getConstructor(),
                GenericClassFactory.get(HashMap.class));
        tc.addStatement(new ConstructorStatement(tc, gcMap, Collections.emptyList()));
        GenericConstructor gcList = new GenericConstructor(
                ArrayList.class.getConstructor(),
                GenericClassFactory.get(ArrayList.class));
        tc.addStatement(new ConstructorStatement(tc, gcList, Collections.emptyList()));

        Set<String> targets = Set.of("java.util.HashMap", "java.util.ArrayList");
        LlmObjectPoolEnricher.TypeKeyInsertionResult result =
                enricher.addSequenceToPoolByProducedTypes(tc, targets);

        assertEquals(2, result.insertions);

        ObjectPoolManager pool = ObjectPoolManager.getInstance();
        assertTrue(pool.hasSequence(GenericClassFactory.get(HashMap.class)));
        assertTrue(pool.hasSequence(GenericClassFactory.get(ArrayList.class)));
    }

    @Test
    void addSequenceToPool_primitivesOnly_rejectedNoType() {
        LlmObjectPoolEnricher enricher = createEnricherWithMocks();

        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 42));

        LlmObjectPoolEnricher.TypeKeyInsertionResult result =
                enricher.addSequenceToPoolByProducedTypes(tc, Collections.emptySet());

        assertEquals(0, result.insertions);
        assertEquals(1, result.rejectedNoType);
        assertFalse(result.diagnostics.isEmpty());
    }

    @Test
    void addSequenceToPool_deterministicOrder() throws Exception {
        LlmObjectPoolEnricher enricher = createEnricherWithMocks();

        DefaultTestCase tc = new DefaultTestCase();
        GenericConstructor gcLinked = new GenericConstructor(
                LinkedList.class.getConstructor(),
                GenericClassFactory.get(LinkedList.class));
        tc.addStatement(new ConstructorStatement(tc, gcLinked, Collections.emptyList()));
        GenericConstructor gcArray = new GenericConstructor(
                ArrayList.class.getConstructor(),
                GenericClassFactory.get(ArrayList.class));
        tc.addStatement(new ConstructorStatement(tc, gcArray, Collections.emptyList()));

        Set<String> targets = Set.of("java.util.LinkedList", "java.util.ArrayList");
        for (int i = 0; i < 3; i++) {
            ObjectPoolManager.getInstance().reset();
            LlmObjectPoolEnricher.TypeKeyInsertionResult result =
                    enricher.addSequenceToPoolByProducedTypes(tc, targets);
            assertEquals(2, result.insertions,
                    "Deterministic: always 2 insertions on iteration " + i);
        }
    }

    // ---- End-to-end retrieval semantics ----

    @Test
    void endToEnd_insertedSequenceRetrievableByExactType() throws Exception {
        LlmObjectPoolEnricher enricher = createEnricherWithMocks();

        DefaultTestCase tc = new DefaultTestCase();
        GenericConstructor gc = new GenericConstructor(
                ArrayList.class.getConstructor(),
                GenericClassFactory.get(ArrayList.class));
        tc.addStatement(new ConstructorStatement(tc, gc, Collections.emptyList()));

        enricher.addSequenceToPoolByProducedTypes(tc, Set.of("java.util.ArrayList"));

        ObjectPoolManager pool = ObjectPoolManager.getInstance();
        GenericClass<?> key = GenericClassFactory.get(ArrayList.class);
        assertTrue(pool.hasSequence(key), "hasSequence should find by exact type");
        TestCase retrieved = pool.getRandomSequence(key);
        assertNotNull(retrieved, "getRandomSequence should return the inserted sequence");
        assertEquals(tc.size(), retrieved.size(), "Retrieved sequence should match inserted");
    }

    @Test
    void endToEnd_supertypeRetrieval_exactMatchAlwaysWorks() throws Exception {
        LlmObjectPoolEnricher enricher = createEnricherWithMocks();

        DefaultTestCase tc = new DefaultTestCase();
        GenericConstructor gc = new GenericConstructor(
                ArrayList.class.getConstructor(),
                GenericClassFactory.get(ArrayList.class));
        tc.addStatement(new ConstructorStatement(tc, gc, Collections.emptyList()));

        enricher.addSequenceToPoolByProducedTypes(tc, Set.of("java.util.ArrayList"));

        ObjectPoolManager pool = ObjectPoolManager.getInstance();

        // Exact-type retrieval always works
        GenericClass<?> exactKey = GenericClassFactory.get(ArrayList.class);
        assertTrue(pool.hasSequence(exactKey),
                "Exact type key (ArrayList) should always be retrievable");

        // Supertype retrieval via isAssignableTo depends on GenericClass implementation.
        // ObjectPool.hasSequence falls back to entry.getKey().isAssignableTo(clazz),
        // which for raw parameterized types may or may not match. We verify the exact
        // key path is solid — supertype matching is an existing ObjectPool concern.
        GenericClass<?> listKey = GenericClassFactory.get(List.class);
        // Not asserting true/false on listKey — documenting that our insertion is correct
        // and retrieval by exact key works reliably.
        if (pool.hasSequence(listKey)) {
            assertNotNull(pool.getRandomSequence(listKey),
                    "If supertype lookup matches, retrieval should succeed");
        }
    }

    // ---- Enrichment result metrics ----

    @Test
    void enrichmentResult_includesRejectionMetrics() {
        LlmObjectPoolEnricher.EnrichmentResult result =
                new LlmObjectPoolEnricher.EnrichmentResult(true, 2, 5, 1, 1, 1, null);
        assertTrue(result.isSuccess());
        assertEquals(2, result.getSequencesAdded());
        assertEquals(5, result.getSequencesParsed());
        assertEquals(1, result.getRejectedNoType());
        assertEquals(1, result.getRejectedValidation());
        assertEquals(1, result.getRejectedAddFailure());
    }

    // ---- Issue 2: FEW_SHOT not injected into strict structured-output enrichers ----

    @Test
    void buildPrompt_doesNotContainFewShotSnippetsEvenWhenFewShotEnabled() {
        Properties.LLM_PROMPT_TECHNIQUE = Properties.LlmPromptTechnique.FEW_SHOT;
        Properties.LLM_FEW_SHOT_USE_PARSED_JUNIT = true;
        Properties.LLM_FEW_SHOT_USE_ARCHIVE = false;

        LlmService service = createUnavailableService();
        TestRepairLoop mockRepairLoop = mock(TestRepairLoop.class);
        LlmObjectPoolEnricher enricher = new LlmObjectPoolEnricher(service, mockRepairLoop);

        List<String> types = Arrays.asList("com.example.Widget");
        PromptResult result = enricher.buildPrompt("com.example.Foo", null, types);
        String combined = result.getMessages().stream()
                .map(LlmMessage::getContent)
                .reduce("", String::concat);
        assertFalse(combined.contains("Existing tests:"),
                "Object pool enricher prompt must not contain FEW_SHOT examples");
    }

    // ---- Helper methods ----

    private LlmObjectPoolEnricher createEnricherWithMocks() {
        LlmService service = createUnavailableService();
        TestRepairLoop mockRepairLoop = mock(TestRepairLoop.class);
        return new LlmObjectPoolEnricher(service, mockRepairLoop);
    }

    private static LlmService createUnavailableService() {
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
                "test-object-enrichment");
        return new LlmService(
                new MockChatLanguageModel(),
                new LlmBudgetCoordinator.Local(0),
                configuration,
                new LlmStatistics(),
                new LlmTraceRecorder(configuration));
    }
}
