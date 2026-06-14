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
 * Records, every N generations, each population individual's covered-branch
 * set (the {@code TRACE_BRANCH_JACCARD} behavior feature, see
 * {@link JaccardSpeciesDistance#getCoveredBranches}) plus per-individual
 * metadata. Used to render population-"shape" small multiples: a joint MDS
 * embedding of pairwise Jaccard branch-coverage distances across sampled
 * generations.
 *
 * <p>Output schema (one row per recorded individual):
 * <pre>
 *   population_shape_&lt;TARGET_CLASS&gt;.csv :
 *     gen,elapsed_ms,idx,species_id,rank,injection_source,covered_goals,sum_fitness,branches,method_sigs,struct_features
 * </pre>
 * {@code branches} holds covered branch IDs sorted ascending, joined with
 * {@code ';'} (empty if no execution trace). {@code method_sigs} holds the
 * sanitized called-method signatures (the {@code METHOD_CALL_JACCARD} genotype
 * feature), sorted, joined with {@code '|'} (empty if none) — used to render
 * the genotype-space twin of the behavior-space MDS grid. {@code struct_features}
 * holds the sanitized static structural-genotype tokens (the
 * {@link JaccardSpeciesDistance#getStructuralFeatures} feature: statement kinds,
 * call signatures with descriptors, and literal values from the test code),
 * sorted, joined with {@code '|'} (empty if none) — a finer genotype twin that
 * separates tests differing only in inputs or call shape. {@code sum_fitness}
 * is written as an empty cell when NaN; {@code injection_source} as an empty
 * cell when null. Note that {@code covered_goals} and {@code sum_fitness} only span
 * goals that were active optimization targets when the individual was
 * evaluated (MOSA/DynaMOSA archive covered goals), so they are comparable
 * within a generation but not across generations; {@code branches} is
 * trace-derived and unaffected by archiving.
 * Mirrors the buffer-then-flush pattern used by {@link PopulationSpeciesRecorder}.
 */
public final class PopulationShapeRecorder {

    private static final Logger logger = LoggerFactory.getLogger(PopulationShapeRecorder.class);

    public static final String FILENAME_PREFIX = "population_shape";

    private static final String HEADER =
            "gen,elapsed_ms,idx,species_id,rank,injection_source,covered_goals,sum_fitness,branches,method_sigs,struct_features";

    private final List<Row> rows = new ArrayList<>();
    private final String path;

    public PopulationShapeRecorder() {
        this(defaultPath());
    }

    public PopulationShapeRecorder(String path) {
        this.path = path;
    }

    /**
     * Records one individual's shape row. {@code branches} must already be
     * sorted ascending; {@code methodSigs} and {@code structFeatures} must
     * already be sanitized (no {@code ','}, {@code '|'} or {@code ';'}
     * characters) and sorted.
     */
    public synchronized void record(int gen, long elapsedMs, int idx, int speciesId,
                                    int rank, String injectionSource, int coveredGoals,
                                    double sumFitness, int[] branches, String[] methodSigs,
                                    String[] structFeatures) {
        rows.add(new Row(gen, elapsedMs, idx, speciesId, rank, injectionSource,
                coveredGoals, sumFitness, branches, methodSigs, structFeatures));
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
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(HEADER);
            writer.newLine();
            for (Row row : rows) {
                StringBuilder sb = new StringBuilder();
                sb.append(row.gen)
                        .append(',').append(row.elapsedMs)
                        .append(',').append(row.idx)
                        .append(',').append(row.speciesId)
                        .append(',').append(row.rank)
                        .append(',');
                if (row.injectionSource != null) {
                    sb.append(row.injectionSource);
                }
                sb.append(',').append(row.coveredGoals).append(',');
                if (!Double.isNaN(row.sumFitness)) {
                    sb.append(Double.toString(row.sumFitness));
                }
                sb.append(',');
                for (int i = 0; i < row.branches.length; i++) {
                    if (i > 0) {
                        sb.append(';');
                    }
                    sb.append(row.branches[i]);
                }
                sb.append(',');
                for (int i = 0; i < row.methodSigs.length; i++) {
                    if (i > 0) {
                        sb.append('|');
                    }
                    sb.append(row.methodSigs[i]);
                }
                sb.append(',');
                for (int i = 0; i < row.structFeatures.length; i++) {
                    if (i > 0) {
                        sb.append('|');
                    }
                    sb.append(row.structFeatures[i]);
                }
                writer.write(sb.toString());
                writer.newLine();
            }
            logger.info("Population shape: wrote {} rows to {}", rows.size(), path);
        } catch (IOException e) {
            logger.warn("Failed to write population shape snapshots: {}", e.getMessage());
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
        final long elapsedMs;
        final int idx;
        final int speciesId;
        final int rank;
        final String injectionSource;
        final int coveredGoals;
        final double sumFitness;
        final int[] branches;
        final String[] methodSigs;
        final String[] structFeatures;

        Row(int gen, long elapsedMs, int idx, int speciesId, int rank,
            String injectionSource, int coveredGoals, double sumFitness, int[] branches,
            String[] methodSigs, String[] structFeatures) {
            this.gen = gen;
            this.elapsedMs = elapsedMs;
            this.idx = idx;
            this.speciesId = speciesId;
            this.rank = rank;
            this.injectionSource = injectionSource;
            this.coveredGoals = coveredGoals;
            this.sumFitness = sumFitness;
            this.branches = (branches != null) ? branches : new int[0];
            this.methodSigs = (methodSigs != null) ? methodSigs : new String[0];
            this.structFeatures = (structFeatures != null) ? structFeatures : new String[0];
        }
    }
}
