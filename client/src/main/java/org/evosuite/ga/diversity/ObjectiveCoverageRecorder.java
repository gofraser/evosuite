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
package org.evosuite.ga.diversity;

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
 * Records, once per (sampled) generation, the best (minimum) normalized
 * fitness value observed across the population for every coverage goal in
 * {@code fitnessFunctions}. Also records a one-time index mapping each
 * goal's column position ("goal id") to its class name, method name, and
 * full description, so the timeline can be rendered as a per-goal or
 * per-method coverage heatmap.
 *
 * <p>Two side-car CSVs are written under {@link Properties#REPORT_DIR}:
 * <pre>
 *   objective_coverage_timeline_&lt;TARGET_CLASS&gt;.csv : gen,goal_id,best_fitness
 *   objective_index_&lt;TARGET_CLASS&gt;.csv            : goal_id,class_name,method_name,description
 * </pre>
 * Mirrors the buffer-then-flush pattern used by {@link PopulationSpeciesRecorder}.
 */
public final class ObjectiveCoverageRecorder {

    private static final Logger logger = LoggerFactory.getLogger(ObjectiveCoverageRecorder.class);

    public static final String TIMELINE_FILENAME_PREFIX = "objective_coverage_timeline";
    public static final String INDEX_FILENAME_PREFIX = "objective_index";

    private static final String TIMELINE_HEADER = "gen,goal_id,best_fitness";
    private static final String INDEX_HEADER = "goal_id,class_name,method_name,description";

    private final List<Row> rows = new ArrayList<>();
    private final String timelinePath;
    private final String indexPath;

    private String[] classNames;
    private String[] methodNames;
    private String[] descriptions;

    public ObjectiveCoverageRecorder() {
        this(defaultTimelinePath(), defaultIndexPath());
    }

    public ObjectiveCoverageRecorder(String timelinePath, String indexPath) {
        this.timelinePath = timelinePath;
        this.indexPath = indexPath;
    }

    /**
     * Records the best (minimum) fitness value seen this generation for each
     * goal. {@code Double.NaN} entries are skipped (the goal had no fitness
     * value this generation).
     *
     * @param gen               the current generation index
     * @param bestFitnessPerGoal array indexed by goal id; {@code Double.NaN}
     *                            for goals with no recorded value
     */
    public synchronized void record(int gen, double[] bestFitnessPerGoal) {
        for (int goalId = 0; goalId < bestFitnessPerGoal.length; goalId++) {
            double v = bestFitnessPerGoal[goalId];
            if (!Double.isNaN(v)) {
                rows.add(new Row(gen, goalId, v));
            }
        }
    }

    /**
     * Sets the one-time goal index. All three arrays must have the same
     * length and are indexed by goal id (matching the {@code goalId} used
     * in {@link #record}).
     */
    public synchronized void setGoalIndex(String[] classNames, String[] methodNames, String[] descriptions) {
        this.classNames = classNames;
        this.methodNames = methodNames;
        this.descriptions = descriptions;
    }

    public synchronized int size() {
        return rows.size();
    }

    public String getTimelinePath() {
        return timelinePath;
    }

    public String getIndexPath() {
        return indexPath;
    }

    /** Writes the timeline CSV and (if set) the goal index CSV. Safe to call multiple times. */
    public synchronized void flush() {
        writeTimeline();
        writeIndex();
    }

    private void writeTimeline() {
        File file = new File(timelinePath);
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            logger.warn("Could not create directory {} for {}", dir, file.getName());
            return;
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(TIMELINE_HEADER);
            writer.newLine();
            for (Row row : rows) {
                writer.write(row.gen + "," + row.goalId + "," + Double.toString(row.bestFitness));
                writer.newLine();
            }
            logger.info("Objective coverage timeline: wrote {} rows to {}", rows.size(), timelinePath);
        } catch (IOException e) {
            logger.warn("Failed to write objective coverage timeline: {}", e.getMessage());
        }
    }

    private void writeIndex() {
        if (classNames == null) {
            return;
        }
        File file = new File(indexPath);
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            logger.warn("Could not create directory {} for {}", dir, file.getName());
            return;
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(INDEX_HEADER);
            writer.newLine();
            for (int i = 0; i < classNames.length; i++) {
                writer.write(i + "," + csvEscape(classNames[i]) + "," + csvEscape(methodNames[i]) + ","
                        + csvEscape(descriptions[i]));
                writer.newLine();
            }
            logger.info("Objective index: wrote {} goals to {}", classNames.length, indexPath);
        } catch (IOException e) {
            logger.warn("Failed to write objective index: {}", e.getMessage());
        }
    }

    private static String csvEscape(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String defaultTimelinePath() {
        return Properties.REPORT_DIR + File.separator + TIMELINE_FILENAME_PREFIX + suffix() + ".csv";
    }

    private static String defaultIndexPath() {
        return Properties.REPORT_DIR + File.separator + INDEX_FILENAME_PREFIX + suffix() + ".csv";
    }

    private static String suffix() {
        String tc = Properties.TARGET_CLASS;
        return (tc == null || tc.isEmpty()) ? "" : "_" + sanitize(tc);
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_.$-]", "_");
    }

    private static final class Row {
        final int gen;
        final int goalId;
        final double bestFitness;

        Row(int gen, int goalId, double bestFitness) {
            this.gen = gen;
            this.goalId = goalId;
            this.bestFitness = bestFitness;
        }
    }
}
