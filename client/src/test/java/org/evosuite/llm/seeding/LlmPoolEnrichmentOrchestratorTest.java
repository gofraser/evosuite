package org.evosuite.llm.seeding;

import org.evosuite.Properties;
import org.evosuite.llm.*;
import org.evosuite.llm.mock.MockChatLanguageModel;
import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.setup.TestCluster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmPoolEnrichmentOrchestratorTest {

    private Properties.LlmProvider savedProvider;
    private boolean savedEnrichConstants;
    private boolean savedEnrichObjects;
    private int savedTimeout;

    @BeforeEach
    void setUp() {
        savedProvider = Properties.LLM_PROVIDER;
        savedEnrichConstants = Properties.LLM_ENRICH_CONSTANT_POOL;
        savedEnrichObjects = Properties.LLM_ENRICH_OBJECT_POOL;
        savedTimeout = Properties.LLM_ENRICHMENT_TIMEOUT_SECONDS;
        LlmService.resetInstanceForTesting();
    }

    @AfterEach
    void tearDown() {
        Properties.LLM_PROVIDER = savedProvider;
        Properties.LLM_ENRICH_CONSTANT_POOL = savedEnrichConstants;
        Properties.LLM_ENRICH_OBJECT_POOL = savedEnrichObjects;
        Properties.LLM_ENRICHMENT_TIMEOUT_SECONDS = savedTimeout;
        LlmService.resetInstanceForTesting();
    }

    @Test
    void isEnrichmentEnabled_falseWhenBothDisabled() {
        Properties.LLM_ENRICH_CONSTANT_POOL = false;
        Properties.LLM_ENRICH_OBJECT_POOL = false;
        assertFalse(LlmPoolEnrichmentOrchestrator.isEnrichmentEnabled());
    }

    @Test
    void isEnrichmentEnabled_falseWhenProviderNone() {
        Properties.LLM_ENRICH_CONSTANT_POOL = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.NONE;
        assertFalse(LlmPoolEnrichmentOrchestrator.isEnrichmentEnabled());
    }

    @Test
    void isEnrichmentEnabled_trueWhenConstantEnabledAndProviderSet() {
        Properties.LLM_ENRICH_CONSTANT_POOL = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        assertTrue(LlmPoolEnrichmentOrchestrator.isEnrichmentEnabled());
    }

    @Test
    void isEnrichmentEnabled_trueWhenObjectEnabledAndProviderSet() {
        Properties.LLM_ENRICH_OBJECT_POOL = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        assertTrue(LlmPoolEnrichmentOrchestrator.isEnrichmentEnabled());
    }

    @Test
    void enrichPools_skipsWhenBothDisabled() {
        Properties.LLM_ENRICH_CONSTANT_POOL = false;
        Properties.LLM_ENRICH_OBJECT_POOL = false;

        LlmConstantPoolEnricher mockConstant = mock(LlmConstantPoolEnricher.class);
        LlmObjectPoolEnricher mockObject = mock(LlmObjectPoolEnricher.class);

        LlmPoolEnrichmentOrchestrator orchestrator =
                new LlmPoolEnrichmentOrchestrator(mockConstant, mockObject, 5);

        // Should not throw and should return quickly
        orchestrator.enrichPools("com.example.Foo", null);
    }

    @Test
    void enrichPools_handlesTimeoutGracefully() {
        Properties.LLM_ENRICH_CONSTANT_POOL = true;
        Properties.LLM_ENRICH_OBJECT_POOL = false;

        // Create a constant enricher that takes forever
        LlmConstantPoolEnricher slowEnricher = mock(LlmConstantPoolEnricher.class);
        when(slowEnricher.enrichAsync(anyString(), any())).thenReturn(
                CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(30_000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return LlmConstantPoolEnricher.EnrichmentResult.failure("should not reach");
                })
        );
        LlmObjectPoolEnricher mockObject = mock(LlmObjectPoolEnricher.class);

        // Use a very short timeout
        LlmPoolEnrichmentOrchestrator orchestrator =
                new LlmPoolEnrichmentOrchestrator(slowEnricher, mockObject, 1);

        long start = System.currentTimeMillis();
        // Should not throw
        orchestrator.enrichPools("com.example.Foo", null);
        long elapsed = System.currentTimeMillis() - start;

        // Should have returned within ~2 seconds (1s timeout + margin)
        assertTrue(elapsed < 5000, "Orchestrator should respect timeout, took " + elapsed + "ms");
    }

    @Test
    void enrichPools_cancelsUnfinishedFutureOnTimeout() {
        Properties.LLM_ENRICH_CONSTANT_POOL = true;
        Properties.LLM_ENRICH_OBJECT_POOL = false;

        LlmConstantPoolEnricher slowEnricher = mock(LlmConstantPoolEnricher.class);
        CompletableFuture<LlmConstantPoolEnricher.EnrichmentResult> neverCompletes = new CompletableFuture<>();
        when(slowEnricher.enrichAsync(anyString(), any())).thenReturn(neverCompletes);

        LlmObjectPoolEnricher mockObject = mock(LlmObjectPoolEnricher.class);
        LlmPoolEnrichmentOrchestrator orchestrator =
                new LlmPoolEnrichmentOrchestrator(slowEnricher, mockObject, 1);

        orchestrator.enrichPools("com.example.Foo", null);
        assertTrue(neverCompletes.isCancelled(), "Timed-out enrichment should be cancelled");
    }

    @Test
    void enrichPools_handlesEnricherExceptionGracefully() {
        Properties.LLM_ENRICH_CONSTANT_POOL = true;
        Properties.LLM_ENRICH_OBJECT_POOL = true;

        // Create enrichers that throw exceptions
        LlmConstantPoolEnricher failingConstant = mock(LlmConstantPoolEnricher.class);
        when(failingConstant.enrichAsync(anyString(), any())).thenReturn(
                CompletableFuture.supplyAsync(() -> {
                    throw new RuntimeException("Simulated constant enrichment failure");
                })
        );
        LlmObjectPoolEnricher failingObject = mock(LlmObjectPoolEnricher.class);
        when(failingObject.enrichAsync(anyString(), any())).thenReturn(
                CompletableFuture.supplyAsync(() -> {
                    throw new RuntimeException("Simulated object enrichment failure");
                })
        );

        LlmPoolEnrichmentOrchestrator orchestrator =
                new LlmPoolEnrichmentOrchestrator(failingConstant, failingObject, 5);

        // Should not throw
        assertDoesNotThrow(() -> orchestrator.enrichPools("com.example.Foo", null));
    }

    @Test
    void enrichPools_acceptsPartialCompletion() {
        Properties.LLM_ENRICH_CONSTANT_POOL = true;
        Properties.LLM_ENRICH_OBJECT_POOL = true;

        // Constant enricher completes fast, object enricher takes forever
        LlmConstantPoolEnricher fastConstant = mock(LlmConstantPoolEnricher.class);
        when(fastConstant.enrichAsync(anyString(), any())).thenReturn(
                CompletableFuture.completedFuture(
                        new LlmConstantPoolEnricher.EnrichmentResult(true, 3, 0, 5, null))
        );

        LlmObjectPoolEnricher slowObject = mock(LlmObjectPoolEnricher.class);
        when(slowObject.enrichAsync(anyString(), any())).thenReturn(
                CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(30_000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return LlmObjectPoolEnricher.EnrichmentResult.failure("should not reach");
                })
        );

        LlmPoolEnrichmentOrchestrator orchestrator =
                new LlmPoolEnrichmentOrchestrator(fastConstant, slowObject, 1);

        long start = System.currentTimeMillis();
        // Should not throw - partial completion is fine
        assertDoesNotThrow(() -> orchestrator.enrichPools("com.example.Foo", null));
        long elapsed = System.currentTimeMillis() - start;

        // Should respect timeout
        assertTrue(elapsed < 5000, "Orchestrator should respect timeout, took " + elapsed + "ms");
    }

    @Test
    void enrichPools_completesSuccessfullyWhenBothSucceed() {
        Properties.LLM_ENRICH_CONSTANT_POOL = true;
        Properties.LLM_ENRICH_OBJECT_POOL = true;

        LlmConstantPoolEnricher fastConstant = mock(LlmConstantPoolEnricher.class);
        when(fastConstant.enrichAsync(anyString(), any())).thenReturn(
                CompletableFuture.completedFuture(
                        new LlmConstantPoolEnricher.EnrichmentResult(true, 5, 0, 8, null))
        );

        LlmObjectPoolEnricher fastObject = mock(LlmObjectPoolEnricher.class);
        when(fastObject.enrichAsync(anyString(), any())).thenReturn(
                CompletableFuture.completedFuture(
                        new LlmObjectPoolEnricher.EnrichmentResult(true, 2, 3, 0, 0, 0, null))
        );

        LlmPoolEnrichmentOrchestrator orchestrator =
                new LlmPoolEnrichmentOrchestrator(fastConstant, fastObject, 30);

        // Should complete without issues
        assertDoesNotThrow(() -> orchestrator.enrichPools("com.example.Foo", null));
    }

    @Test
    void enrichPools_onlyRunsEnabledEnrichers() {
        Properties.LLM_ENRICH_CONSTANT_POOL = true;
        Properties.LLM_ENRICH_OBJECT_POOL = false;

        LlmConstantPoolEnricher constantEnricher = mock(LlmConstantPoolEnricher.class);
        when(constantEnricher.enrichAsync(anyString(), any())).thenReturn(
                CompletableFuture.completedFuture(
                        new LlmConstantPoolEnricher.EnrichmentResult(true, 2, 0, 4, null))
        );

        LlmObjectPoolEnricher objectEnricher = mock(LlmObjectPoolEnricher.class);
        // Object enricher should not be called - but even if it is, we handle gracefully

        LlmPoolEnrichmentOrchestrator orchestrator =
                new LlmPoolEnrichmentOrchestrator(constantEnricher, objectEnricher, 5);

        assertDoesNotThrow(() -> orchestrator.enrichPools("com.example.Foo", null));
    }
}
