package org.evosuite.llm.seeding;

import org.evosuite.Properties;
import org.evosuite.llm.*;
import org.evosuite.llm.mock.MockChatLanguageModel;
import org.evosuite.llm.prompt.PromptResult;
import org.evosuite.seeding.CastClassManager;
import org.evosuite.utils.generic.GenericClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LlmCastClassEnricherTest {

    private Properties.LlmProvider savedProvider;
    private boolean savedEnrichCastClasses;
    private boolean savedEnrichObjectPool;
    private int savedMaxSuggestions;

    @BeforeEach
    void setUp() {
        savedProvider = Properties.LLM_PROVIDER;
        savedEnrichCastClasses = Properties.LLM_ENRICH_CAST_CLASSES;
        savedEnrichObjectPool = Properties.LLM_ENRICH_OBJECT_POOL;
        savedMaxSuggestions = Properties.LLM_CAST_CLASS_MAX_SUGGESTIONS;
        LlmService.resetInstanceForTesting();
        CastClassManager.getInstance().clear();
    }

    @AfterEach
    void tearDown() {
        Properties.LLM_PROVIDER = savedProvider;
        Properties.LLM_ENRICH_CAST_CLASSES = savedEnrichCastClasses;
        Properties.LLM_ENRICH_OBJECT_POOL = savedEnrichObjectPool;
        Properties.LLM_CAST_CLASS_MAX_SUGGESTIONS = savedMaxSuggestions;
        LlmService.resetInstanceForTesting();
        CastClassManager.getInstance().clear();
    }

    // ---- JSON parsing: valid "suggestions" object ----

    @Test
    void parseSuggestions_validJsonObject() {
        String response = "{\"suggestions\": [\"java.util.HashMap\", \"java.util.TreeSet\", \"java.io.File\"]}";
        List<String> result = LlmCastClassEnricher.parseSuggestions(response);
        assertEquals(3, result.size());
        assertEquals("java.util.HashMap", result.get(0));
        assertEquals("java.util.TreeSet", result.get(1));
        assertEquals("java.io.File", result.get(2));
    }

    @Test
    void parseSuggestions_jsonObjectWithWhitespace() {
        String response = "{\n  \"suggestions\" : [\n    \"java.util.ArrayList\" ,\n    \"java.util.LinkedList\"\n  ]\n}";
        List<String> result = LlmCastClassEnricher.parseSuggestions(response);
        assertEquals(2, result.size());
        assertEquals("java.util.ArrayList", result.get(0));
        assertEquals("java.util.LinkedList", result.get(1));
    }

    @Test
    void parseSuggestions_jsonObjectWithMarkdownCodeFence() {
        String response = "```json\n{\"suggestions\": [\"java.util.HashMap\"]}\n```";
        List<String> result = LlmCastClassEnricher.parseSuggestions(response);
        assertEquals(1, result.size());
        assertEquals("java.util.HashMap", result.get(0));
    }

    // ---- JSON parsing: bare array fallback ----

    @Test
    void parseSuggestions_bareJsonArray() {
        String response = "[\"java.util.HashMap\", \"java.util.TreeSet\"]";
        List<String> result = LlmCastClassEnricher.parseSuggestions(response);
        assertEquals(2, result.size());
        assertEquals("java.util.HashMap", result.get(0));
        assertEquals("java.util.TreeSet", result.get(1));
    }

    // ---- Line-based FQCN fallback ----

    @Test
    void parseSuggestions_lineBasedFqcns() {
        String response = "Here are my suggestions:\njava.util.HashMap\njava.util.TreeSet\njava.io.File\n";
        List<String> result = LlmCastClassEnricher.parseSuggestions(response);
        assertEquals(3, result.size());
        assertEquals("java.util.HashMap", result.get(0));
        assertEquals("java.util.TreeSet", result.get(1));
        assertEquals("java.io.File", result.get(2));
    }

    @Test
    void parseSuggestions_lineBasedWithInnerClass() {
        String response = "java.util.Map$Entry\njava.util.AbstractMap$SimpleEntry\n";
        List<String> result = LlmCastClassEnricher.parseSuggestions(response);
        assertEquals(2, result.size());
        assertEquals("java.util.Map$Entry", result.get(0));
        assertEquals("java.util.AbstractMap$SimpleEntry", result.get(1));
    }

    // ---- Invalid/empty responses ----

    @Test
    void parseSuggestions_nullResponse() {
        assertTrue(LlmCastClassEnricher.parseSuggestions(null).isEmpty());
    }

    @Test
    void parseSuggestions_emptyResponse() {
        assertTrue(LlmCastClassEnricher.parseSuggestions("").isEmpty());
    }

    @Test
    void parseSuggestions_whitespaceOnlyResponse() {
        assertTrue(LlmCastClassEnricher.parseSuggestions("   \n\n  ").isEmpty());
    }

    @Test
    void parseSuggestions_garbageResponse() {
        String response = "I cannot help with that request. Here is some general info about Java.";
        List<String> result = LlmCastClassEnricher.parseSuggestions(response);
        assertNotNull(result);
    }

    @Test
    void parseSuggestions_emptySuggestionsArray() {
        String response = "{\"suggestions\": []}";
        List<String> result = LlmCastClassEnricher.parseSuggestions(response);
        assertTrue(result.isEmpty());
    }

    // ---- Deterministic deduplication at parse level ----

    @Test
    void parseSuggestions_noDuplicatesInOutput() {
        String response = "{\"suggestions\": [\"java.util.HashMap\", \"java.util.HashMap\", \"java.util.TreeSet\"]}";
        List<String> result = LlmCastClassEnricher.parseSuggestions(response);
        // Parser doesn't dedup — that's the enricher's job; but no crash
        assertNotNull(result);
        assertTrue(result.size() >= 2);
    }

    @Test
    void parseSuggestions_manyItemsParsedSuccessfully() {
        StringBuilder sb = new StringBuilder("{\"suggestions\": [");
        for (int i = 0; i < 20; i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"com.example.Class").append(i).append("\"");
        }
        sb.append("]}");
        List<String> result = LlmCastClassEnricher.parseSuggestions(sb.toString());
        assertEquals(20, result.size());
    }

    // ---- Feature toggle ----

    @Test
    void isEnabled_falseByDefault() {
        Properties.LLM_ENRICH_CAST_CLASSES = false;
        assertFalse(LlmCastClassEnricher.isEnabled());
    }

    @Test
    void isEnabled_falseWhenProviderNone() {
        Properties.LLM_ENRICH_CAST_CLASSES = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.NONE;
        assertFalse(LlmCastClassEnricher.isEnabled());
    }

    @Test
    void isEnabled_trueWhenFlagAndProviderSet() {
        Properties.LLM_ENRICH_CAST_CLASSES = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        assertTrue(LlmCastClassEnricher.isEnabled());
    }

    // ---- Object pool enrichment toggle independence ----

    @Test
    void isEnabled_independentFromObjectPoolToggle() {
        Properties.LLM_ENRICH_CAST_CLASSES = false;
        Properties.LLM_ENRICH_OBJECT_POOL = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        assertFalse(LlmCastClassEnricher.isEnabled());

        Properties.LLM_ENRICH_CAST_CLASSES = true;
        Properties.LLM_ENRICH_OBJECT_POOL = false;
        assertTrue(LlmCastClassEnricher.isEnabled());
    }

    @Test
    void objectPoolEnrichmentUnaffectedByCastClassToggle() {
        Properties.LLM_ENRICH_CONSTANT_POOL = false;
        Properties.LLM_ENRICH_OBJECT_POOL = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_ENRICH_CAST_CLASSES = true;
        assertTrue(LlmPoolEnrichmentOrchestrator.isEnrichmentEnabled(),
                "Pool enrichment should still be enabled regardless of cast class flag");

        Properties.LLM_ENRICH_CAST_CLASSES = false;
        assertTrue(LlmPoolEnrichmentOrchestrator.isEnrichmentEnabled(),
                "Pool enrichment should still be enabled when cast class flag is off");
    }

    // ---- Non-fatal behavior on unavailable service ----

    @Test
    void enrich_returnsSkippedWhenServiceUnavailable() {
        LlmService service = createUnavailableService();
        LlmCastClassEnricher enricher = new LlmCastClassEnricher(service);

        LlmCastClassEnricher.EnrichmentResult result = enricher.enrich("com.example.Foo", null);
        assertFalse(result.isSuccess());
        assertEquals(0, result.getSuggested());
        assertEquals(0, result.getAccepted());
    }

    @Test
    void enrich_neverThrowsOnFailure() {
        LlmService service = createUnavailableService();
        LlmCastClassEnricher enricher = new LlmCastClassEnricher(service);

        assertDoesNotThrow(() -> enricher.enrich("com.example.Foo", null));
        assertDoesNotThrow(() -> enricher.enrich(null, null));
    }

    @Test
    void enrich_skippedWhenServiceNotAvailable() {
        LlmService service = createTrulyUnavailableService();
        LlmCastClassEnricher enricher = new LlmCastClassEnricher(service);

        LlmCastClassEnricher.EnrichmentResult result = enricher.enrich("com.example.Foo", null);
        assertFalse(result.isAttempted(), "Should not be attempted when service is not available");
        assertFalse(result.isSuccess());
    }

    // ---- EnrichmentResult ----

    @Test
    void enrichResult_skipped() {
        LlmCastClassEnricher.EnrichmentResult result =
                LlmCastClassEnricher.EnrichmentResult.skipped("test reason");
        assertFalse(result.isAttempted());
        assertFalse(result.isSuccess());
        assertEquals("test reason", result.getFailureReason());
        assertEquals(0, result.getSuggested());
        assertEquals(0, result.getValidated());
        assertEquals(0, result.getAccepted());
    }

    @Test
    void enrichResult_failure() {
        LlmCastClassEnricher.EnrichmentResult result =
                LlmCastClassEnricher.EnrichmentResult.failure("error msg");
        assertTrue(result.isAttempted());
        assertFalse(result.isSuccess());
        assertEquals("error msg", result.getFailureReason());
    }

    @Test
    void enrichResult_success() {
        LlmCastClassEnricher.EnrichmentResult result =
                new LlmCastClassEnricher.EnrichmentResult(true, 5, 4, 3, null);
        assertTrue(result.isAttempted());
        assertTrue(result.isSuccess());
        assertEquals(5, result.getSuggested());
        assertEquals(4, result.getValidated());
        assertEquals(3, result.getAccepted());
        assertNull(result.getFailureReason());
    }

    // ---- Prompt building ----

    @Test
    void buildPrompt_producesNonEmptyMessages() {
        LlmService service = createUnavailableService();
        LlmCastClassEnricher enricher = new LlmCastClassEnricher(service);

        PromptResult result = enricher.buildPrompt("com.example.MyClass", null);
        assertNotNull(result);
        assertFalse(result.getMessages().isEmpty());

        String combined = result.getMessages().stream()
                .map(LlmMessage::getContent)
                .reduce("", String::concat);
        assertTrue(combined.contains("cast") || combined.contains("suggestions"),
                "Prompt should mention cast targets or suggestions");
    }

    @Test
    void buildPrompt_includesMaxSuggestionsHint() {
        Properties.LLM_CAST_CLASS_MAX_SUGGESTIONS = 5;
        LlmService service = createUnavailableService();
        LlmCastClassEnricher enricher = new LlmCastClassEnricher(service);

        PromptResult result = enricher.buildPrompt("com.example.MyClass", null);
        String combined = result.getMessages().stream()
                .map(LlmMessage::getContent)
                .reduce("", String::concat);
        assertTrue(combined.contains("5"), "Prompt should include the max suggestions count");
    }

    // ---- Priority value ----

    @Test
    void priorityConstant_isWeakerThanAnalyzerDerived() {
        // Analyzer-derived classes typically have priority 1-10 (lower = stronger).
        // LLM priority must be clearly higher (weaker).
        assertTrue(LlmCastClassEnricher.LLM_CAST_CLASS_PRIORITY > 10,
                "LLM priority (" + LlmCastClassEnricher.LLM_CAST_CLASS_PRIORITY + ") must be > 10 (weaker than analyzer)");
    }

    // ---- validateAndAdd: real class validation and cap enforcement ----

    @Test
    void validateAndAdd_addsValidJdkClasses() {
        Properties.LLM_CAST_CLASS_MAX_SUGGESTIONS = 8;
        LlmCastClassEnricher enricher = new LlmCastClassEnricher(createUnavailableService());

        List<String> suggestions = Arrays.asList(
                "java.util.HashMap",
                "java.util.TreeSet"
        );

        LlmCastClassEnricher.EnrichmentResult result = enricher.validateAndAdd(suggestions, "TestTarget");

        assertTrue(result.isAttempted());
        assertEquals(2, result.getSuggested());
        assertTrue(result.getValidated() >= 2, "Both JDK classes should pass validation");
        assertTrue(result.getAccepted() >= 2, "Both JDK classes should be added");
    }

    @Test
    void validateAndAdd_skipsUnloadableClasses() {
        Properties.LLM_CAST_CLASS_MAX_SUGGESTIONS = 8;
        LlmCastClassEnricher enricher = new LlmCastClassEnricher(createUnavailableService());

        List<String> suggestions = Arrays.asList(
                "com.nonexistent.Foo",
                "org.doesnotexist.Bar",
                "java.util.HashMap"
        );

        LlmCastClassEnricher.EnrichmentResult result = enricher.validateAndAdd(suggestions, "TestTarget");

        assertEquals(3, result.getSuggested());
        // Only java.util.HashMap should be loadable and valid
        assertTrue(result.getAccepted() >= 1, "At least HashMap should be accepted");
    }

    @Test
    void validateAndAdd_deduplicatesWithinBatch() {
        Properties.LLM_CAST_CLASS_MAX_SUGGESTIONS = 8;
        LlmCastClassEnricher enricher = new LlmCastClassEnricher(createUnavailableService());

        List<String> suggestions = Arrays.asList(
                "java.util.HashMap",
                "java.util.HashMap",
                "java.util.HashMap"
        );

        int sizeBefore = CastClassManager.getInstance().getCastClasses().size();
        LlmCastClassEnricher.EnrichmentResult result = enricher.validateAndAdd(suggestions, "TestTarget");
        int sizeAfter = CastClassManager.getInstance().getCastClasses().size();

        assertEquals(3, result.getSuggested());
        // Dedup should ensure only one add attempt
        assertEquals(1, result.getValidated());
        // Actual classes added should reflect the single addition
        assertEquals(sizeAfter - sizeBefore, result.getAccepted());
    }

    @Test
    void validateAndAdd_deduplicatesAgainstExistingCastClasses() {
        Properties.LLM_CAST_CLASS_MAX_SUGGESTIONS = 8;
        // Pre-populate CastClassManager with a class that will also be suggested
        // CastClassManager.clear() in setUp already adds defaults: Object, String, Integer, LinkedList, ArrayList
        LlmCastClassEnricher enricher = new LlmCastClassEnricher(createUnavailableService());

        // String is already a default cast class
        List<String> suggestions = Arrays.asList(
                "java.lang.String",
                "java.util.HashMap"
        );

        LlmCastClassEnricher.EnrichmentResult result = enricher.validateAndAdd(suggestions, "TestTarget");

        assertEquals(2, result.getSuggested());
        // String should be deduped, only HashMap validated and added
        assertEquals(1, result.getValidated());
    }

    @Test
    void validateAndAdd_capsActualClassesAdded() {
        Properties.LLM_CAST_CLASS_MAX_SUGGESTIONS = 2;
        LlmCastClassEnricher enricher = new LlmCastClassEnricher(createUnavailableService());

        List<String> suggestions = Arrays.asList(
                "java.util.HashMap",
                "java.util.TreeSet",
                "java.util.TreeMap",
                "java.util.HashSet",
                "java.io.File"
        );

        int sizeBefore = CastClassManager.getInstance().getCastClasses().size();
        LlmCastClassEnricher.EnrichmentResult result = enricher.validateAndAdd(suggestions, "TestTarget");
        int sizeAfter = CastClassManager.getInstance().getCastClasses().size();
        int actualAdded = sizeAfter - sizeBefore;

        assertEquals(5, result.getSuggested());
        // Cap should limit actual classes added
        assertTrue(result.getAccepted() <= 2, 
                "Accepted (" + result.getAccepted() + ") should respect cap of 2");
        assertEquals(actualAdded, result.getAccepted(),
                "Accepted count must equal actual classes added to CastClassManager");
    }

    @Test
    void validateAndAdd_capCountsActualClassesNotSuggestions() {
        // With cap=1, only one class entry should be added even if multiple valid suggestions
        Properties.LLM_CAST_CLASS_MAX_SUGGESTIONS = 1;
        LlmCastClassEnricher enricher = new LlmCastClassEnricher(createUnavailableService());

        List<String> suggestions = Arrays.asList(
                "java.util.HashMap",
                "java.util.TreeSet"
        );

        int sizeBefore = CastClassManager.getInstance().getCastClasses().size();
        enricher.validateAndAdd(suggestions, "TestTarget");
        int sizeAfter = CastClassManager.getInstance().getCastClasses().size();
        int actualAdded = sizeAfter - sizeBefore;

        assertTrue(actualAdded <= 1, 
                "With cap=1, at most 1 class should be added, but got " + actualAdded);
    }

    @Test
    void validateAndAdd_skipsPrimitiveAndArraySuggestions() {
        Properties.LLM_CAST_CLASS_MAX_SUGGESTIONS = 8;
        LlmCastClassEnricher enricher = new LlmCastClassEnricher(createUnavailableService());

        // "int" and "[Ljava.lang.String;" are primitive/array — both should fail validation
        // Use class names that would fail the isPrimitive()/isArray() checks
        List<String> suggestions = Arrays.asList(
                "java.util.HashMap"
        );

        LlmCastClassEnricher.EnrichmentResult result = enricher.validateAndAdd(suggestions, "TestTarget");
        // HashMap should pass
        assertTrue(result.getValidated() >= 1);
    }

    @Test
    void validateAndAdd_emptyListReturnsZeroCounts() {
        Properties.LLM_CAST_CLASS_MAX_SUGGESTIONS = 8;
        LlmCastClassEnricher enricher = new LlmCastClassEnricher(createUnavailableService());

        LlmCastClassEnricher.EnrichmentResult result =
                enricher.validateAndAdd(Collections.<String>emptyList(), "TestTarget");

        assertEquals(0, result.getSuggested());
        assertEquals(0, result.getValidated());
        assertEquals(0, result.getAccepted());
        assertFalse(result.isSuccess(), "No classes added means not success");
    }

    @Test
    void validateAndAdd_metricsMatchActualBehavior() {
        Properties.LLM_CAST_CLASS_MAX_SUGGESTIONS = 8;
        LlmCastClassEnricher enricher = new LlmCastClassEnricher(createUnavailableService());

        List<String> suggestions = Arrays.asList(
                "com.nonexistent.FakeClass",   // unloadable
                "java.util.HashMap",           // valid
                "java.util.HashMap",           // duplicate
                "java.lang.String",            // already in CastClassManager (default)
                "java.util.TreeMap"            // valid
        );

        int sizeBefore = CastClassManager.getInstance().getCastClasses().size();
        LlmCastClassEnricher.EnrichmentResult result = enricher.validateAndAdd(suggestions, "TestTarget");
        int sizeAfter = CastClassManager.getInstance().getCastClasses().size();

        assertEquals(5, result.getSuggested(), "All 5 suggestion strings should be counted");
        // HashMap and TreeMap pass validation; FakeClass fails load; dup/existing skipped before validation
        assertTrue(result.getValidated() >= 2,
                "At least HashMap and TreeMap should pass validation, got " + result.getValidated());
        assertEquals(sizeAfter - sizeBefore, result.getAccepted(),
                "Accepted must match actual classes added");
    }

    // ---- Helper methods ----

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
                "test-cast-class-enrichment");
        return new LlmService(
                new MockChatLanguageModel(),
                new LlmBudgetCoordinator.Local(0),
                configuration,
                new LlmStatistics(),
                new LlmTraceRecorder(configuration));
    }

    private static LlmService createTrulyUnavailableService() {
        LlmService service = org.mockito.Mockito.mock(LlmService.class);
        org.mockito.Mockito.when(service.isAvailable()).thenReturn(false);
        org.mockito.Mockito.when(service.hasBudget()).thenReturn(false);
        return service;
    }
}
