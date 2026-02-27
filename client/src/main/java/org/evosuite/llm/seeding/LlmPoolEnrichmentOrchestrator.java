package org.evosuite.llm.seeding;

import org.evosuite.Properties;
import org.evosuite.llm.LlmService;
import org.evosuite.rmi.ClientServices;
import org.evosuite.setup.TestCluster;
import org.evosuite.statistics.RuntimeVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
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
                constantEnricher, objectEnricher, castEnricher, Properties.LLM_ENRICHMENT_TIMEOUT_SECONDS);
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
        constantFuture.whenComplete((result, ex) -> {
            if (constantResultTracked.compareAndSet(false, true)) {
                logResult("Constant pool (async)", constantFuture);
            }
        });
        objectFuture.whenComplete((result, ex) -> {
            if (objectResultTracked.compareAndSet(false, true)) {
                logResult("Object pool (async)", objectFuture);
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
            // Block for cast classes (Structural Gate)
            castFuture.get(timeoutSeconds, TimeUnit.SECONDS);
            if (castResultTracked.compareAndSet(false, true)) {
                logResult("Cast class", castFuture);
            }
        } catch (TimeoutException e) {
            logger.warn("LLM cast class enrichment timed out after {}s, cancelling and opening structural gate",
                    timeoutSeconds);
            // Cooperative cancel — sets the enricher's cancelled flag so it won't
            // mutate CastClassManager even if the underlying thread is still running.
            castClassEnricher.cancel();
            castFuture.cancel(true);
        } catch (Throwable e) {
            logger.warn("LLM cast class enrichment failed: {}", e.getMessage());
        }

        // Data enrichments that are already done get logged here via the callback
        // guard. Those still running will self-report via whenComplete callbacks.
        if (constantFuture != null && constantFuture.isDone()) {
            // Callback may have already fired; compareAndSet prevents double logging
            if (constantResultTracked.compareAndSet(false, true)) {
                logResult("Constant pool", constantFuture);
            }
        } else if (constantFuture != null) {
            logger.info("LLM constant pool enrichment continuing in background during search");
        }

        if (objectFuture != null && objectFuture.isDone()) {
            if (objectResultTracked.compareAndSet(false, true)) {
                logResult("Object pool", objectFuture);
            }
        } else if (objectFuture != null) {
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
        awaitFuture(castFuture, deadline);
        awaitFuture(constantFuture, deadline);
        awaitFuture(objectFuture, deadline);
    }

    private void awaitFuture(CompletableFuture<?> future, long deadline) {
        if (future == null || future.isDone()) {
            return;
        }
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
            future.cancel(true);
            return;
        }
        try {
            future.get(remaining, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
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

    private void logResult(String label, CompletableFuture<?> future) {
        if (future == null) {
            return;
        }
        if (future.isCancelled()) {
            logger.info("{} enrichment: cancelled", label);
            return;
        }
        if (!future.isDone()) {
            return;
        }
        try {
            Object result = future.getNow(null);
            if (result == null) {
                logger.debug("{} enrichment: not attempted or skipped", label);
            } else if (result instanceof LlmConstantPoolEnricher.EnrichmentResult) {
                LlmConstantPoolEnricher.EnrichmentResult cr = (LlmConstantPoolEnricher.EnrichmentResult) result;
                logger.info("{} enrichment: attempted={}, parsed={}, sutAdded={}, nonSutAdded={}{}",
                        label, cr.isAttempted(), cr.getConstantsParsed(),
                        cr.getSutConstantsAdded(), cr.getNonSutConstantsAdded(),
                        cr.getFailureReason() != null ? ", failure=" + cr.getFailureReason() : "");
                try {
                    ClientServices.track(RuntimeVariable.LLM_Constants_Added_SUT, cr.getSutConstantsAdded());
                    ClientServices.track(RuntimeVariable.LLM_Constants_Added_NonSUT, cr.getNonSutConstantsAdded());
                } catch (Throwable t) {
                    logger.debug("Failed to track constant pool metrics: {}", t.getMessage());
                }
            } else if (result instanceof LlmObjectPoolEnricher.EnrichmentResult) {
                LlmObjectPoolEnricher.EnrichmentResult or = (LlmObjectPoolEnricher.EnrichmentResult) result;
                logger.info("{} enrichment: attempted={}, parsed={}, added={}{}",
                        label, or.isAttempted(), or.getSequencesParsed(), or.getSequencesAdded(),
                        or.getFailureReason() != null ? ", failure=" + or.getFailureReason() : "");
                try {
                    ClientServices.track(RuntimeVariable.LLM_Object_Pool_Sequences_Added, or.getSequencesAdded());
                } catch (Throwable t) {
                    logger.debug("Failed to track object pool metrics: {}", t.getMessage());
                }
            } else if (result instanceof LlmCastClassEnricher.EnrichmentResult) {
                LlmCastClassEnricher.EnrichmentResult lr = (LlmCastClassEnricher.EnrichmentResult) result;
                logger.info("{} enrichment: attempted={}, suggested={}, validated={}, added={}{}",
                        label, lr.isAttempted(), lr.getSuggested(), lr.getValidated(), lr.getAccepted(),
                        lr.getFailureReason() != null ? ", failure=" + lr.getFailureReason() : "");
                try {
                    ClientServices.track(RuntimeVariable.LLM_Cast_Class_Suggestions, lr.getSuggested());
                    ClientServices.track(RuntimeVariable.LLM_Cast_Class_Accepted, lr.getAccepted());
                } catch (Throwable t) {
                    logger.debug("Failed to track cast class metrics: {}", t.getMessage());
                }
            }
        } catch (Throwable e) {
            logger.debug("{} enrichment: completed with error: {}", label, e.getMessage());
        }
    }
}
