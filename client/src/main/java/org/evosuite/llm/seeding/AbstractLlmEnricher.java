package org.evosuite.llm.seeding;

import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmService;
import org.evosuite.setup.TestCluster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Base class for LLM-based test cluster and pool enrichers.
 * Standardizes async execution, budget checks, and error handling.
 */
public abstract class AbstractLlmEnricher<R extends AbstractLlmEnricher.EnrichmentResult> {

    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected final LlmService llmService;
    protected final LlmFeature feature;

    protected AbstractLlmEnricher(LlmService llmService, LlmFeature feature) {
        this.llmService = llmService;
        this.feature = feature;
    }

    /**
     * Asynchronously runs the enrichment logic if the service is available and has budget.
     */
    public CompletableFuture<R> enrichAsync(String className, TestCluster cluster) {
        return CompletableFuture.supplyAsync(() -> {
            if (!llmService.isAvailable() || !llmService.hasBudget()) {
                logger.debug("LLM not available or no budget for {}", feature);
                return createSkippedResult("LLM unavailable or no budget");
            }
            try {
                if (Thread.currentThread().isInterrupted()) {
                    return createFailureResult("Interrupted before start");
                }
                return doEnrich(className, cluster);
            } catch (Throwable t) {
                logger.warn("Enrichment failed for {}: {}", feature, t.getMessage());
                return createFailureResult(t.getMessage());
            }
        });
    }

    /**
     * Core enrichment logic to be implemented by subclasses.
     * Implementation should check Thread.currentThread().isInterrupted() for long loops.
     */
    protected abstract R doEnrich(String className, TestCluster cluster);

    protected abstract R createSkippedResult(String reason);

    protected abstract R createFailureResult(String reason);

    /**
     * Base class for enrichment results.
     */
    public abstract static class EnrichmentResult {
        private final boolean attempted;
        private final String failureReason;

        protected EnrichmentResult(boolean attempted, String failureReason) {
            this.attempted = attempted;
            this.failureReason = failureReason;
        }

        public boolean isAttempted() {
            return attempted;
        }

        public String getFailureReason() {
            return failureReason;
        }

        public boolean isSuccess() {
            return attempted && failureReason == null;
        }
    }
}
