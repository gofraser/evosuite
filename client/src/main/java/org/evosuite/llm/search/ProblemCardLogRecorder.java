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
package org.evosuite.llm.search;

import org.evosuite.Properties;
import org.evosuite.testcase.InjectionSource;
import org.evosuite.testcase.TestFitnessFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Logs problem-card instances at prompt-selection time, plus the goals
 * actually covered by card-informed LLM injections, to a sidecar CSV for
 * offline card-resolution analysis (scripts/analyze_card_resolution.py).
 *
 * <p>Output schema ({@code problem_card_log_<TARGET_CLASS>.csv}):
 * <pre>
 *   event,attempt_id,gen,elapsed_ms,source,card_type,root_cause_key,scope_key,priority,goals
 * </pre>
 * Event kinds: {@code SELECTED} (one row per card instance placed into a
 * prompt; {@code gen} is {@code -1} because the selecting components do not
 * know the GA generation) and {@code COVERED_BY_INJECTION} (one row per
 * injection candidate that newly covered goals; card columns empty).
 * {@code goals} holds goal {@code toString()} descriptions with any literal
 * {@code '|'} replaced by {@code '_'}, joined with {@code '|'}; the cell is
 * CSV-escaped. Descriptions are the join key against
 * {@code objective_index_<TARGET_CLASS>.csv}, whose writer
 * (ObjectiveCoverageRecorder) uses the same CSV escaping — keep the two in
 * sync. Rows with an empty {@code attempt_id} (degenerate configurations
 * without repeated-injection memory) cannot be joined to coverage events and
 * are classified from the coverage timeline alone.
 *
 * <p>Singleton with buffer-then-flush, mirroring {@link DisruptionRecorder};
 * thread-safe because the ASYNC producer logs from its worker thread.
 */
public final class ProblemCardLogRecorder {

    private static final Logger logger = LoggerFactory.getLogger(ProblemCardLogRecorder.class);

    public static final String FILENAME_PREFIX = "problem_card_log";

    private static final String HEADER =
            "event,attempt_id,gen,elapsed_ms,source,card_type,root_cause_key,scope_key,priority,goals";

    private static ProblemCardLogRecorder instance;

    private final List<String> rows = new ArrayList<>();
    private final String path;
    private final long startMs = System.currentTimeMillis();

    private ProblemCardLogRecorder() {
        this(defaultPath());
    }

    /** Test-friendly constructor with an explicit output path. */
    ProblemCardLogRecorder(String path) {
        this.path = path;
    }

    public static synchronized ProblemCardLogRecorder getInstance() {
        if (instance == null) {
            instance = new ProblemCardLogRecorder();
        }
        return instance;
    }

    /** Reset state for a new run (or for testing). */
    public static synchronized void resetInstance() {
        instance = null;
    }

    /** Returns true if problem-card logging is enabled. */
    public static boolean isEnabled() {
        return Properties.PROBLEM_CARD_LOG_ENABLED;
    }

    /**
     * Records one SELECTED row per card placed into a prompt. No-op when
     * disabled or when {@code cards} is empty.
     */
    public void recordSelected(String attemptId, InjectionSource source, List<ProblemCard> cards) {
        if (!isEnabled() || cards == null || cards.isEmpty()) {
            return;
        }
        for (ProblemCard card : cards) {
            if (card == null || card.getType() == null) {
                continue;
            }
            recordSelectedRow(attemptId, source, card.getType().name(),
                    card.getRootCauseKey(), card.getScopeKey(), card.getPriority(),
                    describeGoals(card.getRelatedGoals()));
        }
    }

    /**
     * Records the goals newly covered by one card-informed injection
     * candidate. No-op when disabled or when {@code goals} is empty.
     */
    public void recordCoveredByInjection(String attemptId, InjectionSource source,
                                         int gen, List<TestFitnessFunction> goals) {
        if (!isEnabled() || goals == null || goals.isEmpty()) {
            return;
        }
        recordCoveredRow(attemptId, source, gen, describeGoals(goals));
    }

    /** Package-visible row writer taking pre-rendered goal descriptions (testable). */
    synchronized void recordSelectedRow(String attemptId, InjectionSource source,
                                        String cardType, String rootCauseKey,
                                        String scopeKey, double priority,
                                        List<String> goalDescriptions) {
        rows.add("SELECTED," + csvEscape(attemptId) + ",-1," + elapsedMs() + ","
                + (source == null ? "" : source.name()) + ","
                + csvEscape(cardType) + "," + csvEscape(rootCauseKey) + ","
                + csvEscape(scopeKey) + "," + priority + ","
                + csvEscape(encodeGoals(goalDescriptions)));
    }

    /** Package-visible row writer taking pre-rendered goal descriptions (testable). */
    synchronized void recordCoveredRow(String attemptId, InjectionSource source,
                                       int gen, List<String> goalDescriptions) {
        rows.add("COVERED_BY_INJECTION," + csvEscape(attemptId) + "," + gen + ","
                + elapsedMs() + "," + (source == null ? "" : source.name())
                + ",,,,," + csvEscape(encodeGoals(goalDescriptions)));
    }

    public synchronized int size() {
        return rows.size();
    }

    public String getPath() {
        return path;
    }

    /** Writes all buffered rows to disk. Safe to call multiple times; never throws. */
    public synchronized void flush() {
        File file = new File(path);
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            logger.warn("Could not create directory {} for {}", dir, file.getName());
            return;
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(HEADER);
            writer.newLine();
            for (String row : rows) {
                writer.write(row);
                writer.newLine();
            }
            logger.info("Problem card log: wrote {} rows to {}", rows.size(), path);
        } catch (IOException e) {
            logger.warn("Failed to write problem card log: {}", e.getMessage());
        }
    }

    private long elapsedMs() {
        return System.currentTimeMillis() - startMs;
    }

    private static List<String> describeGoals(List<TestFitnessFunction> goals) {
        List<String> descriptions = new ArrayList<>();
        if (goals != null) {
            for (TestFitnessFunction goal : goals) {
                if (goal != null) {
                    descriptions.add(goal.toString());
                }
            }
        }
        return descriptions;
    }

    /**
     * Joins goal descriptions with {@code '|'}, replacing literal {@code '|'}
     * inside descriptions with {@code '_'} (the analysis script applies the
     * same replacement when loading the objective index).
     */
    static String encodeGoals(List<String> goalDescriptions) {
        StringBuilder sb = new StringBuilder();
        if (goalDescriptions != null) {
            for (String description : goalDescriptions) {
                if (description == null || description.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('|');
                }
                sb.append(description.replace('|', '_'));
            }
        }
        return sb.toString();
    }

    /**
     * Same escaping as ObjectiveCoverageRecorder.csvEscape — the goal
     * descriptions here must parse back byte-identical to the objective
     * index's description column (offline join key).
     */
    private static String csvEscape(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String defaultPath() {
        String tc = Properties.TARGET_CLASS;
        String suffix = (tc == null || tc.isEmpty()) ? "" : "_" + sanitize(tc);
        return Properties.REPORT_DIR + File.separator + FILENAME_PREFIX + suffix + ".csv";
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_.$-]", "_");
    }
}
