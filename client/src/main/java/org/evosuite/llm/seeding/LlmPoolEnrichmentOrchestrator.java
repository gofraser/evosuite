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
import org.evosuite.llm.LlmService;
import org.evosuite.setup.TestCluster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates async constant pool, object pool, and cast class enrichment.
 * Ensures structural enrichment (cast classes) completes before search starts,
 * while allowing data enrichment (constants, objects) to trickle in during search.
 */
public class LlmPoolEnrichmentOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(LlmPoolEnrichmentOrchestrator.class);

    private final LlmConstantPoolEnricher constantPoolEnricher;
    private final LlmObjectPoolEnricher objectPoolEnricher;
    private final LlmCastClassEnricher castClassEnricher;
    private final int timeoutSeconds;

    private CompletableFuture<LlmConstantPoolEnricher.EnrichmentResult> constantFuture;
    private CompletableFuture<LlmObjectPoolEnricher.EnrichmentResult> objectFuture;
    private CompletableFuture<LlmCastClassEnricher.EnrichmentResult> castFuture;

    // Guards to ensure each enricher's metrics are logged/tracked exactly once
    private final AtomicBoolean constantResultTracked = new AtomicBoolean(false);
    private final AtomicBoolean objectResultTracked = new AtomicBoolean(false);
    private final AtomicBoolean castResultTracked = new AtomicBoolean(false);

    /** Constructs an orchestrator with explicit enricher instances and timeout setting. */
    public LlmPoolEnrichmentOrchestrator(LlmConstantPoolEnricher constantPoolEnricher,
                                         LlmObjectPoolEnricher objectPoolEnricher,
                                         LlmCastClassEnricher castClassEnricher,
                                         int timeoutSeconds) {
        this.constantPoolEnricher = constantPoolEnricher;
        this.objectPoolEnricher = objectPoolEnricher;
        this.castClassEnricher = castClassEnricher;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    /**
     * Creates an orchestrator from Properties-based configuration.
     */
    public static LlmPoolEnrichmentOrchestrator fromProperties(LlmService llmService) {
        LlmConstantPoolEnricher constantEnricher = new LlmConstantPoolEnricher(llmService);
        LlmObjectPoolEnricher objectEnricher = new LlmObjectPoolEnricher(llmService);
        LlmCastClassEnricher castEnricher = new LlmCastClassEnricher(llmService);
        return new LlmPoolEnrichmentOrchestrator(
                constantEnricher, objectEnricher, castEnricher, Properties.LLM_TIMEOUT_SECONDS);
    }

    /**
     * Starts all enabled enrichment tasks asynchronously.
     * Non-blocking. Registers whenComplete callbacks on data enrichment futures
     * to ensure metrics are tracked even for late completions.
     */
    public void startEnrichment(String className, TestCluster cluster) {
        boolean enrichConstants = Properties.LLM_ENRICH_CONSTANT_POOL;
        boolean enrichObjects = Properties.LLM_ENRICH_OBJECT_POOL;
        boolean enrichCastClasses = Properties.LLM_ENRICH_CAST_CLASSES;

        logger.info("LLM pool enrichment starting (constants={}, objects={}, castClasses={}, timeout={}s)",
                enrichConstants, enrichObjects, enrichCastClasses, timeoutSeconds);

        constantFuture = enrichConstants ? constantPoolEnricher.enrichAsync(className, cluster)
                : CompletableFuture.completedFuture(null);

        objectFuture = enrichObjects ? objectPoolEnricher.enrichAsync(className, cluster)
                : CompletableFuture.completedFuture(null);

        castFuture = enrichCastClasses ? castClassEnricher.enrichAsync(className, cluster)
                : CompletableFuture.completedFuture(null);

        // Register callbacks for late async completions — ensures metrics are
        // tracked exactly once even if enrichers finish after the structural gate.
        // We register one for the cast future too so its result is captured even
        // if finishStructuralEnrichment is never called by the caller.
        constantFuture.whenComplete((result, ex) -> {
            if (constantResultTracked.compareAndSet(false, true)) {
                handleResult("Constant pool (async)", result, ex);
            }
        });
        objectFuture.whenComplete((result, ex) -> {
            if (objectResultTracked.compareAndSet(false, true)) {
                handleResult("Object pool (async)", result, ex);
            }
        });
        castFuture.whenComplete((result, ex) -> {
            if (castResultTracked.compareAndSet(false, true)) {
                handleResult("Cast class (async)", result, ex);
            }
        });
    }

    /**
     * Waits for structural enrichment (cast classes) to complete or timeout.
     * On timeout, cancels the cast future to prevent late CastClassManager mutations.
     * Data enrichment (constants/objects) continues in the background — their results
     * are tracked via whenComplete callbacks registered in {@link #startEnrichment}.
     */
    public void finishStructuralEnrichment() {
        if (castFuture == null) {
            return;
        }

        try {
            // Block for cast classes (Structural Gate). The result is also
            // delivered to the whenComplete callback registered in startEnrichment,
            // so we don't log here — the callback's compareAndSet wins exactly once.
            castFuture.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.warn("LLM cast class enrichment timed out after {}s, cancelling and opening structural gate",
                    timeoutSeconds);
            // Cooperative cancel — sets the enricher's cancelled flag so it won't
            // mutate CastClassManager even if the underlying thread is still running.
            castClassEnricher.cancel();
            castFuture.cancel(true);
        } catch (CancellationException e) {
            // The whenComplete callback handles logging.
        } catch (ExecutionException e) {
            // Surfaced via whenComplete; nothing to do here.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Note background-running data enrichments for operator visibility.
        // Logging of their actual results is owned by the whenComplete callbacks.
        if (constantFuture != null && !constantFuture.isDone()) {
            logger.info("LLM constant pool enrichment continuing in background during search");
        }
        if (objectFuture != null && !objectFuture.isDone()) {
            logger.info("LLM object pool enrichment continuing in background during search");
        }
    }

    /**
     * Convenience method: starts enrichment then waits for the structural gate only.
     * Data enrichment (constants/objects) may still be running after this returns.
     * Primarily used for legacy call sites and tests that don't need full blocking.
     *
     * @see #startEnrichment(String, TestCluster)
     * @see #finishStructuralEnrichment()
     * @see #awaitAll(int)
     */
    public void enrichPools(String className, TestCluster cluster) {
        startEnrichment(className, cluster);
        finishStructuralEnrichment();
    }

    /**
     * Blocks until all enrichment futures (structural + data) complete or timeout.
     * Use this when the caller needs all enrichment to finish before proceeding.
     *
     * @param maxWaitSeconds maximum seconds to wait for all futures combined
     */
    public void awaitAll(int maxWaitSeconds) {
        long deadline = System.currentTimeMillis() + maxWaitSeconds * 1000L;
        awaitFuture(castFuture, deadline, castClassEnricher);
        awaitFuture(constantFuture, deadline, constantPoolEnricher);
        awaitFuture(objectFuture, deadline, objectPoolEnricher);
    }

    /**
     * Cooperatively cancels all enrichers and their futures. Idempotent and safe
     * to call from arbitrary threads. Use this at the end of search to ensure
     * background enrichment threads don't keep mutating shared pools after the
     * results have already been written out.
     */
    public void cancelAll() {
        cancelEnricher(constantFuture, constantPoolEnricher);
        cancelEnricher(objectFuture, objectPoolEnricher);
        cancelEnricher(castFuture, castClassEnricher);
    }

    private void cancelEnricher(CompletableFuture<?> future,
                                AbstractLlmEnricher<?> enricher) {
        if (enricher != null) {
            try {
                enricher.cancel();
            } catch (Throwable t) {
                logger.debug("Enricher cancel failed: {}", t.getMessage());
            }
        }
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private void awaitFuture(CompletableFuture<?> future, long deadline,
                             AbstractLlmEnricher<?> enricher) {
        if (future == null || future.isDone()) {
            return;
        }
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
            cancelEnricher(future, enricher);
            return;
        }
        try {
            future.get(remaining, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            cancelEnricher(future, enricher);
        } catch (Throwable e) {
            logger.debug("Enrichment future failed during awaitAll: {}", e.getMessage());
        }
    }

    /**
     * Checks whether any enrichment is enabled and the LLM is configured.
     */
    public static boolean isEnrichmentEnabled() {
        if (!Properties.LLM_ENRICH_CONSTANT_POOL && !Properties.LLM_ENRICH_OBJECT_POOL
                && !Properties.LLM_ENRICH_CAST_CLASSES) {
            return false;
        }
        return Properties.LLM_PROVIDER != Properties.LlmProvider.NONE;
    }

    private void handleResult(String label, Object result, Throwable ex) {
        if (ex instanceof CancellationException) {
            logger.info("{} enrichment: cancelled", label);
            return;
        }
        if (ex != null) {
            Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
            logger.warn("{} enrichment: failed: {}", label, cause.getMessage());
            return;
        }
        if (result == null) {
            logger.debug("{} enrichment: not attempted or skipped", label);
            return;
        }
        if (result instanceof AbstractLlmEnricher.EnrichmentResult) {
            AbstractLlmEnricher.EnrichmentResult er = (AbstractLlmEnricher.EnrichmentResult) result;
            logger.info(er.summarize(label));
            er.trackMetrics();
        } else {
            logger.debug("{} enrichment: unrecognised result type {}", label, result.getClass().getName());
        }
    }
}
