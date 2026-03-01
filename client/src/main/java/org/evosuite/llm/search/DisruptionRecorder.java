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
package org.evosuite.llm.search;

import org.evosuite.Properties;
import org.evosuite.llm.search.DisruptionEvent.OperatorKind;
import org.evosuite.llm.search.DisruptionEvent.OperatorOutcome;
import org.evosuite.llm.search.DisruptionEvent.OperatorSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Records operator disruption events to a sidecar CSV file when
 * {@link Properties#LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED} is {@code true}.
 *
 * <p>When disabled, all public methods are no-ops with zero allocation overhead
 * beyond the enabled-check branch.
 *
 * <p>Thread-safety: events are accumulated in a synchronized list and flushed
 * at end of run. Event index assignment is atomic for deterministic ordering.
 */
public class DisruptionRecorder {

    private static final Logger logger = LoggerFactory.getLogger(DisruptionRecorder.class);

    private static volatile DisruptionRecorder instance;

    private final List<DisruptionEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger eventCounter = new AtomicInteger(0);

    // Run-level counters by operator kind × source
    private final AtomicInteger standardMutations = new AtomicInteger(0);
    private final AtomicInteger semanticMutations = new AtomicInteger(0);
    private final AtomicInteger standardCrossovers = new AtomicInteger(0);
    private final AtomicInteger semanticCrossovers = new AtomicInteger(0);
    private final AtomicInteger semanticFallbacks = new AtomicInteger(0);

    private String sidecarPath;

    private DisruptionRecorder() {}

    public static synchronized DisruptionRecorder getInstance() {
        if (instance == null) {
            instance = new DisruptionRecorder();
        }
        return instance;
    }

    /** Reset state for a new run (or for testing). */
    public static synchronized void resetInstance() {
        instance = null;
    }

    /** Returns true if disruption analysis is enabled. */
    public static boolean isEnabled() {
        return Properties.LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED;
    }

    /** Returns true if isolated probes should be performed. */
    public static boolean isIsolatedProbeEnabled() {
        return isEnabled() && Properties.LLM_OPERATOR_DISRUPTION_EVALUATE_ISOLATED;
    }

    /**
     * Allocate a monotonically increasing event index.
     * Used by callers to ensure deterministic ordering under fixed seed.
     */
    public int nextEventIndex() {
        return eventCounter.getAndIncrement();
    }

    /** Record a disruption event. No-op if analysis is disabled. */
    public void record(DisruptionEvent event) {
        if (!isEnabled()) return;
        events.add(event);
        updateCounters(event);
    }

    private void updateCounters(DisruptionEvent event) {
        if (event.getOperatorKind() == OperatorKind.MUTATION) {
            if (event.getOperatorSource() == OperatorSource.SEMANTIC) {
                semanticMutations.incrementAndGet();
            } else {
                standardMutations.incrementAndGet();
            }
        } else if (event.getOperatorKind() == OperatorKind.CROSSOVER) {
            if (event.getOperatorSource() == OperatorSource.SEMANTIC) {
                semanticCrossovers.incrementAndGet();
            } else {
                standardCrossovers.incrementAndGet();
            }
        }
        if (event.getOutcome() == OperatorOutcome.FALLBACK
                && event.getOperatorSource() == OperatorSource.SEMANTIC) {
            semanticFallbacks.incrementAndGet();
        }
    }

    /** Flush all recorded events to the sidecar CSV file. */
    public void flush() {
        if (!isEnabled() || events.isEmpty()) return;

        sidecarPath = resolveSidecarPath();
        File dirFile = new File(sidecarPath).getParentFile();
        if (dirFile != null && !dirFile.exists()) {
            dirFile.mkdirs();
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(sidecarPath))) {
            writer.write(DisruptionEvent.CSV_HEADER);
            writer.newLine();
            // Sort by event index for deterministic output
            List<DisruptionEvent> sorted;
            synchronized (events) {
                sorted = new ArrayList<>(events);
            }
            sorted.sort((a, b) -> Integer.compare(a.getEventIndex(), b.getEventIndex()));
            for (DisruptionEvent event : sorted) {
                writer.write(event.toCsvRow());
                writer.newLine();
            }
            logger.info("Disruption analysis: wrote {} events to {}", sorted.size(), sidecarPath);
        } catch (IOException e) {
            logger.warn("Failed to write disruption sidecar CSV: {}", e.getMessage());
        }
    }

    /**
     * Deterministically resolve the sidecar file path from properties.
     * Can be called before flush to emit the path in statistics.
     */
    public String resolveSidecarPath() {
        String dir = Properties.LLM_OPERATOR_DISRUPTION_OUTPUT_DIR;
        if (dir == null || dir.isEmpty()) {
            dir = Properties.REPORT_DIR;
        }
        return dir + File.separator + "disruption_events.csv";
    }

    // ---- Run-level counter accessors ----

    public int getTotalEvents() { return events.size(); }
    public int getStandardMutations() { return standardMutations.get(); }
    public int getSemanticMutations() { return semanticMutations.get(); }
    public int getStandardCrossovers() { return standardCrossovers.get(); }
    public int getSemanticCrossovers() { return semanticCrossovers.get(); }
    public int getSemanticFallbacks() { return semanticFallbacks.get(); }
    public String getSidecarPath() { return sidecarPath; }

    /** Get a snapshot of all events (for testing). */
    public List<DisruptionEvent> getEvents() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }
}
