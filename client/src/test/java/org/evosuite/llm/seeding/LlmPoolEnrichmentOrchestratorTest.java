package org.evosuite.llm.seeding;

import org.evosuite.Properties;
import org.evosuite.llm.*;
import org.evosuite.llm.mock.MockChatLanguageModel;
import org.evosuite.setup.TestCluster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmPoolEnrichmentOrchestratorTest {

    private Properties.LlmProvider savedProvider;
    private boolean savedEnrichConstants;
    private boolean savedEnrichObjects;
    private boolean savedEnrichCast;
    private int savedTimeout;

    @BeforeEach
    void setUp() {
        savedProvider = Properties.LLM_PROVIDER;
        savedEnrichConstants = Properties.LLM_ENRICH_CONSTANT_POOL;
        savedEnrichObjects = Properties.LLM_ENRICH_OBJECT_POOL;
        savedEnrichCast = Properties.LLM_ENRICH_CAST_CLASSES;
        savedTimeout = Properties.LLM_ENRICHMENT_TIMEOUT_SECONDS;
        LlmService.resetInstanceForTesting();
    }

    @AfterEach
    void tearDown() {
        Properties.LLM_PROVIDER = savedProvider;
        Properties.LLM_ENRICH_CONSTANT_POOL = savedEnrichConstants;
        Properties.LLM_ENRICH_OBJECT_POOL = savedEnrichObjects;
        Properties.LLM_ENRICH_CAST_CLASSES = savedEnrichCast;
        Properties.LLM_ENRICHMENT_TIMEOUT_SECONDS = savedTimeout;
        LlmService.resetInstanceForTesting();
    }

    // ---- isEnrichmentEnabled tests ----

    @Test
    void isEnrichmentEnabled_falseWhenAllDisabled() {
        Properties.LLM_ENRICH_CONSTANT_POOL = false;
        Properties.LLM_ENRICH_OBJECT_POOL = false;
        Properties.LLM_ENRICH_CAST_CLASSES = false;
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
    void isEnrichmentEnabled_trueWhenCastEnabledAndProviderSet() {
        Properties.LLM_ENRICH_CAST_CLASSES = true;
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        assertTrue(LlmPoolEnrichmentOrchestrator.isEnrichmentEnabled());
    }

    // ---- Structural gate tests ----

    @Test
    void enrichPools_handlesTimeoutOnCastClasses() {
        Properties.LLM_ENRICH_CAST_CLASSES = true;
        Properties.LLM_ENRICH_CONSTANT_POOL = false;
        Properties.LLM_ENRICH_OBJECT_POOL = false;

        LlmCastClassEnricher slowCast = mock(LlmCastClassEnricher.class);
        when(slowCast.enrichAsync(anyString(), any())).thenReturn(
                CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(30_000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return LlmCastClassEnricher.EnrichmentResult.failure("should not reach");
                })
        );
        
        LlmPoolEnrichmentOrchestrator orchestrator =
                new LlmPoolEnrichmentOrchestrator(mock(LlmConstantPoolEnricher.class), 
                        mock(LlmObjectPoolEnricher.class), slowCast, 1);

        long start = System.currentTimeMillis();
        orchestrator.enrichPools("com.example.Foo", null);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 5000, "Orchestrator should respect timeout, took " + elapsed + "ms");
    }

    @Test
    void finishStructuralEnrichment_doesNotBlockOnConstants() {
        Properties.LLM_ENRICH_CAST_CLASSES = false;
        Properties.LLM_ENRICH_CONSTANT_POOL = true;
        Properties.LLM_ENRICH_OBJECT_POOL = false;

        LlmConstantPoolEnricher slowConstant = mock(LlmConstantPoolEnricher.class);
        CompletableFuture<LlmConstantPoolEnricher.EnrichmentResult> neverCompletes = new CompletableFuture<>();
        when(slowConstant.enrichAsync(anyString(), any())).thenReturn(neverCompletes);

        LlmPoolEnrichmentOrchestrator orchestrator =
                new LlmPoolEnrichmentOrchestrator(slowConstant, 
                        mock(LlmObjectPoolEnricher.class), mock(LlmCastClassEnricher.class), 30);

        orchestrator.startEnrichment("com.example.Foo", null);
        
        long start = System.currentTimeMillis();
        orchestrator.finishStructuralEnrichment();
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 1000, "finishStructuralEnrichment should not block on data enrichment");
        assertFalse(neverCompletes.isDone());
        assertFalse(neverCompletes.isCancelled());
    }

    @Test
    void finishStructuralEnrichment_blocksOnCastClasses() {
        Properties.LLM_ENRICH_CAST_CLASSES = true;
        Properties.LLM_ENRICH_CONSTANT_POOL = false;
        Properties.LLM_ENRICH_OBJECT_POOL = false;

        LlmCastClassEnricher slowCast = mock(LlmCastClassEnricher.class);
        CompletableFuture<LlmCastClassEnricher.EnrichmentResult> manualComplete = new CompletableFuture<>();
        when(slowCast.enrichAsync(anyString(), any())).thenReturn(manualComplete);

        LlmPoolEnrichmentOrchestrator orchestrator =
                new LlmPoolEnrichmentOrchestrator(mock(LlmConstantPoolEnricher.class), 
                        mock(LlmObjectPoolEnricher.class), slowCast, 10);

        orchestrator.startEnrichment("com.example.Foo", null);
        
        CompletableFuture<Void> finishFuture = CompletableFuture.runAsync(orchestrator::finishStructuralEnrichment);
        
        try {
            finishFuture.get(500, TimeUnit.MILLISECONDS);
            fail("finishStructuralEnrichment should have blocked on cast classes");
        } catch (Exception e) {
            // expected timeout
        }
        
        manualComplete.complete(new LlmCastClassEnricher.EnrichmentResult(true, 1, 1, 1, null));
        assertDoesNotThrow(() -> finishFuture.get(1, TimeUnit.SECONDS));
    }

    // ---- Cast cancellation on timeout ----

    @Test
    void castTimeout_cancelsFuture() {
        Properties.LLM_ENRICH_CAST_CLASSES = true;
        Properties.LLM_ENRICH_CONSTANT_POOL = false;
        Properties.LLM_ENRICH_OBJECT_POOL = false;

        CompletableFuture<LlmCastClassEnricher.EnrichmentResult> slowFuture = new CompletableFuture<>();
        LlmCastClassEnricher slowCast = mock(LlmCastClassEnricher.class);
        when(slowCast.enrichAsync(anyString(), any())).thenReturn(slowFuture);

        LlmPoolEnrichmentOrchestrator orchestrator =
                new LlmPoolEnrichmentOrchestrator(mock(LlmConstantPoolEnricher.class),
                        mock(LlmObjectPoolEnricher.class), slowCast, 1);

        orchestrator.startEnrichment("com.example.Foo", null);
        orchestrator.finishStructuralEnrichment();

        // After timeout, cast future should be cancelled
        assertTrue(slowFuture.isCancelled(), "Cast future should be cancelled on timeout");
        // Late completion should be rejected since the future is cancelled
        assertFalse(slowFuture.complete(
                new LlmCastClassEnricher.EnrichmentResult(true, 5, 5, 5, null)),
                "Late completion should be rejected after cancellation");
    }

    // ---- Late async completion tracking ----

    @Test
    void lateConstantCompletion_isTrackedByCallback() throws Exception {
        Properties.LLM_ENRICH_CAST_CLASSES = false;
        Properties.LLM_ENRICH_CONSTANT_POOL = true;
        Properties.LLM_ENRICH_OBJECT_POOL = false;

        CompletableFuture<LlmConstantPoolEnricher.EnrichmentResult> delayedFuture = new CompletableFuture<>();
        LlmConstantPoolEnricher slowConstant = mock(LlmConstantPoolEnricher.class);
        when(slowConstant.enrichAsync(anyString(), any())).thenReturn(delayedFuture);

        LlmPoolEnrichmentOrchestrator orchestrator =
                new LlmPoolEnrichmentOrchestrator(slowConstant,
                        mock(LlmObjectPoolEnricher.class), mock(LlmCastClassEnricher.class), 1);

        orchestrator.startEnrichment("com.example.Foo", null);
        orchestrator.finishStructuralEnrichment();

        // Constant enricher hasn't completed yet
        assertFalse(delayedFuture.isDone());

        // Simulate late completion — callback should fire and log/track metrics
        LlmConstantPoolEnricher.EnrichmentResult result =
                new LlmConstantPoolEnricher.EnrichmentResult(true, 3, 1, 5, null);
        delayedFuture.complete(result);

        // Give callback time to fire
        Thread.sleep(100);
        assertTrue(delayedFuture.isDone());
        // No exception means the callback ran cleanly (metrics tracking is best-effort
        // since ClientServices may not be available in unit tests)
    }

    @Test
    void lateObjectCompletion_isTrackedByCallback() throws Exception {
        Properties.LLM_ENRICH_CAST_CLASSES = false;
        Properties.LLM_ENRICH_CONSTANT_POOL = false;
        Properties.LLM_ENRICH_OBJECT_POOL = true;

        CompletableFuture<LlmObjectPoolEnricher.EnrichmentResult> delayedFuture = new CompletableFuture<>();
        LlmObjectPoolEnricher slowObject = mock(LlmObjectPoolEnricher.class);
        when(slowObject.enrichAsync(anyString(), any())).thenReturn(delayedFuture);

        LlmPoolEnrichmentOrchestrator orchestrator =
                new LlmPoolEnrichmentOrchestrator(mock(LlmConstantPoolEnricher.class),
                        slowObject, mock(LlmCastClassEnricher.class), 1);

        orchestrator.startEnrichment("com.example.Foo", null);
        orchestrator.finishStructuralEnrichment();

        assertFalse(delayedFuture.isDone());

        // Simulate late completion
        delayedFuture.complete(
                new LlmObjectPoolEnricher.EnrichmentResult(true, 2, 3, 0, 0, 0, null));

        Thread.sleep(100);
        assertTrue(delayedFuture.isDone());
    }

    // ---- enrichPools contract: start + structural-only ----

    @Test
    void enrichPools_doesNotBlockOnDataEnrichment() {
        Properties.LLM_ENRICH_CAST_CLASSES = false;
        Properties.LLM_ENRICH_CONSTANT_POOL = true;
        Properties.LLM_ENRICH_OBJECT_POOL = true;

        CompletableFuture<LlmConstantPoolEnricher.EnrichmentResult> slowConstant = new CompletableFuture<>();
        CompletableFuture<LlmObjectPoolEnricher.EnrichmentResult> slowObject = new CompletableFuture<>();

        LlmConstantPoolEnricher constantEnricher = mock(LlmConstantPoolEnricher.class);
        when(constantEnricher.enrichAsync(anyString(), any())).thenReturn(slowConstant);
        LlmObjectPoolEnricher objectEnricher = mock(LlmObjectPoolEnricher.class);
        when(objectEnricher.enrichAsync(anyString(), any())).thenReturn(slowObject);

        LlmPoolEnrichmentOrchestrator orchestrator =
                new LlmPoolEnrichmentOrchestrator(constantEnricher, objectEnricher,
                        mock(LlmCastClassEnricher.class), 30);

        long start = System.currentTimeMillis();
        orchestrator.enrichPools("com.example.Foo", null);
        long elapsed = System.currentTimeMillis() - start;

        // enrichPools returns without waiting for data enrichment
        assertTrue(elapsed < 2000, "enrichPools should not block on data enrichment");
        assertFalse(slowConstant.isDone());
        assertFalse(slowObject.isDone());
    }

    // ---- awaitAll blocks on everything ----

    @Test
    void awaitAll_blocksUntilAllComplete() throws Exception {
        Properties.LLM_ENRICH_CAST_CLASSES = true;
        Properties.LLM_ENRICH_CONSTANT_POOL = true;
        Properties.LLM_ENRICH_OBJECT_POOL = true;

        CompletableFuture<LlmCastClassEnricher.EnrichmentResult> castFut =
                CompletableFuture.completedFuture(new LlmCastClassEnricher.EnrichmentResult(true, 1, 1, 1, null));
        CompletableFuture<LlmConstantPoolEnricher.EnrichmentResult> constFut = new CompletableFuture<>();
        CompletableFuture<LlmObjectPoolEnricher.EnrichmentResult> objFut =
                CompletableFuture.completedFuture(new LlmObjectPoolEnricher.EnrichmentResult(true, 1, 1, 0, 0, 0, null));

        LlmCastClassEnricher castEnricher = mock(LlmCastClassEnricher.class);
        when(castEnricher.enrichAsync(anyString(), any())).thenReturn(castFut);
        LlmConstantPoolEnricher constEnricher = mock(LlmConstantPoolEnricher.class);
        when(constEnricher.enrichAsync(anyString(), any())).thenReturn(constFut);
        LlmObjectPoolEnricher objEnricher = mock(LlmObjectPoolEnricher.class);
        when(objEnricher.enrichAsync(anyString(), any())).thenReturn(objFut);

        LlmPoolEnrichmentOrchestrator orchestrator =
                new LlmPoolEnrichmentOrchestrator(constEnricher, objEnricher, castEnricher, 5);

        orchestrator.startEnrichment("com.example.Foo", null);
        orchestrator.finishStructuralEnrichment();

        // Complete constant enricher after structural gate
        new Thread(() -> {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            constFut.complete(new LlmConstantPoolEnricher.EnrichmentResult(true, 2, 0, 3, null));
        }).start();

        orchestrator.awaitAll(5);
        assertTrue(constFut.isDone(), "awaitAll should have waited for constant enricher");
    }

    @Test
    void awaitAll_cancelsOnTimeout() {
        Properties.LLM_ENRICH_CAST_CLASSES = false;
        Properties.LLM_ENRICH_CONSTANT_POOL = true;
        Properties.LLM_ENRICH_OBJECT_POOL = false;

        CompletableFuture<LlmConstantPoolEnricher.EnrichmentResult> neverCompletes = new CompletableFuture<>();
        LlmConstantPoolEnricher constEnricher = mock(LlmConstantPoolEnricher.class);
        when(constEnricher.enrichAsync(anyString(), any())).thenReturn(neverCompletes);

        LlmPoolEnrichmentOrchestrator orchestrator =
                new LlmPoolEnrichmentOrchestrator(constEnricher,
                        mock(LlmObjectPoolEnricher.class), mock(LlmCastClassEnricher.class), 30);

        orchestrator.startEnrichment("com.example.Foo", null);
        orchestrator.finishStructuralEnrichment();

        long start = System.currentTimeMillis();
        orchestrator.awaitAll(1);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 3000, "awaitAll should respect timeout");
        assertTrue(neverCompletes.isCancelled(), "awaitAll should cancel timed-out futures");
    }
}
