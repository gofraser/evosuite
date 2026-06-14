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
 * Records, every N generations, the full fitness vector (one value per
 * coverage goal, in {@code fitnessFunctions} order) of each rank-0 (Pareto
 * front) individual. Used to render a PCA-projected trajectory of the
 * population through fitness space over the course of the search.
 *
 * <p>Output schema (one row per recorded individual):
 * <pre>
 *   fitness_space_snapshots_&lt;TARGET_CLASS&gt;.csv :
 *     gen,individual_id,goal_0,goal_1,...,goal_{N-1}
 * </pre>
 * {@code individual_id} is the individual's position within the recorded
 * front for that generation (0-based, not stable across generations).
 * Empty cells denote {@code Double.NaN} (no fitness value for that goal).
 * Mirrors the buffer-then-flush pattern used by {@link PopulationSpeciesRecorder}.
 */
public final class FitnessSpaceSnapshotRecorder {

    private static final Logger logger = LoggerFactory.getLogger(FitnessSpaceSnapshotRecorder.class);

    public static final String FILENAME_PREFIX = "fitness_space_snapshots";

    private final List<Row> rows = new ArrayList<>();
    private final String path;
    private int numGoals = -1;

    public FitnessSpaceSnapshotRecorder() {
        this(defaultPath());
    }

    public FitnessSpaceSnapshotRecorder(String path) {
        this.path = path;
    }

    /**
     * Records one snapshot generation's worth of fitness vectors. All
     * vectors are expected to have the same length (the number of coverage
     * goals); the length of the first recorded vector determines the
     * number of {@code goal_*} columns written by {@link #flush()}.
     */
    public synchronized void record(int gen, List<double[]> individualVectors) {
        for (int i = 0; i < individualVectors.size(); i++) {
            double[] vec = individualVectors.get(i);
            if (numGoals < 0) {
                numGoals = vec.length;
            }
            rows.add(new Row(gen, i, vec));
        }
    }

    public synchronized int size() {
        return rows.size();
    }

    public String getPath() {
        return path;
    }

    /** Writes all buffered rows to disk. Safe to call multiple times. */
    public synchronized void flush() {
        File file = new File(path);
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            logger.warn("Could not create directory {} for {}", dir, file.getName());
            return;
        }
        int n = Math.max(numGoals, 0);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            StringBuilder header = new StringBuilder("gen,individual_id");
            for (int i = 0; i < n; i++) {
                header.append(",goal_").append(i);
            }
            writer.write(header.toString());
            writer.newLine();
            for (Row row : rows) {
                StringBuilder sb = new StringBuilder();
                sb.append(row.gen).append(',').append(row.individualId);
                for (int i = 0; i < n; i++) {
                    sb.append(',');
                    double v = (i < row.vector.length) ? row.vector[i] : Double.NaN;
                    if (!Double.isNaN(v)) {
                        sb.append(Double.toString(v));
                    }
                }
                writer.write(sb.toString());
                writer.newLine();
            }
            logger.info("Fitness space snapshots: wrote {} rows to {}", rows.size(), path);
        } catch (IOException e) {
            logger.warn("Failed to write fitness space snapshots: {}", e.getMessage());
        }
    }

    private static String defaultPath() {
        String tc = Properties.TARGET_CLASS;
        String suffix = (tc == null || tc.isEmpty()) ? "" : "_" + sanitize(tc);
        return Properties.REPORT_DIR + File.separator + FILENAME_PREFIX + suffix + ".csv";
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_.$-]", "_");
    }

    private static final class Row {
        final int gen;
        final int individualId;
        final double[] vector;

        Row(int gen, int individualId, double[] vector) {
            this.gen = gen;
            this.individualId = individualId;
            this.vector = vector;
        }
    }
}
