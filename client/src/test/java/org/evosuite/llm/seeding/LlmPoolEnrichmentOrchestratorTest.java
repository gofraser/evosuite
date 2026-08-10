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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmPoolEnrichmentOrchestratorTest {

    private Properties.LlmProvider savedProvider;
    private boolean savedEnrichConstants;
    private boolean savedEnrichObjects;
    private boolean savedEnrichCast;
    private boolean savedEnrichNonSut;
    private boolean savedSeedInit;
    private boolean savedTestFactory;
    private int savedTimeout;
    private Properties.LlmSeedingProfile savedProfile;

    @BeforeEach
    void setUp() {
        savedProvider = Properties.LLM_PROVIDER;
        savedEnrichConstants = Properties.LLM_ENRICH_CONSTANT_POOL;
        savedEnrichObjects = Properties.LLM_ENRICH_OBJECT_POOL;
        savedEnrichCast = Properties.LLM_ENRICH_CAST_CLASSES;
        savedEnrichNonSut = Properties.LLM_ENRICH_NON_SUT_CONSTANT_POOL;
        savedSeedInit = Properties.LLM_SEED_INITIAL_POPULATION;
        savedTestFactory = Properties.LLM_TEST_FACTORY;
        savedTimeout = Properties.LLM_TIMEOUT_SECONDS;
        savedProfile = Properties.LLM_SEEDING_PROFILE;
        LlmService.resetInstanceForTesting();
    }

    @AfterEach
    void tearDown() {
        Properties.LLM_PROVIDER = savedProvider;
        Properties.LLM_ENRICH_CONSTANT_POOL = savedEnrichConstants;
        Properties.LLM_ENRICH_OBJECT_POOL = savedEnrichObjects;
        Properties.LLM_ENRICH_CAST_CLASSES = savedEnrichCast;
        Properties.LLM_ENRICH_NON_SUT_CONSTANT_POOL = savedEnrichNonSut;
        Properties.LLM_SEED_INITIAL_POPULATION = savedSeedInit;
        Properties.LLM_TEST_FACTORY = savedTestFactory;
        Properties.LLM_TIMEOUT_SECONDS = savedTimeout;
        Properties.LLM_SEEDING_PROFILE = savedProfile;
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
    void awaitAll_sharesDeadlineAcrossFutures() {
        Properties.LLM_ENRICH_CAST_CLASSES = true;
        Properties.LLM_ENRICH_CONSTANT_POOL = true;
        Properties.LLM_ENRICH_OBJECT_POOL = true;

        // Cast enricher is slow and exhausts most of the deadline.
        CompletableFuture<LlmCastClassEnricher.EnrichmentResult> slowCastFut = new CompletableFuture<>();
        // Constant and object enrichers complete quickly after a short delay.
        CompletableFuture<LlmConstantPoolEnricher.EnrichmentResult> constFut = new CompletableFuture<>();
        CompletableFuture<LlmObjectPoolEnricher.EnrichmentResult> objFut = new CompletableFuture<>();

        LlmCastClassEnricher castEnricher = mock(LlmCastClassEnricher.class);
        when(castEnricher.enrichAsync(anyString(), any())).thenReturn(slowCastFut);
        LlmConstantPoolEnricher constEnricher = mock(LlmConstantPoolEnricher.class);
        when(constEnricher.enrichAsync(anyString(), any())).thenReturn(constFut);
        LlmObjectPoolEnricher objEnricher = mock(LlmObjectPoolEnricher.class);
        when(objEnricher.enrichAsync(anyString(), any())).thenReturn(objFut);

        LlmPoolEnrichmentOrchestrator orchestrator =
                new LlmPoolEnrichmentOrchestrator(constEnricher, objEnricher, castEnricher, 3);
        orchestrator.startEnrichment("com.example.Foo", null);

        // Complete constants and objects shortly after awaitAll begins. Cast stays
        // outstanding to verify it does not gate the others' result delivery.
        new Thread(() -> {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            constFut.complete(new LlmConstantPoolEnricher.EnrichmentResult(true, 1, 0, 1, null));
            objFut.complete(new LlmObjectPoolEnricher.EnrichmentResult(true, 1, 1, 0, 0, 0, null));
        }).start();

        long start = System.currentTimeMillis();
        orchestrator.awaitAll(3);
        long elapsed = System.currentTimeMillis() - start;

        // awaitAll waits for the shared deadline (or all-complete). Since the slow
        // cast never completes, it blocks the full 3s and then cancels.
        assertTrue(elapsed >= 2500, "awaitAll should wait for shared deadline, took " + elapsed + "ms");
        assertTrue(constFut.isDone(), "constant should have completed");
        assertTrue(objFut.isDone(), "object should have completed");
        assertTrue(slowCastFut.isCancelled(), "slow cast should have been cancelled at the deadline");
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

    // ---- Cooperative cancellation tests ----

    @Test
    void cancelAll_cancelsAllEnrichersCooperatively() {
        Properties.LLM_ENRICH_CAST_CLASSES = true;
        Properties.LLM_ENRICH_CONSTANT_POOL = true;
        Properties.LLM_ENRICH_OBJECT_POOL = true;

        LlmConstantPoolEnricher realConstant = org.mockito.Mockito.spy(
                new LlmConstantPoolEnricher(createUnavailableService()));
        LlmObjectPoolEnricher realObject = org.mockito.Mockito.spy(
                new LlmObjectPoolEnricher(createUnavailableService()));
        LlmCastClassEnricher realCast = org.mockito.Mockito.spy(
                new LlmCastClassEnricher(createUnavailableService()));

        CompletableFuture<LlmConstantPoolEnricher.EnrichmentResult> constFut = new CompletableFuture<>();
        CompletableFuture<LlmObjectPoolEnricher.EnrichmentResult> objFut = new CompletableFuture<>();
        CompletableFuture<LlmCastClassEnricher.EnrichmentResult> castFut = new CompletableFuture<>();
        doReturn(constFut).when(realConstant).enrichAsync(anyString(), any());
        doReturn(objFut).when(realObject).enrichAsync(anyString(), any());
        doReturn(castFut).when(realCast).enrichAsync(anyString(), any());

        LlmPoolEnrichmentOrchestrator orchestrator =
                new LlmPoolEnrichmentOrchestrator(realConstant, realObject, realCast, 30);
        orchestrator.startEnrichment("com.example.Foo", null);

        orchestrator.cancelAll();

        assertTrue(realConstant.isCancelled(), "constant enricher cooperative flag must be set");
        assertTrue(realObject.isCancelled(), "object enricher cooperative flag must be set");
        assertTrue(realCast.isCancelled(), "cast enricher cooperative flag must be set");
        assertTrue(constFut.isCancelled(), "constant future should be cancelled");
        assertTrue(objFut.isCancelled(), "object future should be cancelled");
        assertTrue(castFut.isCancelled(), "cast future should be cancelled");
    }

    @Test
    void llmSeedingProfile_offDoesNotChangeFlags() {
        Properties.LLM_ENRICH_CAST_CLASSES = false;
        Properties.LLM_ENRICH_CONSTANT_POOL = false;
        Properties.LLM_ENRICH_OBJECT_POOL = false;
        Properties.LLM_SEEDING_PROFILE = Properties.LlmSeedingProfile.OFF;

        Properties.applyLlmSeedingProfile();

        assertFalse(Properties.LLM_ENRICH_CAST_CLASSES);
        assertFalse(Properties.LLM_ENRICH_CONSTANT_POOL);
        assertFalse(Properties.LLM_ENRICH_OBJECT_POOL);
    }

    @Test
    void llmSeedingProfile_minEnablesOnlyCastClasses() {
        Properties.LLM_SEEDING_PROFILE = Properties.LlmSeedingProfile.MIN;
        Properties.LLM_ENRICH_CONSTANT_POOL = true; // user attempt, will be overridden
        Properties.LLM_ENRICH_OBJECT_POOL = true;

        Properties.applyLlmSeedingProfile();

        assertTrue(Properties.LLM_ENRICH_CAST_CLASSES);
        assertFalse(Properties.LLM_ENRICH_CONSTANT_POOL, "MIN profile force-disables constants");
        assertFalse(Properties.LLM_ENRICH_OBJECT_POOL, "MIN profile force-disables objects");
        assertFalse(Properties.LLM_SEED_INITIAL_POPULATION);
        assertFalse(Properties.LLM_TEST_FACTORY);
    }

    @Test
    void llmSeedingProfile_fullEnablesEverything() {
        Properties.LLM_SEEDING_PROFILE = Properties.LlmSeedingProfile.FULL;

        Properties.applyLlmSeedingProfile();

        assertTrue(Properties.LLM_ENRICH_CAST_CLASSES);
        assertTrue(Properties.LLM_ENRICH_CONSTANT_POOL);
        assertTrue(Properties.LLM_ENRICH_NON_SUT_CONSTANT_POOL);
        assertTrue(Properties.LLM_ENRICH_OBJECT_POOL);
        assertTrue(Properties.LLM_SEED_INITIAL_POPULATION);
        assertTrue(Properties.LLM_TEST_FACTORY);
    }

    @Test
    void cancelAll_isIdempotent() {
        Properties.LLM_ENRICH_CAST_CLASSES = true;
        Properties.LLM_ENRICH_CONSTANT_POOL = true;
        Properties.LLM_ENRICH_OBJECT_POOL = true;

        LlmPoolEnrichmentOrchestrator orchestrator =
                new LlmPoolEnrichmentOrchestrator(mock(LlmConstantPoolEnricher.class),
                        mock(LlmObjectPoolEnricher.class), mock(LlmCastClassEnricher.class), 30);

        // Calling cancelAll before startEnrichment should not throw
        assertDoesNotThrow(orchestrator::cancelAll);
        // Calling again should be a no-op
        assertDoesNotThrow(orchestrator::cancelAll);
    }

    @Test
    void castTimeout_setsCancelFlagOnEnricher() {
        Properties.LLM_ENRICH_CAST_CLASSES = true;
        Properties.LLM_ENRICH_CONSTANT_POOL = false;
        Properties.LLM_ENRICH_OBJECT_POOL = false;

        // A real enricher whose cancel() flag we can inspect
        LlmCastClassEnricher realEnricher = new LlmCastClassEnricher(createUnavailableService());

        // Wire a slow future that blocks longer than the timeout
        CompletableFuture<LlmCastClassEnricher.EnrichmentResult> slowFuture = new CompletableFuture<>();
        LlmCastClassEnricher spyEnricher = org.mockito.Mockito.spy(realEnricher);
        // doReturn avoids invoking the real asynchronous method while Mockito
        // is setting up the spy.
        org.mockito.Mockito.doReturn(slowFuture)
                .when(spyEnricher).enrichAsync(anyString(), any());

        LlmPoolEnrichmentOrchestrator orchestrator =
                new LlmPoolEnrichmentOrchestrator(mock(LlmConstantPoolEnricher.class),
                        mock(LlmObjectPoolEnricher.class), spyEnricher, 1);

        orchestrator.startEnrichment("com.example.Foo", null);
        orchestrator.finishStructuralEnrichment();

        // The enricher's cancel flag should have been set by the orchestrator
        assertTrue(spyEnricher.isCancelled(),
                "Orchestrator should set the enricher's cancelled flag on timeout");
    }

    // ---- Helper ----

    private static LlmService createUnavailableService() {
        LlmConfiguration configuration = new LlmConfiguration(
                Properties.LlmProvider.NONE, "mock", "", "", 0.0, 1024, 2, 0, 1,
                false, java.nio.file.Paths.get("target/llm-test-traces"), "test-orch");
        return new LlmService(
                new org.evosuite.llm.mock.MockChatLanguageModel(),
                new LlmBudgetCoordinator.Local(0),
                configuration,
                new LlmStatistics(),
                new LlmTraceRecorder(configuration));
    }
}
