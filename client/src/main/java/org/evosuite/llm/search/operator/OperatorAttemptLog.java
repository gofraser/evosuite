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
import org.evosuite.utils.SidecarCsv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One row per LLM operator target, in both {@code SYNC} and {@code ASYNC} mode: {@link #open}
 * registers a target as it is prompted, {@link #recordResponse}/{@link #recordUndelivered}/{@link
 * #recordDropped} record what the call produced (or that it never got the chance to), and {@link
 * #recordEvaluated}/{@link #recordAdmitted}/{@link #recordSurvivors} record what the search did
 * with it afterwards.
 *
 * <p>A row is kept in memory only until it is <em>final</em> — nothing more can happen to it:
 * <ol>
 *   <li>its outcome is one of {@code NO_BUDGET}, {@code FAILED}, {@code PARSE_FAILED}, {@code
 *   UNMATCHED}, {@code UNDELIVERED} or {@code DROPPED} — it never produced an offspring admitted
 *   for this target;</li>
 *   <li>it was admitted and both survival horizons are resolved ({@code survived_plus5} set,
 *   possibly resolved early — see {@link OperatorAttemptRecord#applySurvivorScan}); or</li>
 *   <li>it parsed but was never admitted, and two consecutive {@link #recordSurvivors} scans have
 *   found it still unadmitted — by then the generation that could have admitted it is long past.
 *   </li>
 * </ol>
 * Memory is therefore bounded by the number of attempts still awaiting an outcome — in flight,
 * awaiting drain, or inside the five-generation survival window — independent of {@code
 * llm_max_calls} (which may be {@code 0}, i.e. unlimited).
 *
 * <p>The {@code LLM_Operator_*} per-target runtime variables ({@link #getAttempts}, {@link
 * #getOffspringParsed} and its neighbours) are aggregate counters maintained as each event
 * happens, never derived by scanning the rows still in memory.
 *
 * <p>The sidecar CSV, when {@link Properties#LLM_OPERATOR_ATTEMPT_LOG_ENABLED} is set, is
 * streamed via {@link OperatorAttemptCsvWriter}: a row is appended (batched with any other rows
 * retired by the same call) the moment it goes final, and whatever is still open is appended by
 * {@link #flush()} at the end of the run. Rows therefore do not appear in the file in insertion
 * order; {@code generation} and {@code attempt_id} are in every row so analysis can re-sort them.
 * When the sidecar is disabled, retired and flushed rows are simply dropped, nothing is written.
 *
 * <p>Singleton like {@code ProblemCardLogRecorder}. Every public method holds the log's monitor
 * while it touches rows or counters: {@code recordResponse} is called from worker threads in
 * {@code ASYNC} mode, everything else from the search thread. The sidecar write itself happens
 * after that monitor is released (rows are rendered to text first), serialised on the writer, so
 * a worker retiring a failed batch never makes the search thread wait on disk I/O. Every method
 * that touches a row or the sidecar file is also best-effort:
 * telemetry must never break the search it is observing, so a {@link RuntimeException} anywhere
 * in its body is caught and logged rather than propagated; a failed sidecar write is logged once
 * per instance and the row is dropped rather than retried or kept forever.
 */
public final class OperatorAttemptLog {

    private static final Logger logger = LoggerFactory.getLogger(OperatorAttemptLog.class);

    private static OperatorAttemptLog instance;

    private final LinkedHashMap<String, OperatorAttemptRecord> records = new LinkedHashMap<>();
    private final OperatorAttemptCsvWriter csvWriter = new OperatorAttemptCsvWriter();
    /** Guarded by {@link #csvWriter}'s monitor, not the log's. */
    private boolean writeFailureLogged;
    /** Guarded by {@link #csvWriter}'s monitor, not the log's. */
    private long rowsWritten;

    private long attempts;
    private final Map<OperatorTarget.Kind, Long> attemptsByKind = new EnumMap<>(OperatorTarget.Kind.class);
    private long parsed;
    private long admitted;
    private long survived;
    private long targetHits;
    private long targetImproved;

    /**
     * Records with an unresolved survival horizon, indexed by the generation whose
     * post-selection scan resolves them: an admitted record is filed under both its admission
     * generation (resolves {@code survived_plus1}) and that generation + 4 (resolves {@code
     * survived_plus5}), so {@link #recordSurvivors} only ever scans the records a given
     * generation can actually resolve, not every row still open. Entries are removed as they are
     * consumed, so this never grows past what is currently pending.
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
        return SidecarCsv.path("operator_attempts");
    }

    /** Registers one operator target as it is prompted. No-op for a null/empty or duplicate attempt id. */
    public synchronized void open(String attemptId, int generation, OperatorTarget target, String mode,
                                  int batchSize) {
        try {
            if (attemptId == null || attemptId.isEmpty() || records.containsKey(attemptId)) {
                return;
            }
            OperatorAttemptRecord record = new OperatorAttemptRecord(attemptId, generation, target, mode, batchSize);
            records.put(attemptId, record);
            attempts++;
            attemptsByKind.merge(record.getKind(), 1L, Long::sum);
        } catch (RuntimeException e) {
            logger.debug("Operator attempt log: open() failed for {}: {}", attemptId, e.getMessage());
        }
    }

    /** Records what the call behind {@code attemptId} produced for this target. No-op if never opened. */
    public void recordResponse(String attemptId, TargetedResponse.Status status,
                               boolean parseSucceeded, boolean targetMatched, int repairTurns,
                               TargetedResponse.CallUsage usage, TestCase offspringOrNull) {
        List<String> rows = Collections.emptyList();
        synchronized (this) {
            try {
                OperatorAttemptRecord record = records.get(attemptId);
                if (record == null) {
                    return;
                }
                boolean applied = record.recordResponse(status, parseSucceeded, targetMatched, repairTurns,
                        usage, offspringOrNull);
                if (!applied) {
                    return;
                }
                if (record.isParsed()) {
                    parsed++;
                }
                if (record.isCaseATerminal()) {
                    rows = retire(Collections.singletonList(record));
                }
            } catch (RuntimeException e) {
                logger.debug("Operator attempt log: recordResponse() failed for {}: {}", attemptId, e.getMessage());
            }
        }
        writeRows(rows);
    }

    /** Records that this attempt was released without ever getting a response (shutdown/pool rejection). */
    public void recordUndelivered(String attemptId) {
        List<String> rows = Collections.emptyList();
        synchronized (this) {
            try {
                OperatorAttemptRecord record = records.get(attemptId);
                if (record != null && record.recordUndelivered()) {
                    rows = retire(Collections.singletonList(record));
                }
            } catch (RuntimeException e) {
                logger.debug("Operator attempt log: recordUndelivered() failed for {}: {}", attemptId,
                        e.getMessage());
            }
        }
        writeRows(rows);
    }

    /**
     * Records that this attempt's call was delivered — tokens/latency spent — but the run ended
     * before its response could be processed (e.g. still queued at {@code shutdown()}). No-op if
     * never opened, or if a parse status was already recorded for this attempt.
     */
    public void recordDropped(String attemptId, TargetedResponse.CallUsage usage) {
        List<String> rows = Collections.emptyList();
        synchronized (this) {
            try {
                OperatorAttemptRecord record = records.get(attemptId);
                if (record != null && record.recordDropped(usage)) {
                    rows = retire(Collections.singletonList(record));
                }
            } catch (RuntimeException e) {
                logger.debug("Operator attempt log: recordDropped() failed for {}: {}", attemptId, e.getMessage());
            }
        }
        writeRows(rows);
    }

    /** Records the post-evaluation distance to the target goal and whether it was hit. */
    public synchronized void recordEvaluated(String attemptId, double distanceAfter, boolean targetHit) {
        try {
            OperatorAttemptRecord record = records.get(attemptId);
            if (record == null) {
                return;
            }
            int flags = record.recordEvaluated(distanceAfter, targetHit);
            if ((flags & OperatorAttemptRecord.EVAL_NEW_HIT) != 0) {
                targetHits++;
            }
            if ((flags & OperatorAttemptRecord.EVAL_NEW_IMPROVED) != 0) {
                targetImproved++;
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
            if (record == null || !record.recordAdmitted(offspring, generation)) {
                return;
            }
            admitted++;
            pendingSurvivorScans.computeIfAbsent(generation, g -> new ArrayList<>()).add(record);
            pendingSurvivorScans.computeIfAbsent(generation + 4, g -> new ArrayList<>()).add(record);
        } catch (RuntimeException e) {
            logger.debug("Operator attempt log: recordAdmitted() failed for {}: {}", attemptId, e.getMessage());
        }
    }

    /**
     * Scans {@code population} once per generation, after environmental selection. Resolves the
     * admitted records this generation can actually settle (see {@link #pendingSurvivorScans}),
     * retiring any that become final (rule (b): both survival horizons resolved); then bumps
     * every still-open {@code PARSED}-but-unadmitted record's scan count, retiring it once that
     * reaches two (rule (c)): its offspring's generation of admission-or-rejection is long past.
     */
    public void recordSurvivors(Collection<TestChromosome> population, int generation) {
        List<String> rows = Collections.emptyList();
        synchronized (this) {
            rows = retireSettledLocked(population, generation);
        }
        writeRows(rows);
    }

    /** The row-mutating part of {@link #recordSurvivors}; returns the rendered rows to write. */
    private List<String> retireSettledLocked(Collection<TestChromosome> population, int generation) {
        try {
            List<OperatorAttemptRecord> toRetire = new ArrayList<>();
            List<OperatorAttemptRecord> pending = pendingSurvivorScans.remove(generation);
            if (pending != null && !pending.isEmpty()) {
                Set<TestChromosome> presentByIdentity = Collections.newSetFromMap(new IdentityHashMap<>());
                if (population != null) {
                    presentByIdentity.addAll(population);
                }
                for (OperatorAttemptRecord record : pending) {
                    int flags = record.applySurvivorScan(presentByIdentity, generation);
                    if ((flags & OperatorAttemptRecord.SCAN_PLUS1_SURVIVED) != 0) {
                        survived++;
                    }
                    if ((flags & OperatorAttemptRecord.SCAN_PLUS5_RESOLVED_EARLY) != 0) {
                        List<OperatorAttemptRecord> plus5Pending = pendingSurvivorScans.get(generation + 4);
                        if (plus5Pending != null) {
                            plus5Pending.remove(record);
                            if (plus5Pending.isEmpty()) {
                                pendingSurvivorScans.remove(generation + 4);
                            }
                        }
                    }
                    if ((flags & OperatorAttemptRecord.SCAN_FINAL) != 0) {
                        toRetire.add(record);
                    }
                }
            }
            for (OperatorAttemptRecord record : records.values()) {
                if (record.isParsed() && !record.isAdmitted() && record.bumpUnadmittedParsedScanCount() >= 2) {
                    toRetire.add(record);
                }
            }
            return retire(toRetire);
        } catch (RuntimeException e) {
            logger.debug("Operator attempt log: recordSurvivors() failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Removes {@code toRetire} from memory and returns their rendered CSV rows for {@link
     * #writeRows} — rendered here, under the log's monitor, so the write can happen outside it.
     * Empty when the sidecar is disabled: the rows are then simply dropped.
     */
    private List<String> retire(List<OperatorAttemptRecord> toRetire) {
        if (toRetire.isEmpty()) {
            return Collections.emptyList();
        }
        for (OperatorAttemptRecord record : toRetire) {
            records.remove(record.getAttemptId());
        }
        if (!isSidecarEnabled()) {
            return Collections.emptyList();
        }
        List<String> rows = new ArrayList<>(toRetire.size());
        for (OperatorAttemptRecord record : toRetire) {
            rows.add(record.toCsvRow());
        }
        return rows;
    }

    /**
     * Appends {@code rows} to the sidecar in one batched write, serialised on the writer but not
     * on the log's monitor. A write failure is logged once per instance and the rows dropped,
     * not retried.
     */
    private void writeRows(List<String> rows) {
        if (rows.isEmpty()) {
            return;
        }
        synchronized (csvWriter) {
            try {
                csvWriter.appendRows(resolveSidecarPath(), rows);
                rowsWritten += rows.size();
            } catch (IOException | RuntimeException e) {
                if (!writeFailureLogged) {
                    writeFailureLogged = true;
                    logger.debug("Operator attempt log: failed to write to the sidecar CSV; "
                            + "further failures will not be logged: {}", e.getMessage());
                }
            }
        }
    }

    /** The row behind {@code attemptId}, or {@code null} if never opened or already retired; for tests. */
    synchronized OperatorAttemptRecord recordFor(String attemptId) {
        return records.get(attemptId);
    }

    public synchronized long getAttempts() {
        return attempts;
    }

    public synchronized long getAttempts(OperatorTarget.Kind kind) {
        Long count = attemptsByKind.get(kind);
        return count == null ? 0L : count;
    }

    public synchronized long getOffspringParsed() {
        return parsed;
    }

    public synchronized long getOffspringAdmitted() {
        return admitted;
    }

    public synchronized long getOffspringSurvived() {
        return survived;
    }

    public synchronized long getTargetHits() {
        return targetHits;
    }

    public synchronized long getTargetImproved() {
        return targetImproved;
    }

    /**
     * Appends every row still in memory to the sidecar CSV (in insertion order), whatever its
     * state — in-flight rows too, rendered with what they have — then clears them so a second
     * call writes nothing new. No-op when there are no rows left; writes nothing (but still
     * clears) when the sidecar is disabled.
     */
    public void flush() {
        List<String> rows;
        synchronized (this) {
            try {
                if (records.isEmpty()) {
                    return;
                }
                List<OperatorAttemptRecord> remaining = new ArrayList<>(records.values());
                pendingSurvivorScans.clear();
                rows = retire(remaining);
            } catch (RuntimeException e) {
                logger.debug("Operator attempt log: flush() failed: {}", e.getMessage());
                return;
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        synchronized (csvWriter) {
            try {
                csvWriter.appendRows(resolveSidecarPath(), rows);
                rowsWritten += rows.size();
                logger.info("Operator attempt log: wrote {} rows to {} ({} total this run)",
                        rows.size(), resolveSidecarPath(), rowsWritten);
            } catch (IOException | RuntimeException e) {
                logger.warn("Failed to write operator attempt log: {}", e.getMessage());
            }
        }
    }
}
