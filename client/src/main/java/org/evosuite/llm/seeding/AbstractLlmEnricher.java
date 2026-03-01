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

import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmService;
import org.evosuite.setup.TestCluster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base class for LLM-based test cluster and pool enrichers.
 * Standardizes async execution, budget checks, and error handling.
 *
 * <p>Uses a dedicated daemon-thread executor (not ForkJoinPool.commonPool())
 * so that blocking LLM I/O does not starve CPU-bound work, and
 * {@link ExecutorService#shutdownNow()} delivers real thread interruption
 * when the orchestrator cancels enrichment on timeout.
 */
public abstract class AbstractLlmEnricher<R extends AbstractLlmEnricher.EnrichmentResult> {

    /** Shared executor for all enrichment async work. Daemon threads so JVM exit is not blocked. */
    static final ExecutorService LLM_ENRICHMENT_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "llm-enrichment");
        t.setDaemon(true);
        return t;
    });

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(LLM_ENRICHMENT_EXECUTOR::shutdownNow,
                "llm-enrichment-shutdown"));
    }

    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected final LlmService llmService;
    protected final LlmFeature feature;

    /**
     * Cooperative cancellation flag. Checked by {@link #isCancelled()} and set by
     * the orchestrator via {@link #cancel()} when the enrichment deadline expires.
     */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

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
                if (isCancelled()) {
                    return createFailureResult("Cancelled before start");
                }
                return doEnrich(className, cluster);
            } catch (Throwable t) {
                logger.warn("Enrichment failed for {}: {}", feature, t.getMessage());
                return createFailureResult(t.getMessage());
            }
        }, LLM_ENRICHMENT_EXECUTOR);
    }

    /**
     * Signals this enricher to stop as soon as possible.
     * Subclass loops should poll {@link #isCancelled()}.
     */
    public void cancel() {
        cancelled.set(true);
    }

    /**
     * Returns true if cancellation has been requested.
     * Subclasses should check this in long-running loops and before pool mutations.
     */
    protected boolean isCancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    /**
     * Core enrichment logic to be implemented by subclasses.
     * Implementation should check {@link #isCancelled()} for long loops
     * and before mutating shared pools.
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
