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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One CSV row per LLM operator target, in both {@code SYNC} and {@code ASYNC}
 * mode: {@link #open} registers a target as it is prompted, {@link
 * #recordResponse}/{@link #recordUndelivered}/{@link #recordDropped} record
 * what the call produced (or that it never got the chance to), and {@link
 * #recordEvaluated}/{@link #recordAdmitted}/{@link
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
 * {@code ASYNC} mode, everything else from the search thread. Every public
 * method is also best-effort: telemetry must never break the search it is
 * observing, so a {@link RuntimeException} anywhere in a method body is
 * caught and logged rather than propagated.
 */
public final class OperatorAttemptLog {

    private static final Logger logger = LoggerFactory.getLogger(OperatorAttemptLog.class);

    private static OperatorAttemptLog instance;

    private final LinkedHashMap<String, OperatorAttemptRecord> records = new LinkedHashMap<>();

    /**
     * Records with an unresolved survival horizon, indexed by the generation whose
     * post-selection scan resolves them: an admitted record is filed under both its admission
     * generation (resolves {@code survived_plus1}) and that generation + 4 (resolves {@code
     * survived_plus5}), so {@link #recordSurvivors} only ever scans the records a given
     * generation can actually resolve, not every row ever opened. Entries are removed as they
     * are consumed, so this never grows past what is currently pending.
     */
    private final Map<Integer, List<OperatorAttemptRecord>> pendingSurvivorScans = new HashMap<>();

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
        try {
            if (attemptId == null || attemptId.isEmpty() || records.containsKey(attemptId)) {
                return;
            }
            records.put(attemptId, new OperatorAttemptRecord(attemptId, generation, target, mode, batchSize));
        } catch (RuntimeException e) {
            logger.debug("Operator attempt log: open() failed for {}: {}", attemptId, e.getMessage());
        }
    }

    /** Records what the call behind {@code attemptId} produced for this target. No-op if never opened. */
    public synchronized void recordResponse(String attemptId, TargetedResponse.Status status,
                                            boolean parseSucceeded, boolean targetMatched, int repairTurns,
                                            TargetedResponse.CallUsage usage, TestCase offspringOrNull) {
        try {
            OperatorAttemptRecord record = records.get(attemptId);
            if (record != null) {
                record.recordResponse(status, parseSucceeded, targetMatched, repairTurns, usage, offspringOrNull);
            }
        } catch (RuntimeException e) {
            logger.debug("Operator attempt log: recordResponse() failed for {}: {}", attemptId, e.getMessage());
        }
    }

    /** Records that this attempt was released without ever getting a response (shutdown/pool rejection). */
    public synchronized void recordUndelivered(String attemptId) {
        try {
            OperatorAttemptRecord record = records.get(attemptId);
            if (record != null) {
                record.recordUndelivered();
            }
        } catch (RuntimeException e) {
            logger.debug("Operator attempt log: recordUndelivered() failed for {}: {}", attemptId, e.getMessage());
        }
    }

    /**
     * Records that this attempt's call was delivered — tokens/latency spent — but the run ended
     * before its response could be processed (e.g. still queued at {@code shutdown()}). No-op if
     * never opened, or if a parse status was already recorded for this attempt.
     */
    public synchronized void recordDropped(String attemptId, TargetedResponse.CallUsage usage) {
        try {
            OperatorAttemptRecord record = records.get(attemptId);
            if (record != null) {
                record.recordDropped(usage);
            }
        } catch (RuntimeException e) {
            logger.debug("Operator attempt log: recordDropped() failed for {}: {}", attemptId, e.getMessage());
        }
    }

    /** Records the post-evaluation distance to the target goal and whether it was hit. */
    public synchronized void recordEvaluated(String attemptId, double distanceAfter, boolean targetHit) {
        try {
            OperatorAttemptRecord record = records.get(attemptId);
            if (record != null) {
                record.recordEvaluated(distanceAfter, targetHit);
            }
        } catch (RuntimeException e) {
            logger.debug("Operator attempt log: recordEvaluated() failed for {}: {}", attemptId, e.getMessage());
        }
    }

    /**
     * Records that this attempt's offspring entered the union/offspring set of {@code
     * generation}, and files the record for the two future survivor scans it now owes: one at
     * {@code generation} ({@code survived_plus1}) and one at {@code generation + 4} ({@code
     * survived_plus5}).
     */
    public synchronized void recordAdmitted(String attemptId, TestChromosome offspring, int generation) {
        try {
            OperatorAttemptRecord record = records.get(attemptId);
            if (record == null) {
                return;
            }
            record.recordAdmitted(offspring, generation);
            pendingSurvivorScans.computeIfAbsent(generation, g -> new ArrayList<>()).add(record);
            pendingSurvivorScans.computeIfAbsent(generation + 4, g -> new ArrayList<>()).add(record);
        } catch (RuntimeException e) {
            logger.debug("Operator attempt log: recordAdmitted() failed for {}: {}", attemptId, e.getMessage());
        }
    }

    /**
     * Scans {@code population} once per generation, after environmental selection, only for the
     * admitted records this generation can actually resolve (see {@link
     * #pendingSurvivorScans}): sets {@code survived_plus1} on records admitted in this generation
     * and {@code survived_plus5} on records admitted four generations ago (the offspring then
     * sits in the population that breeds admission + 1 / + 5). A record found gone at plus1 is
     * dropped from its still-pending plus5 scan too — once absent by identity it cannot reappear,
     * so plus5 is resolved to false immediately and the offspring reference is released early.
     */
    public synchronized void recordSurvivors(Collection<TestChromosome> population, int generation) {
        try {
            List<OperatorAttemptRecord> pending = pendingSurvivorScans.remove(generation);
            if (pending == null || pending.isEmpty()) {
                return;
            }
            Set<TestChromosome> presentByIdentity = Collections.newSetFromMap(new IdentityHashMap<>());
            if (population != null) {
                presentByIdentity.addAll(population);
            }
            for (OperatorAttemptRecord record : pending) {
                if (record.applySurvivorScan(presentByIdentity, generation)) {
                    List<OperatorAttemptRecord> plus5Pending = pendingSurvivorScans.get(generation + 4);
                    if (plus5Pending != null) {
                        plus5Pending.remove(record);
                        if (plus5Pending.isEmpty()) {
                            pendingSurvivorScans.remove(generation + 4);
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            logger.debug("Operator attempt log: recordSurvivors() failed: {}", e.getMessage());
        }
    }

    /** The row behind {@code attemptId}, or {@code null}; package-visible for tests. */
    synchronized OperatorAttemptRecord recordFor(String attemptId) {
        return records.get(attemptId);
    }

    public synchronized long getAttempts() {
        try {
            return records.size();
        } catch (RuntimeException e) {
            logger.debug("Operator attempt log: getAttempts() failed: {}", e.getMessage());
            return 0L;
        }
    }

    public synchronized long getAttempts(OperatorTarget.Kind kind) {
        try {
            long count = 0L;
            for (OperatorAttemptRecord record : records.values()) {
                if (record.getKind() == kind) {
                    count++;
                }
            }
            return count;
        } catch (RuntimeException e) {
            logger.debug("Operator attempt log: getAttempts(kind) failed: {}", e.getMessage());
            return 0L;
        }
    }

    public synchronized long getOffspringParsed() {
        return countSafely(OperatorAttemptRecord::isParsed, "getOffspringParsed");
    }

    public synchronized long getOffspringAdmitted() {
        return countSafely(OperatorAttemptRecord::isAdmitted, "getOffspringAdmitted");
    }

    public synchronized long getOffspringSurvived() {
        return countSafely(OperatorAttemptRecord::isSurvivedPlus1, "getOffspringSurvived");
    }

    public synchronized long getTargetHits() {
        return countSafely(OperatorAttemptRecord::isTargetHit, "getTargetHits");
    }

    public synchronized long getTargetImproved() {
        return countSafely(OperatorAttemptRecord::isTargetImproved, "getTargetImproved");
    }

    private long countSafely(java.util.function.Predicate<OperatorAttemptRecord> predicate, String methodName) {
        try {
            return count(predicate);
        } catch (RuntimeException e) {
            logger.debug("Operator attempt log: {}() failed: {}", methodName, e.getMessage());
            return 0L;
        }
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
        try {
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
        } catch (RuntimeException e) {
            logger.debug("Operator attempt log: flush() failed: {}", e.getMessage());
        }
    }
}
