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
 */
package org.evosuite.llm;

import org.evosuite.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Round-aligned sidecar for the iterative LLM-only strategy.
 */
public final class IterativeLlmTimelineRecorder {

    private static final Logger logger =
            LoggerFactory.getLogger(IterativeLlmTimelineRecorder.class);
    private static final String HEADER =
            "round,round_type,elapsed_ms,remaining_ms,target_goals,query_latency_ms,"
                    + "tests_parsed,tests_unique,duplicates,new_goals,covered_goals,total_goals,"
                    + "suite_size,suite_length,coverage,assertions_retained,"
                    + "tests_with_assertions,failure";

    private final List<Row> rows = new ArrayList<>();
    private final String path;

    public IterativeLlmTimelineRecorder() {
        this(defaultPath());
    }

    IterativeLlmTimelineRecorder(String path) {
        this.path = path;
    }

    public synchronized void record(int round, String roundType,
                                    long elapsedMs, long remainingMs,
                                    int targetGoals, long queryLatencyMs,
                                    int testsParsed, int testsUnique,
                                    int duplicates, int newGoals,
                                    int coveredGoals, int totalGoals,
                                    int suiteSize, int suiteLength,
                                    double coverage, int assertionsRetained,
                                    int testsWithAssertions, String failure) {
        rows.add(new Row(round, roundType, elapsedMs, remainingMs, targetGoals,
                queryLatencyMs, testsParsed, testsUnique, duplicates, newGoals,
                coveredGoals, totalGoals, suiteSize, suiteLength, coverage,
                assertionsRetained, testsWithAssertions, failure));
    }

    public synchronized void flush() {
        if (!Properties.LLM_ITERATIVE_TIMELINE_ENABLED) {
            return;
        }
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            logger.warn("Could not create directory {} for {}", parent, file.getName());
            return;
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(HEADER);
            writer.newLine();
            for (Row row : rows) {
                writer.write(row.toCsv());
                writer.newLine();
            }
        } catch (IOException e) {
            logger.warn("Failed to write iterative LLM timeline: {}", e.getMessage());
        }
    }

    private static String defaultPath() {
        String target = Properties.TARGET_CLASS;
        String suffix = target == null || target.isEmpty()
                ? "" : "_" + target.replaceAll("[^A-Za-z0-9_.$-]", "_");
        return Properties.REPORT_DIR + File.separator
                + "iterative_llm_timeline" + suffix + ".csv";
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static final class Row {
        private final int round;
        private final String roundType;
        private final long elapsedMs;
        private final long remainingMs;
        private final int targetGoals;
        private final long queryLatencyMs;
        private final int testsParsed;
        private final int testsUnique;
        private final int duplicates;
        private final int newGoals;
        private final int coveredGoals;
        private final int totalGoals;
        private final int suiteSize;
        private final int suiteLength;
        private final double coverage;
        private final int assertionsRetained;
        private final int testsWithAssertions;
        private final String failure;

        private Row(int round, String roundType, long elapsedMs, long remainingMs,
                    int targetGoals, long queryLatencyMs, int testsParsed,
                    int testsUnique, int duplicates, int newGoals,
                    int coveredGoals, int totalGoals, int suiteSize,
                    int suiteLength, double coverage, int assertionsRetained,
                    int testsWithAssertions, String failure) {
            this.round = round;
            this.roundType = roundType;
            this.elapsedMs = elapsedMs;
            this.remainingMs = remainingMs;
            this.targetGoals = targetGoals;
            this.queryLatencyMs = queryLatencyMs;
            this.testsParsed = testsParsed;
            this.testsUnique = testsUnique;
            this.duplicates = duplicates;
            this.newGoals = newGoals;
            this.coveredGoals = coveredGoals;
            this.totalGoals = totalGoals;
            this.suiteSize = suiteSize;
            this.suiteLength = suiteLength;
            this.coverage = coverage;
            this.assertionsRetained = assertionsRetained;
            this.testsWithAssertions = testsWithAssertions;
            this.failure = failure;
        }

        private String toCsv() {
            return round + "," + csv(roundType) + "," + elapsedMs + ","
                    + remainingMs + "," + targetGoals + "," + queryLatencyMs
                    + "," + testsParsed + "," + testsUnique + "," + duplicates
                    + "," + newGoals + "," + coveredGoals + "," + totalGoals
                    + "," + suiteSize + "," + suiteLength + "," + coverage
                    + "," + assertionsRetained + "," + testsWithAssertions
                    + "," + csv(failure);
        }
    }
}
