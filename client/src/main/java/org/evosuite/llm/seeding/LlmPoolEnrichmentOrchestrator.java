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

/**
 * Orchestrates async constant pool and object pool enrichment with bounded timeout.
 * Ensures enrichment never stalls startup; partial results are accepted.
 */
public class LlmPoolEnrichmentOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(LlmPoolEnrichmentOrchestrator.class);

    private final LlmConstantPoolEnricher constantPoolEnricher;
    private final LlmObjectPoolEnricher objectPoolEnricher;
    private final int timeoutSeconds;

    public LlmPoolEnrichmentOrchestrator(LlmConstantPoolEnricher constantPoolEnricher,
                                         LlmObjectPoolEnricher objectPoolEnricher,
                                         int timeoutSeconds) {
        this.constantPoolEnricher = constantPoolEnricher;
        this.objectPoolEnricher = objectPoolEnricher;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    /**
     * Creates an orchestrator from Properties-based configuration.
     */
    public static LlmPoolEnrichmentOrchestrator fromProperties(LlmService llmService) {
        LlmConstantPoolEnricher constantEnricher = new LlmConstantPoolEnricher(llmService);
        LlmObjectPoolEnricher objectEnricher = new LlmObjectPoolEnricher(llmService);
        return new LlmPoolEnrichmentOrchestrator(
                constantEnricher, objectEnricher, Properties.LLM_ENRICHMENT_TIMEOUT_SECONDS);
    }

    /**
     * Runs pool enrichment according to Properties flags.
     * Blocks for at most {@code timeoutSeconds}, accepting partial results.
     * Never throws; all failures are logged and swallowed.
     */
    public void enrichPools(String className, TestCluster cluster) {
        boolean enrichConstants = Properties.LLM_ENRICH_CONSTANT_POOL;
        boolean enrichObjects = Properties.LLM_ENRICH_OBJECT_POOL;

        if (!enrichConstants && !enrichObjects) {
            logger.debug("LLM pool enrichment: both constant and object enrichment disabled, skipping");
            return;
        }

        logger.info("LLM pool enrichment starting (constants={}, objects={}, timeout={}s)",
                enrichConstants, enrichObjects, timeoutSeconds);

        CompletableFuture<LlmConstantPoolEnricher.EnrichmentResult> constantFuture =
                enrichConstants ? constantPoolEnricher.enrichAsync(className, cluster)
                        : CompletableFuture.completedFuture(null);

        CompletableFuture<LlmObjectPoolEnricher.EnrichmentResult> objectFuture =
                enrichObjects ? objectPoolEnricher.enrichAsync(className, cluster)
                        : CompletableFuture.completedFuture(null);

        CompletableFuture<Void> combined = CompletableFuture.allOf(constantFuture, objectFuture);

        try {
            combined.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.warn("LLM pool enrichment timed out after {}s, proceeding with partial results", timeoutSeconds);
            cancelUnfinished(constantFuture, objectFuture);
        } catch (Throwable e) {
            logger.warn("LLM pool enrichment failed: {}", e.getMessage());
            cancelUnfinished(constantFuture, objectFuture);
        }

        logResult("Constant pool", constantFuture);
        logResult("Object pool", objectFuture);
    }

    /**
     * Checks whether any enrichment is enabled and the LLM is configured.
     */
    public static boolean isEnrichmentEnabled() {
        if (!Properties.LLM_ENRICH_CONSTANT_POOL && !Properties.LLM_ENRICH_OBJECT_POOL) {
            return false;
        }
        return Properties.LLM_PROVIDER != Properties.LlmProvider.NONE;
    }

    private void logResult(String label, CompletableFuture<?> future) {
        if (future.isCancelled()) {
            logger.info("{} enrichment: cancelled before completion", label);
            return;
        }
        if (!future.isDone()) {
            logger.info("{} enrichment: still running (timed out), partial results may be available", label);
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
            }
        } catch (Throwable e) {
            logger.debug("{} enrichment: completed with error: {}", label, e.getMessage());
        }
    }

    private void cancelUnfinished(CompletableFuture<?>... futures) {
        for (CompletableFuture<?> future : futures) {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
    }
}
