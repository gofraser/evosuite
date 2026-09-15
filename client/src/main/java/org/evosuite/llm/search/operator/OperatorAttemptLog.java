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
package org.evosuite.llm.search.operator;

import org.evosuite.Properties;
import org.evosuite.llm.response.TargetedResponse;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * One CSV row per LLM operator target, in both {@code SYNC} and {@code ASYNC}
 * mode: {@link #open} registers a target as it is prompted, {@link
 * #recordResponse}/{@link #recordUndelivered} record what the call produced,
 * and {@link #recordEvaluated}/{@link #recordAdmitted}/{@link
 * #recordSurvivors} record what the search did with it afterwards.
 *
 * <p>Rows are always kept in memory — they are the source of the {@code
 * LLM_Operator_*} per-target runtime variables — bounded by {@code
 * llm_max_calls * llm_operator_batch_size}. The sidecar CSV itself is only
 * written by {@link #flush()} when {@link
 * Properties#LLM_OPERATOR_ATTEMPT_LOG_ENABLED} is set.
 *
 * <p>Singleton like {@code ProblemCardLogRecorder}. Every public method is
 * synchronized: {@code recordResponse} is called from worker threads in
 * {@code ASYNC} mode, everything else from the search thread.
 */
public final class OperatorAttemptLog {

    private static final Logger logger = LoggerFactory.getLogger(OperatorAttemptLog.class);

    private static OperatorAttemptLog instance;

    private final LinkedHashMap<String, OperatorAttemptRecord> records = new LinkedHashMap<>();

    private OperatorAttemptLog() {
    }

    public static synchronized OperatorAttemptLog getInstance() {
        if (instance == null) {
            instance = new OperatorAttemptLog();
        }
        return instance;
    }

    /** Reset state for a new run (or for testing). */
    public static synchronized void resetInstance() {
        instance = null;
    }

    /** Returns true when the sidecar CSV should be written. */
    public static boolean isSidecarEnabled() {
        return Properties.LLM_OPERATOR_ATTEMPT_LOG_ENABLED;
    }

    /** Deterministically resolves the sidecar file path from properties. */
    public static String resolveSidecarPath() {
        String tc = Properties.TARGET_CLASS;
        String suffix = (tc == null || tc.isEmpty()) ? "" : "_" + sanitize(tc);
        return Properties.REPORT_DIR + File.separator + "operator_attempts" + suffix + ".csv";
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_.$-]", "_");
    }

    /** Registers one operator target as it is prompted. No-op for a null/empty or duplicate attempt id. */
    public synchronized void open(String attemptId, int generation, OperatorTarget target, String mode,
                                  int batchSize) {
        if (attemptId == null || attemptId.isEmpty() || records.containsKey(attemptId)) {
            return;
        }
        records.put(attemptId, new OperatorAttemptRecord(attemptId, generation, target, mode, batchSize));
    }

    /** Records what the call behind {@code attemptId} produced for this target. No-op if never opened. */
    public synchronized void recordResponse(String attemptId, TargetedResponse.Status status,
                                            boolean parseSucceeded, boolean targetMatched, int repairTurns,
                                            TargetedResponse.CallUsage usage, TestCase offspringOrNull) {
        OperatorAttemptRecord record = records.get(attemptId);
        if (record != null) {
            record.recordResponse(status, parseSucceeded, targetMatched, repairTurns, usage, offspringOrNull);
        }
    }

    /** Records that this attempt was released without ever getting a response (shutdown/pool rejection). */
    public synchronized void recordUndelivered(String attemptId) {
        OperatorAttemptRecord record = records.get(attemptId);
        if (record != null) {
            record.recordUndelivered();
        }
    }

    /** Records the post-evaluation distance to the target goal and whether it was hit. */
    public synchronized void recordEvaluated(String attemptId, double distanceAfter, boolean targetHit) {
        OperatorAttemptRecord record = records.get(attemptId);
        if (record != null) {
            record.recordEvaluated(distanceAfter, targetHit);
        }
    }

    /** Records that this attempt's offspring entered the union/offspring set of {@code generation}. */
    public synchronized void recordAdmitted(String attemptId, TestChromosome offspring, int generation) {
        OperatorAttemptRecord record = records.get(attemptId);
        if (record != null) {
            record.recordAdmitted(offspring, generation);
        }
    }

    /**
     * Scans {@code population} once per generation, after environmental
     * selection, for admitted offspring still present by identity: sets
     * {@code survived_plus1} on records admitted in this generation and
     * {@code survived_plus5} on records admitted four generations ago (the
     * offspring then sits in the population that breeds admission + 1 / + 5).
     */
    public synchronized void recordSurvivors(Collection<TestChromosome> population, int generation) {
        Set<TestChromosome> presentByIdentity = Collections.newSetFromMap(new IdentityHashMap<>());
        if (population != null) {
            presentByIdentity.addAll(population);
        }
        for (OperatorAttemptRecord record : records.values()) {
            record.applySurvivorScan(presentByIdentity, generation);
        }
    }

    /** The row behind {@code attemptId}, or {@code null}; package-visible for tests. */
    synchronized OperatorAttemptRecord recordFor(String attemptId) {
        return records.get(attemptId);
    }

    public synchronized long getAttempts() {
        return records.size();
    }

    public synchronized long getAttempts(OperatorTarget.Kind kind) {
        long count = 0L;
        for (OperatorAttemptRecord record : records.values()) {
            if (record.getKind() == kind) {
                count++;
            }
        }
        return count;
    }

    public synchronized long getOffspringParsed() {
        return count(OperatorAttemptRecord::isParsed);
    }

    public synchronized long getOffspringAdmitted() {
        return count(OperatorAttemptRecord::isAdmitted);
    }

    public synchronized long getOffspringSurvived() {
        return count(OperatorAttemptRecord::isSurvivedPlus1);
    }

    public synchronized long getTargetHits() {
        return count(OperatorAttemptRecord::isTargetHit);
    }

    public synchronized long getTargetImproved() {
        return count(OperatorAttemptRecord::isTargetImproved);
    }

    private long count(java.util.function.Predicate<OperatorAttemptRecord> predicate) {
        long count = 0L;
        for (OperatorAttemptRecord record : records.values()) {
            if (predicate.test(record)) {
                count++;
            }
        }
        return count;
    }

    /** Writes every row to the sidecar CSV. No-op unless enabled and non-empty; never throws. */
    public synchronized void flush() {
        if (!isSidecarEnabled() || records.isEmpty()) {
            return;
        }
        String path = resolveSidecarPath();
        File file = new File(path);
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            logger.warn("Could not create directory {} for {}", dir, file.getName());
            return;
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(OperatorAttemptRecord.CSV_HEADER);
            writer.newLine();
            for (OperatorAttemptRecord record : records.values()) {
                writer.write(record.toCsvRow());
                writer.newLine();
            }
            logger.info("Operator attempt log: wrote {} rows to {}", records.size(), path);
        } catch (IOException e) {
            logger.warn("Failed to write operator attempt log: {}", e.getMessage());
        }
    }
}
